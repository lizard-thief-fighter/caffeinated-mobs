package caffeinated_mobs.extinction;

import caffeinated_mobs.CaffeinatedMobs;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.random.Weight;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public final class Extinction {
    private static final Logger LOGGER = LogUtils.getLogger();

    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Caffeinated Mobs extinction");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<ServerLevel, ExtinctionData> DATA = new IdentityHashMap<>();
    // spawner checks entries by identity, so reuse copies
    private static final Map<SpawnerData, Int2ObjectOpenHashMap<SpawnerData>> REWEIGHTED = new IdentityHashMap<>();
    private static final Int2ObjectOpenHashMap<SpawnerData> VACANCIES = new Int2ObjectOpenHashMap<>();
    private static final int TICK_INTERVAL = 20;

    public static void register(IEventBus bus) {
        bus.addListener(Extinction::onLevelTick);
        bus.addListener(EventPriority.LOWEST, Extinction::onDeath);
        bus.addListener(Extinction::onPotentialSpawns);
        bus.addListener(Extinction::onLevelUnload);
        bus.addListener((ServerAboutToStartEvent e) -> Settings.load());
        bus.addListener(Extinction::onServerStopping);
        bus.addListener(Extinction::onServerStopped);
        bus.addListener((TagsUpdatedEvent e) -> Pools.clearCache());
        bus.addListener((RegisterCommandsEvent e) -> ExtinctionCommands.register(e.getDispatcher()));
    }

    public static void setPermanent(ServerLevel level, BlockPos pos, int radius, ResourceLocation pool, boolean permanent) {
        MobCategory category = Pools.category(pool);
        if (category == null) throw new IllegalArgumentException("Unknown extinction pool " + pool);
        Settings settings = Settings.current();
        LongArrayList keys = regionsAround(pos, radius);
        data(level).update(permanent
                ? s -> s.edit(keys, pool, category, true, settings)
                : s -> s.edit(keys, pool, category, false, settings));
    }

    public static float capFactor(ServerLevel level, ChunkPos chunk, MobCategory category) {
        RegionState region = data(level).state.atChunk(chunk.x, chunk.z);
        return region == null || region.capFactor == null ? 1f : region.capFactor[category.ordinal()];
    }

    public static boolean reload(MinecraftServer server) {
        if (!Settings.load()) return false;
        Settings settings = Settings.current();
        REWEIGHTED.clear();
        for (ServerLevel level : server.getAllLevels()) data(level).update(s -> s.rebuild(settings));
        return true;
    }

    static ExtinctionData data(ServerLevel level) {
        ExtinctionData data = DATA.get(level);
        if (data == null) {
            data = level.getDataStorage().computeIfAbsent(ExtinctionData.factory(), ExtinctionData.NAME);
            DATA.put(level, data);
        }
        return data;
    }

    static LongArrayList regionsAround(BlockPos pos, int radius) {
        int rx = pos.getX() >> 7, rz = pos.getZ() >> 7;
        LongArrayList keys = new LongArrayList();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) keys.add(ExtinctionState.key(rx + dx, rz + dz));
        }
        return keys;
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % TICK_INTERVAL == 0) data(level).tick(level);
    }

    private static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        MobCategory category = mob.getType().getCategory();
        if (category == MobCategory.MISC) return;
        Settings.MobRule rule = Settings.current().mob(mob.getType());
        data(level).recordKill(mob.getBlockX(), mob.getBlockZ(), Pools.of(mob.getType()), category, rule.origin(), rule.adjacent());
    }

    private static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        RegionState region = data(level).state.atChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (region == null || region.levels.isEmpty()) return;

        List<SpawnerData> entries = event.getSpawnerDataList();
        Settings settings = Settings.current();
        double original = 0, kept = 0, target = 0;
        boolean changed = false;
        for (SpawnerData entry : List.copyOf(entries)) {
            int weight = entry.getWeight().asInt();
            if (entry.type == CaffeinatedMobs.VACANCY.get()) continue;
            byte lvl = region.levels.getByte(Pools.of(entry.type));
            original += weight;
            if (lvl == 0) {
                kept += weight;
                target += weight;
                continue;
            }
            Settings.MobRule rule = settings.mob(entry.type);
            // lvl 2/3 hold total spawns to lvl 1s count
            target += weight * (1 - rule.removed1());
            int newWeight = Math.round(weight * (1 - rule.removed(lvl)));
            kept += newWeight;
            if (newWeight == weight) continue;
            changed = true;
            event.removeSpawnerData(entry);
            if (newWeight > 0) event.addSpawnerData(reweighted(entry, newWeight));
        }
        if (!changed) return;
        if (kept <= 0) {
            event.setCanceled(true);
            return;
        }

        int vacancy = target <= 0 ? 0 : (int) Math.round(kept * original / target - kept);
        if (vacancy > 0) event.addSpawnerData(VACANCIES.computeIfAbsent(vacancy,
                w -> new SpawnerData(CaffeinatedMobs.VACANCY.get(), Weight.of(w), 1, 1)));
    }

    private static SpawnerData reweighted(SpawnerData entry, int weight) {
        return REWEIGHTED.computeIfAbsent(entry, e -> new Int2ObjectOpenHashMap<>())
                .computeIfAbsent(weight, w -> new SpawnerData(entry.type, Weight.of(w), entry.minCount, entry.maxCount));
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) DATA.remove(level);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        try {
            WORKER.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Extinction changes didn't finish before the server stopped", e);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        DATA.clear();
        REWEIGHTED.clear();
        VACANCIES.clear();
        Pools.clearCache();
    }

    private Extinction() {}
}
