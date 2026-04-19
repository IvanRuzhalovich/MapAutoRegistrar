package ru.upsic.mapautoregistrar.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.upsic.mapautoregistrar.annotation.KeyMethod;
import ru.upsic.mapautoregistrar.exception.MapAutoRegistrarException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;

class KeyedMapValidationTest {

    private final KeyedMapPostProcessor processor = new KeyedMapPostProcessor();

    private final Method findKeyMethod;

    public KeyedMapValidationTest() throws NoSuchMethodException {
        this.findKeyMethod = KeyedMapPostProcessor.class.getDeclaredMethod("findKeyMethodInInterface", Class.class);
        this.findKeyMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Выбрасывает ошибку при нескольких @KeyMethod")
    void testMultipleMethods() {
        Throwable thrown = catchThrowable(() -> invokeMethod(BadMulti.class));

        assertThat(thrown)
                .isInstanceOf(MapAutoRegistrarException.class)
                .hasMessageContaining("несколько методов");
    }

    @Test
    @DisplayName("Выбрасывает ошибку при void методе")
    void testVoidMethod() {
        Throwable thrown = catchThrowable(() -> invokeMethod(BadVoid.class));

        assertThat(thrown)
                .isInstanceOf(MapAutoRegistrarException.class)
                .hasMessageContaining("void");
    }

    @Test
    @DisplayName("Выбрасывает ошибку при параметрах")
    void testParamsMethod() {
        Throwable thrown = catchThrowable(() -> invokeMethod(BadParams.class));

        assertThat(thrown)
                .isInstanceOf(MapAutoRegistrarException.class)
                .hasMessageContaining("параметры");
    }

    @Test
    @DisplayName("Находит корректный метод")
    void testGoodMethod() throws Exception {
        Object result = findKeyMethod.invoke(processor, Good.class);
        assertThat(result).isNotNull().isInstanceOf(Method.class);
        assertThat(((Method) result).getName()).isEqualTo("k");
    }

    private void invokeMethod(Class<?> iface) throws Throwable {
        try {
            findKeyMethod.invoke(processor, iface);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    interface BadMulti {
        @KeyMethod
        String k1();

        @KeyMethod
        String k2();
    }

    interface BadVoid {
        @KeyMethod
        void k();
    }

    interface BadParams {
        @KeyMethod
        String k(String s);
    }

    interface Good {
        @KeyMethod
        String k();
    }
}
