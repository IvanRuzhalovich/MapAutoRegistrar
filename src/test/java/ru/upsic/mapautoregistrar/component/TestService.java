package ru.upsic.mapautoregistrar.component;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.upsic.mapautoregistrar.annotation.KeyedMap;
import ru.upsic.mapautoregistrar.api.TestStrategy;

import java.util.Map;

@Getter
@Component
@RequiredArgsConstructor
public class TestService {

    @KeyedMap
    private final Map<String, TestStrategy> strategiesByKey;

    private final Map<String, TestStrategy> strategiesByName;

}
