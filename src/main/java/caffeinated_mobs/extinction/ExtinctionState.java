package caffeinated_mobs.extinction;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;

record ExtinctionState(Long2ObjectMap<RegionState> regions, Map<ResourceLocation, MobCategory> categories) {
    static final ExtinctionState EMPTY = new ExtinctionState(new Long2ObjectOpenHashMap<>(), Map.of());

    ExtinctionState rollover(Long2ObjectMap<Object2IntOpenHashMap<ResourceLocation>> kills, Map<ResourceLocation, MobCategory> newCategories,
                             long oldDay, long newDay, boolean force, Settings settings) {
        Map<ResourceLocation, MobCategory> cats = new HashMap<>(categories);
        cats.putAll(newCategories);
        LongOpenHashSet keys = new LongOpenHashSet(regions.keySet());
        keys.addAll(kills.keySet());
        Long2ObjectOpenHashMap<RegionState> out = new Long2ObjectOpenHashMap<>();
        for (long key : keys) {
            RegionState old = regions.get(key);
            Object2IntOpenHashMap<ResourceLocation> pressure = copy(old == null ? null : old.pressure);
            Object2IntOpenHashMap<ResourceLocation> peak = copy(old == null ? null : old.peak);
            Object2IntOpenHashMap<ResourceLocation> banked = copy(old == null ? null : old.banked);
            Object2IntMap<ResourceLocation> added = kills.get(key);
            if (added != null) added.forEach((pool, n) -> banked.mergeInt(pool, n, Integer::sum));

            Set<ResourceLocation> pools = new HashSet<>(banked.keySet());
            pools.addAll(pressure.keySet());
            pools.addAll(peak.keySet());
            for (ResourceLocation pool : pools) {
                Settings.PoolRule rule = settings.pool(pool);
                // clock set back = one period
                long periods = force ? 1 : newDay < oldDay ? 1
                        : Math.floorDiv(newDay, rule.period()) - Math.floorDiv(oldDay, rule.period());
                if (periods <= 0) continue;
                int total = pressure.getInt(pool) + banked.removeInt(pool);
                if (total <= 0) {
                    pressure.removeInt(pool);
                    peak.removeInt(pool);
                    continue;
                }
                peak.put(pool, total);
                long left = total - (long) rule.decay(rule.level(total)) * periods;
                if (left > 0) pressure.put(pool, (int) left);
                else pressure.removeInt(pool);
            }
            put(out, key, RegionState.of(pressure, peak, banked, old == null ? Set.of() : old.permanent, cats, settings));
        }
        return new ExtinctionState(out, Map.copyOf(cats));
    }

    ExtinctionState edit(LongCollection keys, @Nullable ResourceLocation pool, @Nullable MobCategory category,
                         boolean setPermanent, Settings settings) {
        Map<ResourceLocation, MobCategory> cats = new HashMap<>(categories);
        if (pool != null && category != null) cats.put(pool, category);
        Long2ObjectOpenHashMap<RegionState> out = new Long2ObjectOpenHashMap<>(regions);
        for (long key : keys) {
            RegionState old = regions.get(key);
            Object2IntOpenHashMap<ResourceLocation> pressure = copy(old == null ? null : old.pressure);
            Object2IntOpenHashMap<ResourceLocation> peak = copy(old == null ? null : old.peak);
            Object2IntOpenHashMap<ResourceLocation> banked = copy(old == null ? null : old.banked);
            Set<ResourceLocation> permanent = old == null ? new HashSet<>() : new HashSet<>(old.permanent);
            if (setPermanent) {
                permanent.add(pool);
            } else if (pool == null) {
                pressure.clear();
                peak.clear();
                banked.clear();
                permanent.clear();
            } else {
                pressure.removeInt(pool);
                peak.removeInt(pool);
                banked.removeInt(pool);
                permanent.remove(pool);
            }
            out.remove(key);
            put(out, key, RegionState.of(pressure, peak, banked, permanent, cats, settings));
        }
        return new ExtinctionState(out, Map.copyOf(cats));
    }

    ExtinctionState rebuild(Settings settings) {
        Long2ObjectOpenHashMap<RegionState> out = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<RegionState> e : regions.long2ObjectEntrySet()) {
            put(out, e.getLongKey(), e.getValue().rebuild(categories, settings));
        }
        return new ExtinctionState(out, categories);
    }

    @Nullable
    RegionState atChunk(int chunkX, int chunkZ) {
        return regions.isEmpty() ? null : regions.get(key(chunkX >> 3, chunkZ >> 3));
    }

    static long key(int regionX, int regionZ) {
        return (long) regionX & 0xFFFFFFFFL | ((long) regionZ & 0xFFFFFFFFL) << 32;
    }

    private static Object2IntOpenHashMap<ResourceLocation> copy(@Nullable Object2IntMap<ResourceLocation> map) {
        return map == null ? new Object2IntOpenHashMap<>() : new Object2IntOpenHashMap<>(map);
    }

    private static void put(Long2ObjectOpenHashMap<RegionState> map, long key, @Nullable RegionState region) {
        if (region != null) map.put(key, region);
    }
}
