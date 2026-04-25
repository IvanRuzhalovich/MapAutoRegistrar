package ru.upsic.mapautoregistrar.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;
import ru.upsic.mapautoregistrar.annotation.KeyMethod;
import ru.upsic.mapautoregistrar.annotation.KeyedMap;
import ru.upsic.mapautoregistrar.annotation.WithKey;
import ru.upsic.mapautoregistrar.exception.MapAutoRegistrarException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Component
public class KeyedMapPostProcessor implements BeanPostProcessor, ApplicationContextAware {
    private static final String COMMA_SPACE = ", ";
    private static final String MSG_MULTIPLE_KEY_METHODS =
            "Интерфейс [%s] нарушает контракт: найдено несколько методов с @KeyMethod: [%s].\n" +
                    "Решение: оставьте ровно один метод с аннотацией @KeyMethod, возвращающий ключ";

    private static final String MSG_VOID_KEY_METHOD =
            "Метод [%s::%s] с аннотацией @KeyMethod не может возвращать void.\n" +
                    "Решение: измените возвращаемый тип на класс ключа";

    private static final String MSG_KEY_METHOD_WITH_PARAMS =
            "Метод [%s::%s] с аннотацией @KeyMethod не должен принимать параметры (найдено: %d).\n" +
                    "Решение: удалите все параметры метода";
    private static final String DUPLICATE_KEY = "Дублирующийся ключ '%s' — бин '%s' перезапишет '%s'";

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        if (isInfrastructureBean(beanName)) {
            return bean;
        }

        injectCustomMaps(bean);

        return bean;
    }

    /**
     * Находит все поля с @KeyedMap и подменяет мапы
     */
    private void injectCustomMaps(Object targetBean) {

        // Получаем целевой класс (убираем прокси)
        Class<?> targetClass = AopUtils.isAopProxy(targetBean)
                ? AopUtils.getTargetClass(targetBean)
                : targetBean.getClass();

        // Сканируем ВСЮ иерархию
        ReflectionUtils.doWithFields(targetClass, field -> {
            KeyedMap strategyMap = AnnotatedElementUtils.findMergedAnnotation(field, KeyedMap.class);

            if (nonNull(strategyMap) && Map.class.isAssignableFrom(field.getType())) {
                performInjection(targetBean, field);
            }
        });
    }

    /**
     * Выполняет подмену Map
     */
    private void performInjection(Object targetBean, Field field) {
        try {
            ReflectionUtils.makeAccessible(field);

            ResolvableType mapType = ResolvableType.forField(field);
            Class<?> keyType = mapType.getGeneric(0).resolve();
            Class<?> valueType = mapType.getGeneric(1).resolve();

            if (valueType == null) {
                log.warn("Не удалось определить тип значения для поля {}.{} — пропускаем",
                        field.getDeclaringClass().getSimpleName(), field.getName());
                return;
            }

            if (isNull(keyType)) {
                log.warn("Не удалось определить тип ключа!");
            }

            Map<?, ?> customMap;
            Map<?, ?> beansByName;
            if (keyType == String.class) {
                // String ключи
                beansByName = getBeansOfTypeFromOriginalMap(field, targetBean, valueType);
            } else
                beansByName = applicationContext.getBeansOfType(valueType);

            if (CollectionUtils.isEmpty(beansByName)) {
                log.warn("Нет бинов типа {} для поля {}.{}",
                        valueType.getSimpleName(), field.getDeclaringClass().getSimpleName(), field.getName());
                customMap = Collections.emptyMap();
            } else {
                Method keyMethod = findKeyMethodInInterface(valueType);
                if (nonNull(keyMethod)) {
                    customMap = buildKeyedMapFromMethod(beansByName, keyMethod);
                } else {
                    customMap = buildKeyedMapFromAnnotation(beansByName);
                }
            }
            ReflectionUtils.setField(field, targetBean, customMap);

        } catch (MapAutoRegistrarException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Ошибка подмены Map в поле {}.{}: {}",
                    field.getDeclaringClass().getSimpleName(), field.getName(), e.getMessage(), e);
        }
    }

    /**
     * Извлекает исходную мапу из поля или собирает бины из контекста, если поле пустое/нулевое
     */
    @SuppressWarnings("unchecked")
    private <T> Map<String, T> getBeansOfTypeFromOriginalMap(Field field, Object targetBean, Class<T> valueType) {
        try {
            Map<?, ?> originalMap = (Map<?, ?>) field.get(targetBean);

            // Если в поле уже есть бины используем их
            if (!CollectionUtils.isEmpty(originalMap)) {
                Object firstKey = originalMap.keySet().iterator().next();
                if (!(firstKey instanceof String)) {
                    log.warn("Поле {}.{} ожидает String-ключи, но найдено ключ типа {} — пропускаем оригинальную мапу",
                            field.getDeclaringClass().getSimpleName(), field.getName(), firstKey.getClass().getSimpleName());
                    return applicationContext.getBeansOfType(valueType);
                }
                return (Map<String, T>) originalMap;
            }
        } catch (IllegalAccessException ignored) {
            // Игнорируем, переход к поиску в контексте
        }
        return applicationContext.getBeansOfType(valueType);
    }

    /**
     * Трансформирует Map<beanName, Strategy> → Map<registryKey, Strategy>.
     * Ключ берётся из @WithKey на классе реализации
     */
    @SuppressWarnings("unchecked")
    private <T> Map<String, T> buildKeyedMapFromAnnotation(Map<?, ?> originalMap) {

        Map<String, T> result = new HashMap<>();

        for (Map.Entry<?, ?> entry : originalMap.entrySet()) {
            String beanName = (String) entry.getKey();
            T bean = (T) entry.getValue();

            Class<?> beanClass = AopUtils.getTargetClass(bean);
            WithKey keyAnnotation = beanClass.getAnnotation(WithKey.class);

            if (isNull(keyAnnotation)) {
                log.debug("Бин '{}' не имеет @WithKey — пропускаем", beanName);
                continue;
            }

            String registryKey = keyAnnotation.value();

            if (result.containsKey(registryKey)) {
                String message = String.format(DUPLICATE_KEY, registryKey, beanName, result.get(registryKey));
                log.warn(message);
            }

            result.put(registryKey, bean);
        }
        return result;
    }

    /**
     * Режим 2: Трансформирует ключи через вызов метода с @KeyMethod в интерфейсе
     * Map<beanName, Bean> → Map<method.invoke(bean), Bean>
     */
    @SuppressWarnings("unchecked")
    private <K, T> Map<K, T> buildKeyedMapFromMethod(Map<?, ?> originalMap, Method keyMethod) {
        if (isNull(keyMethod)) {
            log.warn("Не найден метод с @KeyMethod");
            return (Map<K, T>) originalMap;
        }

        Map<K, T> result = new HashMap<>(originalMap.size());

        for (Map.Entry<?, ?> entry : originalMap.entrySet()) {
            String beanName = (String) entry.getKey();
            T bean = (T) entry.getValue();

            try {
                // Вызываем метод: key = bean.getKey()
                @SuppressWarnings("unchecked")
                K customKey = (K) ReflectionUtils.invokeMethod(keyMethod, bean);

                if (isNull(customKey)) {
                    log.debug("Метод @KeyMethod вернул null для бина '{}' — пропускаем", beanName);
                    continue;
                }

                if (result.containsKey(customKey)) {
                    String message = String.format(DUPLICATE_KEY, customKey, beanName, result.get(customKey));
                    log.warn(message);
                }
                result.put(customKey, bean);

            } catch (Exception e) {
                log.debug("Ошибка вызова @KeyMethod для бина '{}': {}", beanName, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Исключает инфраструктурные бины от обработки
     */
    private boolean isInfrastructureBean(String beanName) {
        // Эвристика по имени
        if (beanName.startsWith("_") || beanName.endsWith("PostProcessor")) {
            return true;
        }

        // Проверка роли
        if (applicationContext instanceof ConfigurableListableBeanFactory) {
            ConfigurableListableBeanFactory beanFactory =
                    (ConfigurableListableBeanFactory) applicationContext;

            if (beanFactory.containsBeanDefinition(beanName)) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                return bd.getRole() == ROLE_INFRASTRUCTURE;
            }
        }
        return false;
    }

    /**
     * Поиск метода с аннотацией @KeyMethod
     */
    private Method findKeyMethodInInterface(Class<?> iface) {
        List<Method> annotatedMethods = Arrays.stream(ReflectionUtils.getAllDeclaredMethods(iface))
                .filter(m -> m.isAnnotationPresent(KeyMethod.class))
                .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(annotatedMethods))
            return null;

        if (annotatedMethods.size() > 1) {
            String methodSignatures = annotatedMethods.stream()
                    .map(m -> m.getName() + "(" + Arrays.toString(m.getParameterTypes()) + ")")
                    .sorted()
                    .collect(Collectors.joining(COMMA_SPACE));
            throw new MapAutoRegistrarException(String.format(MSG_MULTIPLE_KEY_METHODS, iface.getSimpleName(), methodSignatures));
        }
        return validateKeyMethod(iface, annotatedMethods);
    }

    private Method validateKeyMethod(Class<?> iface, List<Method> annotatedMethods) {
        Method keyMethod = annotatedMethods.get(0);

        if (keyMethod.getReturnType().equals(void.class)) {
            throw new MapAutoRegistrarException(String.format(MSG_VOID_KEY_METHOD, iface.getSimpleName(), keyMethod.getName()));
        }

        if (keyMethod.getParameterCount() != 0) {
            throw new MapAutoRegistrarException(
                    String.format(MSG_KEY_METHOD_WITH_PARAMS, iface.getSimpleName(), keyMethod.getName(), keyMethod.getParameterCount())
            );
        }
        return keyMethod;
    }
}