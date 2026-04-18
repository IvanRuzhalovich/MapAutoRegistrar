package ru.upsic.mapautoregistrar.processor;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
        try {
            injectCustomMaps(bean);
        } catch (Exception e) {
            log.error("Ошибка инъекции кастомных мап в бин '{}': {}. Инъекция не выполнена", beanName, e.getMessage(), e);
        }
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
            var strategyMap = AnnotatedElementUtils.findMergedAnnotation(field, KeyedMap.class);

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

            if (keyType == String.class) {
                // String ключи
                Map<?, ?> originalMap = (Map<?, ?>) field.get(targetBean);

                if (CollectionUtils.isEmpty(originalMap)) {
                    log.debug("Поле {}.{} пустое — пропускаем", field.getDeclaringClass().getSimpleName(), field.getName());
                    return;
                }

                customMap = buildKeyedMapFromAnnotation(originalMap);

            } else {
                Map<String, ?> beansByName = applicationContext.getBeansOfType(valueType);

                if (beansByName.isEmpty()) {
                    log.debug("Не найдено бинов типа {} для поля {}.{} — создаём пустую мапу",
                            valueType.getSimpleName(),
                            field.getDeclaringClass().getSimpleName(),
                            field.getName());
                    customMap = Collections.emptyMap();
                } else {
                    // Трансформируем Map<String, Bean> → Map<KeyType, Bean> через @KeyMethod
                    customMap = buildKeyedMapFromMethod(beansByName, valueType);
                }
            }

            ReflectionUtils.setField(field, targetBean, customMap);

        } catch (IllegalAccessException e) {
            log.warn("Не удалось прочитать поле {}.{}: {}",
                    field.getDeclaringClass().getSimpleName(), field.getName(), e.getMessage());
        } catch (Exception e) {
            log.warn("Ошибка подмены Map в поле {}.{}: {}",
                    field.getDeclaringClass().getSimpleName(), field.getName(), e.getMessage());
        }
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
                log.warn("Дублирующийся ключ '{}' — бин '{}' перезапишет '{}'", registryKey, beanName, result.get(registryKey));
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
    private <K, T> Map<K, T> buildKeyedMapFromMethod(Map<?, ?> originalMap, Class<T> valueType) {
        // Находим метод с @KeyMethod в интерфейсе
        Method keyMethod = findUniqueKeyMethodOrThrow(valueType);

        if (isNull(keyMethod)) {
            log.warn("В интерфейсе {} не найден метод с @KeyMethod — пропускаем трансформацию", valueType.getSimpleName());
            return (Map<K, T>) originalMap;
        }

        Map<K, T> result = HashMap.newHashMap(originalMap.size());

        for (Map.Entry<?, ?> entry : originalMap.entrySet()) {
            String beanName = (String) entry.getKey();
            T bean = (T) entry.getValue();

            try {
                // Вызываем метод: key = bean.getKey()
                @SuppressWarnings("unchecked")
                K customKey = (K) ReflectionUtils.invokeMethod(keyMethod, bean);

                if (customKey == null) {
                    log.warn("Метод @KeyMethod вернул null для бина '{}' — пропускаем", beanName);
                    continue;
                }

                if (result.containsKey(customKey)) {
                    log.warn("Дублирующийся ключ '{}' — бин '{}' перезапишет '{}'",
                            customKey, beanName, result.get(customKey));
                }
                result.put(customKey, bean);

            } catch (Exception e) {
                log.warn("Не удалось получить ключ через @KeyMethod для бина '{}': {}",
                        beanName, e.getMessage());
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
        if (applicationContext instanceof ConfigurableListableBeanFactory beanFactory
                && beanFactory.containsBeanDefinition(beanName)) {

            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            return bd.getRole() == ROLE_INFRASTRUCTURE;
        }
        return false;
    }

    /**
     * Поиск метода с аннотацией @KeyMethod
     */
    public static Method findUniqueKeyMethodOrThrow(Class<?> iface) {
        List<Method> annotatedMethods = new ArrayList<>();
        for (Method method : iface.getDeclaredMethods()) {
            if (method.isAnnotationPresent(KeyMethod.class)) {
                annotatedMethods.add(method);
            }
        }

        if (CollectionUtils.isEmpty(annotatedMethods))
            return null;

        if (annotatedMethods.size() > 1) {
            String methodSignatures = annotatedMethods.stream()
                    .map(m -> m.getName() + "(" + Arrays.toString(m.getParameterTypes()) + ")")
                    .sorted()
                    .collect(Collectors.joining(COMMA_SPACE));
            throw new MapAutoRegistrarException(String.format(MSG_MULTIPLE_KEY_METHODS, iface.getSimpleName(), methodSignatures));
        }
        return getKeyMethod(iface, annotatedMethods);
    }

    private static Method getKeyMethod(Class<?> iface, List<Method> annotatedMethods) {
        Method keyMethod = annotatedMethods.getFirst();

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