package ru.upsic.mapautoregistrar.component;

import org.springframework.stereotype.Component;
import ru.upsic.mapautoregistrar.annotation.WithKey;
import ru.upsic.mapautoregistrar.api.TestStrategy;

@Component
@WithKey(value = "BETA")
public class TestStrategyBeta implements TestStrategy {
    @Override
    public String getKey() { return "BETA"; }
}
