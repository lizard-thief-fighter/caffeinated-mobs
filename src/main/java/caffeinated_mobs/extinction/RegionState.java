package caffeinated_mobs.extinction;

import it.unimi.dsi.fastutil.objects.Object2ByteMap;
import it.unimi.dsi.fastutil.objects.Object2ByteMaps;
import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobCategory;

final class RegionState {
    final Object2IntMap<ResourceLocation> pressure;
    final Object2IntMap<ResourceLocation> peak;
    final Object2IntMap<ResourceLocation> banked;
    final Set<ResourceLocation> permanent;
    final Object2ByteMap<ResourceLocation> levels;

    @Nullable
    final float[] capFactor;

    private RegionState(Object2IntMap<ResourceLocation> pressure, Object2IntMap<ResourceLocation> peak, Object2IntMap<ResourceLocation> banked,
                        Set<ResourceLocation> permanent, Object2ByteMap<ResourceLocation> levels, @Nullable float[] capFactor) {
        this.pressure = pressure;
        this.peak = peak;
        this.banked = banked;
        this.permanent = permanent;
        this.levels = levels;
        this.capFactor = capFactor;
    }

    @Nullable
    static RegionState of(Object2IntMap<ResourceLocation> pressure, Object2IntMap<ResourceLocation> peak,
                          Object2IntMap<ResourceLocation> banked, Set<ResourceLocation> permanent,
                          Map<ResourceLocation, MobCategory> categories, Settings settings) {
        if (pressure.isEmpty() && peak.isEmpty() && banked.isEmpty() && permanent.isEmpty()) return null;
        Object2ByteOpenHashMap<ResourceLocation> levels = new Object2ByteOpenHashMap<>();
        Set<ResourceLocation> pools = new HashSet<>(peak.keySet());
        pools.addAll(permanent);
        float[] cut = null;
        for (ResourceLocation pool : pools) {
            Settings.PoolRule rule = settings.pool(pool);
            byte level = permanent.contains(pool) ? 3 : rule.level(peak.getInt(pool));
            if (level == 0) continue;
            levels.put(pool, level);
            if (cut == null) cut = new float[MobCategory.values().length];
            cut[categories.getOrDefault(pool, MobCategory.MONSTER).ordinal()] += rule.cut(level);
        }
        float[] factor = null;
        if (cut != null) {
            factor = new float[cut.length];
            for (int i = 0; i < cut.length; i++) factor[i] = Math.max(settings.floor, 1 - cut[i]);
        }
        return new RegionState(pressure, peak, banked, Set.copyOf(permanent),
                levels.isEmpty() ? Object2ByteMaps.emptyMap() : levels, factor);
    }

    @Nullable
    RegionState rebuild(Map<ResourceLocation, MobCategory> categories, Settings settings) {
        return of(pressure, peak, banked, permanent, categories, settings);
    }
}
