package ru.upsic.mapautoregistrar.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Внутренняя конфигурация библиотеки. Отвечает за сканирование компонентов библиотеки
 */
@Configuration
@ComponentScan(basePackages = "ru.upsic.mapautoregistrar")
public class MapAutoRegistrarInternalConfig {
}
