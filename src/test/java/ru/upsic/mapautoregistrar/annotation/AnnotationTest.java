package ru.upsic.mapautoregistrar.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

public class AnnotationTest {
    @Nested
    @DisplayName("Тесты @RegistryKey")
    class KeyMethodTests {

        @Test
        @DisplayName("Имеет правильную RetentionPolicy.RUNTIME")
        void hasRuntimeRetention() {
            Retention retention = KeyMethod.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        @DisplayName("Имеет правильный Target METHOD и TYPE")
        void hasCorrectTarget() {
            Target target = KeyMethod.class.getAnnotation(Target.class);
            assertThat(target.value()).contains(ElementType.METHOD);
        }
    }
}
