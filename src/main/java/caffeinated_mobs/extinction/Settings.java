package caffeinated_mobs.extinction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

final class Settings {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FILE = "caffeinated_mobs-extinction.json";
    static final String SAMPLE_FILE = "caffeinated_mobs-extinction.sample.json";

    static final String ORIGIN = "origin_pressure", ADJACENT = "adjacent_pressure", LEVEL1 = "level1", LEVEL2 = "level2",
            DECAY0 = "decay_level0", DECAY1 = "decay_level1", DECAY2 = "decay_level2", PERIOD = "period_days",
            REMOVED1 = "pool_spawns_removed_level1", REMOVED2 = "pool_spawns_removed_level2",
            CUT1 = "category_cap_cut_level1", CUT2 = "category_cap_cut_level2", FLOOR = "category_cap_floor";

    private static final Map<String, int[]> KEYS = new LinkedHashMap<>();

    static {
        key(ORIGIN, 2, 0, 1000);
        key(ADJACENT, 1, 0, 1000);
        key(LEVEL1, 25, 1, 1_000_000);
        key(LEVEL2, 50, 1, 1_000_000);
        key(DECAY0, 5, 0, 1_000_000);
        key(DECAY1, 5, 0, 1_000_000);
        key(DECAY2, 5, 0, 1_000_000);
        key(PERIOD, 1, 1, 1000);
        key(REMOVED1, 50, 0, 100);
        key(REMOVED2, 100, 0, 100);
        key(CUT1, 15, 0, 100);
        key(CUT2, 20, 0, 100);
        key(FLOOR, 50, 0, 100);
    }

    private static volatile Settings current = new Settings(defaults(), Map.of(), Map.of());
    private final Map<String, Integer> general;
    private final Map<ResourceLocation, Map<String, Integer>> pools;
    private final Map<ResourceLocation, Map<String, Integer>> mobs;
    final float floor;
    private final Map<ResourceLocation, PoolRule> poolRules = new ConcurrentHashMap<>();
    private final Map<EntityType<?>, MobRule> mobRules = new ConcurrentHashMap<>();

    record PoolRule(int level1, int level2, int[] decay, int period, float cut1, float cut2) {
        byte level(int pressure) {
            return pressure >= level2 ? (byte) 2 : pressure >= level1 ? (byte) 1 : (byte) 0;
        }

        int decay(byte level) {
            return decay[Math.min(level, 2)];
        }

        float cut(byte level) {
            return level >= 2 ? cut2 : level == 1 ? cut1 : 0;
        }
    }

    record MobRule(int origin, int adjacent, float removed1, float removed2) {
        float removed(byte level) {
            return level >= 2 ? removed2 : level == 1 ? removed1 : 0;
        }
    }

    private Settings(Map<String, Integer> general, Map<ResourceLocation, Map<String, Integer>> pools,
                     Map<ResourceLocation, Map<String, Integer>> mobs) {
        this.general = general;
        this.pools = pools;
        this.mobs = mobs;
        this.floor = general.get(FLOOR) / 100f;
    }

    static Settings current() {
        return current;
    }

    // pool rules: pool > mob (if its own pool) > general
    PoolRule pool(ResourceLocation pool) {
        return poolRules.computeIfAbsent(pool, p -> {
            var maps = List.of(pools.getOrDefault(p, Map.of()), mobs.getOrDefault(p, Map.of()), general);
            return new PoolRule(get(maps, LEVEL1), get(maps, LEVEL2),
                    new int[] {get(maps, DECAY0), get(maps, DECAY1), get(maps, DECAY2)}, get(maps, PERIOD),
                    get(maps, CUT1) / 100f, get(maps, CUT2) / 100f);
        });
    }

    // mob rules: mob > its pool > general
    MobRule mob(EntityType<?> type) {
        return mobRules.computeIfAbsent(type, t -> {
            var maps = List.of(mobs.getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(t), Map.of()),
                    pools.getOrDefault(Pools.of(t), Map.of()), general);
            return new MobRule(get(maps, ORIGIN), get(maps, ADJACENT), get(maps, REMOVED1) / 100f, get(maps, REMOVED2) / 100f);
        });
    }

    static boolean load() {
        Path dir = FMLPaths.CONFIGDIR.get();
        Path path = dir.resolve(FILE);
        try {
            if (Files.notExists(path)) Files.writeString(path, DEFAULT_FILE);
            Path sample = dir.resolve(SAMPLE_FILE);
            if (Files.notExists(sample)) Files.writeString(sample, SAMPLE);
            JsonObject root;
            try (Reader reader = Files.newBufferedReader(path)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            Map<String, Integer> general = defaults();
            if (root.get("general") instanceof JsonObject section) general.putAll(values(section, "general", true));
            current = new Settings(general, overrides(root, "pools"), overrides(root, "mobs"));
            return true;
        } catch (Exception e) {
            LOGGER.error("Couldn't load {}; keeping the previous extinction settings", path, e);
            return false;
        }
    }

    private static Map<ResourceLocation, Map<String, Integer>> overrides(JsonObject root, String name) {
        Map<ResourceLocation, Map<String, Integer>> out = new HashMap<>();
        if (!(root.get(name) instanceof JsonObject section)) return out;
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null || !(entry.getValue() instanceof JsonObject values)) {
                LOGGER.warn("Extinction config: skipping {}.\"{}\" (not an id with an object)", name, entry.getKey());
                continue;
            }
            out.put(id, values(values, name + ".\"" + id + "\"", false));
        }
        return out;
    }

    private static Map<String, Integer> values(JsonObject section, String where, boolean general) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            int[] spec = KEYS.get(entry.getKey());
            if (spec == null || (!general && entry.getKey().equals(FLOOR))
                    || !(entry.getValue() instanceof JsonPrimitive p) || !p.isNumber()) {
                LOGGER.warn("Extinction config: ignoring {} in {}", entry.getKey(), where);
                continue;
            }
            out.put(entry.getKey(), Math.clamp(p.getAsLong(), spec[1], spec[2]));
        }
        return out;
    }

    private static int get(List<Map<String, Integer>> maps, String key) {
        for (Map<String, Integer> map : maps) {
            Integer value = map.get(key);
            if (value != null) return value;
        }
        throw new IllegalStateException("No value for " + key);
    }

    private static Map<String, Integer> defaults() {
        Map<String, Integer> out = new HashMap<>();
        KEYS.forEach((key, spec) -> out.put(key, spec[0]));
        return out;
    }

    private static void key(String key, int value, int min, int max) {
        KEYS.put(key, new int[] {value, min, max});
    }

    private static final String DEFAULT_FILE = """
            {
              "_about": [
                "Caffeinated Mobs: extinction. Player kills put pressure on a mob's pool in 8x8-chunk regions.",
                "A pool is an entity type tag caffeinated_mobs:extinction_pools/<name> (zombies, skeletons, spiders ship with the mod),",
                "or a mob that isn't in one. At the end of each period the period's kills are added, the pool's level is set from",
                "that total, then the decay for that level comes off.",
                "Level 0: normal. Level 1: some of the pool's spawns removed. Level 2: more removed (all, by default).",
                "Level 3: level 2 forever; only from commands or events.",
                "Reload in game with /caffeinated_mobs extinction reload. Keys starting with _ are notes.",
                "Overrides go in pools and mobs; see caffeinated_mobs-extinction.sample.json."
              ],
              "general": {
                "_pressure": "Pressure a player kill adds in its own region, and in each of the 8 regions around it",
                "origin_pressure": 2,
                "adjacent_pressure": 1,
                "_levels": "Period total that sets level 1 and level 2",
                "level1": 25,
                "level2": 50,
                "_decay": "Pressure lost at the end of each period, by the level just set",
                "decay_level0": 5,
                "decay_level1": 5,
                "decay_level2": 5,
                "_period": "Period length in in-game days",
                "period_days": 1,
                "_removed": "Percent of the pool's spawns removed at levels 1 and 2. At level 1 other mobs don't take them; at level 2 (and 3) they take just enough to keep total spawns at level 1's count",
                "pool_spawns_removed_level1": 50,
                "pool_spawns_removed_level2": 100,
                "_cap": "Percent cut to the local mob cap of the pool's spawn category (monster, creature...) in its region at levels 1 and 2; cuts from several pools add up. Floor: lowest the cap can go, in percent (general only)",
                "category_cap_cut_level1": 15,
                "category_cap_cut_level2": 20,
                "category_cap_floor": 50
              },
              "_overrides": [
                "Any key from general except category_cap_floor.",
                "pools: the pool's thresholds, decay, period and cap cuts; also pressure and spawns removed for its mobs, unless a mob has its own entry.",
                "mobs: pressure added and spawns removed for that mob, even inside a pool. Thresholds, decay, period and cap cuts only apply if the mob is its own pool."
              ],
              "pools": {},
              "mobs": {}
            }
            """;

    private static final String SAMPLE = """
            {
              "_about": "Sample overrides for caffeinated_mobs-extinction.json. Not read: copy entries into its pools and mobs.",
              "pools": {
                "caffeinated_mobs:extinction_pools/zombies": {
                  "_note": "A wiped-out horde comes back slowly",
                  "level1": 35,
                  "level2": 70,
                  "decay_level2": 3
                }
              },
              "mobs": {
                "minecraft:creeper": {
                  "_note": "Creepers are scarce, so each kill counts more",
                  "origin_pressure": 3,
                  "adjacent_pressure": 2
                },
                "minecraft:cow": {
                  "_note": "Herds are counted every three days",
                  "level1": 15,
                  "period_days": 3,
                  "pool_spawns_removed_level1": 30,
                  "category_cap_cut_level1": 5
                }
              }
            }
            """;
}
