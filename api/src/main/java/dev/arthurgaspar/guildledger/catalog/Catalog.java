package dev.arthurgaspar.guildledger.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.arthurgaspar.guildledger.common.ApiException;
import tools.jackson.databind.json.JsonMapper;

/** Static game data loaded from {@code shared/catalog.json}, the same file the web client bundles. */
@Component
public class Catalog {

    public record Limits(int teamMembers, int partySize, int invitationDays, int maxLevel) {}
    public record Difficulty(String code, String label, int rankPoints, int exp, int suggestedPoke) {}
    public record Rank(String code, String name, int minPoints, String maxDifficulty, int rosterCap, int bagCap) {}
    public record Dungeon(String code, String name, int floors, String direction, String suggestedDifficulty) {}
    public record Item(String code, String name, String category, int refValue) {}
    public record Species(int id, String name, List<String> types) {}

    public record Data(int version, Limits limits, List<Difficulty> difficulties, List<Rank> ranks,
                       List<String> missionKinds, List<Dungeon> dungeons, List<Item> items, List<Species> species) {}

    private final Data data;
    private final Map<String, Difficulty> difficulties;
    private final Map<String, Dungeon> dungeons;
    private final Map<String, Item> items;
    private final Map<Integer, Species> species;

    public Catalog(JsonMapper mapper) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/shared/catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("shared/catalog.json is missing from the classpath");
            }
            this.data = mapper.readValue(in, Data.class);
        }
        this.difficulties = index(data.difficulties(), Difficulty::code);
        this.dungeons = index(data.dungeons(), Dungeon::code);
        this.items = index(data.items(), Item::code);
        this.species = index(data.species(), Species::id);
    }

    private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
        return values.stream().collect(Collectors.toUnmodifiableMap(key, Function.identity()));
    }

    public Data data() {
        return data;
    }

    public Limits limits() {
        return data.limits();
    }

    public Difficulty difficulty(String code) {
        Difficulty d = difficulties.get(code);
        if (d == null) {
            throw ApiException.invalid("mission.difficulty-unknown", "Unknown difficulty " + code);
        }
        return d;
    }

    /** Position on the E..STAR scale, for comparisons. */
    public int difficultyOrder(String code) {
        return data.difficulties().indexOf(difficulty(code));
    }

    public Dungeon dungeon(String code) {
        Dungeon d = dungeons.get(code);
        if (d == null) {
            throw ApiException.invalid("mission.dungeon-unknown", "Unknown dungeon " + code);
        }
        return d;
    }

    public Item item(String code) {
        Item i = items.get(code);
        if (i == null) {
            throw ApiException.invalid("item.unknown", "Unknown item " + code);
        }
        return i;
    }

    public Species species(int id) {
        Species s = species.get(id);
        if (s == null) {
            throw ApiException.invalid("species.unknown", "No species with dex number " + id);
        }
        return s;
    }

    /** The highest rank whose threshold the points reach. */
    public Rank rankFor(int points) {
        Rank current = data.ranks().getFirst();
        for (Rank r : data.ranks()) {
            if (points >= r.minPoints()) {
                current = r;
            }
        }
        return current;
    }

    public Optional<Rank> nextRank(Rank rank) {
        int i = data.ranks().indexOf(rank);
        return i + 1 < data.ranks().size() ? Optional.of(data.ranks().get(i + 1)) : Optional.empty();
    }

    public String floorLabel(Dungeon dungeon, int floor) {
        return "B".equals(dungeon.direction()) ? "B" + floor + "F" : floor + "F";
    }
}
