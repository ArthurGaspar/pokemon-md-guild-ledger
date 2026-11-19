package dev.arthurgaspar.expeditions.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.arthurgaspar.expeditions.platform.web.ApiException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Dungeons and difficulties from {@code shared/catalog.json}, the file the ledger API and web client use. */
@Component
public class GameData {

    public record Difficulty(String code, String label) {}
    public record Dungeon(String code, String name, int floors) {}
    record Data(List<Difficulty> difficulties, List<Dungeon> dungeons) {}

    private final Map<String, Difficulty> difficulties;
    private final Map<String, Dungeon> dungeons;

    public GameData(JsonMapper mapper) throws IOException {
        JsonMapper lenient = mapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try (InputStream in = getClass().getResourceAsStream("/shared/catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("shared/catalog.json is missing from the classpath");
            }
            Data data = lenient.readValue(in, Data.class);
            this.difficulties = index(data.difficulties(), Difficulty::code);
            this.dungeons = index(data.dungeons(), Dungeon::code);
        }
    }

    private static <V> Map<String, V> index(List<V> values, Function<V, String> key) {
        return values.stream().collect(Collectors.toUnmodifiableMap(key, Function.identity()));
    }

    public Difficulty difficulty(String code) {
        Difficulty d = difficulties.get(code);
        if (d == null) {
            throw ApiException.invalid("expedition.difficulty-unknown", "Unknown difficulty " + code);
        }
        return d;
    }

    public Dungeon dungeon(String code) {
        Dungeon d = dungeons.get(code);
        if (d == null) {
            throw ApiException.invalid("expedition.dungeon-unknown", "Unknown dungeon " + code);
        }
        return d;
    }
}
