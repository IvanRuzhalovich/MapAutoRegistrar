package ru.upsic.mapautoregistrar.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.upsic.mapautoregistrar.annotation.KeyedMap;
import ru.upsic.mapautoregistrar.api.TestStrategyWithKey;
import ru.upsic.mapautoregistrar.api.TestStrategyWithKeyMethod;

import java.util.Map;

@Getter
@Component
@RequiredArgsConstructor
public class TestService {

    @KeyedMap
    private final Map<String, TestStrategyWithKey> strategiesByKey;

    private final Map<String, TestStrategyWithKey> strategiesByName;

    @KeyedMap
    private final Map<String, TestStrategyWithKeyMethod> strategiesByKeyMethod;

}
