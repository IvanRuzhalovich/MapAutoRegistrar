package ru.upsic.mapautoregistrar.component;

import org.springframework.stereotype.Component;
import ru.upsic.mapautoregistrar.annotation.WithKey;
import ru.upsic.mapautoregistrar.api.TestStrategyWithKey;

@Component
@WithKey(value = "BETA")
public class TestStrategyWithKeyBeta implements TestStrategyWithKey {
}
