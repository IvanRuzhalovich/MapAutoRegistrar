package ru.upsic.mapautoregistrar.component;

import org.springframework.stereotype.Component;
import ru.upsic.mapautoregistrar.api.TestStrategyWithKeyMethod;

@Component
public class TestStrategyWithKeyMethodBeta implements TestStrategyWithKeyMethod {

    @Override
    public String getKey() {
        return "BETA";
    }
}
