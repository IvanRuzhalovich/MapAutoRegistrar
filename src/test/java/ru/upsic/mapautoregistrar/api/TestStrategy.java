package ru.upsic.mapautoregistrar.api;

import ru.upsic.mapautoregistrar.annotation.KeyMethod;

public interface TestStrategy {
    @KeyMethod
    String getKey();
}
