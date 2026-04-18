package ru.upsic.mapautoregistrar.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import ru.upsic.mapautoregistrar.api.TestStrategy;
import ru.upsic.mapautoregistrar.component.TestService;
import ru.upsic.mapautoregistrar.component.TestStrategyAlpha;
import ru.upsic.mapautoregistrar.component.TestStrategyBeta;
import ru.upsic.mapautoregistrar.component.TestStrategyDuplicate;
import ru.upsic.mapautoregistrar.config.TestConfig;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KeyedMapInjectionPostProcessorTest {

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.register(KeyedMapPostProcessor.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("Поле с @KeyedMap получает Map с ключами из @WithKey")
    void shouldInjectMapWithRegistryKeys() {
        context.register(TestConfig.class);
        context.refresh();

        TestService testService = context.getBean(TestService.class);
        Map<String, TestStrategy> strategies = testService.getStrategiesByKey();

        assertThat(strategies)
                .isNotNull()
                .hasSize(2)
                .containsOnlyKeys("ALPHA", "BETA")
                .extracting("ALPHA", "BETA")
                .containsExactlyInAnyOrder(
                        context.getBean(TestStrategyAlpha.class),
                        context.getBean(TestStrategyBeta.class)
                );
    }

    @Test
    @DisplayName("Стратегии без @WithKey пропускаются")
    void shouldSkipStrategiesWithoutAnnotation() {
        context.register(TestConfig.class);
        context.refresh();

        TestService testService = context.getBean(TestService.class);
        Map<String, TestStrategy> strategies = testService.getStrategiesByKey();

        assertThat(strategies)
                .doesNotContainKey("testStrategyDuplicate")
                .doesNotContainValue(context.getBean(TestStrategyDuplicate.class));
    }

    @Test
    @DisplayName("Поле без @KeyedMap остаётся без изменений")
    void shouldNotInjectMapWithoutAnnotation() {
        context.register(TestConfig.class);
        context.refresh();

        TestService testService = context.getBean(TestService.class);
        Map<String, TestStrategy> strategiesByName = testService.getStrategiesByName();

        assertThat(strategiesByName)
                .containsKey("testStrategyAlpha")
                .containsKey("testStrategyBeta");
        assertThat(strategiesByName.keySet())
                .doesNotContain("ALPHA")
                .doesNotContain("BETA");
    }

    @Test
    @DisplayName("Постпроцессор не падает при пустом поле")
    void shouldHandleEmptyFieldGracefully() {
        context.register(TestConfig.class);
        context.refresh();

        TestService service = new TestService(new HashMap<>(), new HashMap<>());
        KeyedMapPostProcessor processor =
                context.getBean(KeyedMapPostProcessor.class);

        assertThat(service.getStrategiesByKey()).isEmpty();

        assertThatCode(() ->
                processor.postProcessAfterInitialization(service, "testService")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Поле с @KeyedMap получает Map с ключами из @WithKey")
    void shouldInjectMapWithWithKeyAnnotations() {
        context.register(TestConfig.class);
        context.refresh();

        TestService testService = context.getBean(TestService.class);
        Map<String, TestStrategy> strategies = testService.getStrategiesByKey();

        assertThat(strategies)
                .isNotNull()
                .hasSize(2)
                .containsOnlyKeys("ALPHA", "BETA")
                .containsEntry("ALPHA", context.getBean(TestStrategyAlpha.class))
                .containsEntry("BETA", context.getBean(TestStrategyBeta.class));
    }

    @Test
    @DisplayName("Поле без @KeyedMap остаётся с ключами-именами бинов")
    void shouldNotTransformMapWithoutKeyedMapAnnotation() {
        context.register(TestConfig.class);
        context.refresh();

        TestService testService = context.getBean(TestService.class);
        Map<String, TestStrategy> strategiesByName = testService.getStrategiesByName();

        assertThat(strategiesByName)
                .isNotNull()
                .containsKey("testStrategyAlpha")
                .containsKey("testStrategyBeta")
                .doesNotContainKey("ALPHA")
                .doesNotContainKey("BETA");
    }

    @Test
    @DisplayName("Инфраструктурные бины пропускаются")
    void shouldSkipInfrastructureBeans() {
        context.register(TestConfig.class);
        context.refresh();

        KeyedMapPostProcessor processor = context.getBean(KeyedMapPostProcessor.class);
        Object mockBean = new Object();

        assertThat(processor.postProcessAfterInitialization(mockBean, "_internal")).isSameAs(mockBean);
        assertThat(processor.postProcessAfterInitialization(mockBean, "myPostProcessor")).isSameAs(mockBean);
    }
}
