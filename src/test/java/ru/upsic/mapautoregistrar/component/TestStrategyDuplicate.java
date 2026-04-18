package ru.upsic.mapautoregistrar.component;

import org.springframework.stereotype.Component;
import ru.upsic.mapautoregistrar.api.TestStrategy;

@Component
public class TestStrategyDuplicate implements TestStrategy {
    @Override
    public String getKey() {
        return "ALPHA";
    }
}
