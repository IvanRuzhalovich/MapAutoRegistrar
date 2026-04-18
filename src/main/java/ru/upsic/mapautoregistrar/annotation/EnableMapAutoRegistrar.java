package ru.upsic.mapautoregistrar.annotation;

import org.springframework.context.annotation.Import;
import ru.upsic.mapautoregistrar.config.MapAutoRegistrarInternalConfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Включает автоматическую регистрацию мап.
 * Необходимо указать базовый пакет Вашего приложения
 */
@Target(ElementType.TYPE)
@Retention(value = RetentionPolicy.RUNTIME)
@Import(MapAutoRegistrarInternalConfig.class)
public @interface EnableMapAutoRegistrar {
}
