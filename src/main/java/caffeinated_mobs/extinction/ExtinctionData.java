package caffeinated_mobs.extinction;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.saveddata.SavedData;

final class ExtinctionData extends SavedData {
    static final String NAME = "caffeinated_mobs_extinction";
    private static final long DAY = 24000L;

    // worker replaces state, main thread only reads it
    volatile ExtinctionState state = ExtinctionState.EMPTY;
    private volatile boolean dirty;
    private long day = -1;
    private Long2ObjectOpenHashMap<Object2IntOpenHashMap<ResourceLocation>> kills = new Long2ObjectOpenHashMap<>();
    private Map<ResourceLocation, MobCategory> killCategories = new HashMap<>();

    static SavedData.Factory<ExtinctionData> factory() {
        return new SavedData.Factory<>(ExtinctionData::new, ExtinctionData::load);
    }

    void recordKill(int blockX, int blockZ, ResourceLocation pool, MobCategory category, int origin, int adjacent) {
        int rx = blockX >> 7, rz = blockZ >> 7;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                kills.computeIfAbsent(ExtinctionState.key(rx + dx, rz + dz), k -> new Object2IntOpenHashMap<>())
                        .addTo(pool, dx == 0 && dz == 0 ? origin : adjacent);
            }
        }
        killCategories.putIfAbsent(pool, category);
        dirty = true;
    }

    long day() {
        return day;
    }

    Object2IntMap<ResourceLocation> pendingKills(long key) {
        Object2IntMap<ResourceLocation> map = kills.get(key);
        return map == null ? new Object2IntOpenHashMap<>() : map;
    }

    void tick(ServerLevel level) {
        // better days just moves daytime, nothing else needed
        long now = Math.floorDiv(level.getDayTime(), DAY);
        if (day < 0) day = now;
        if (now == day) return;
        long oldDay = day;
        day = now;
        endDay(oldDay, now, false);
    }

    void endDay(long oldDay, long newDay, boolean force) {
        var periodKills = kills;
        var periodCategories = killCategories;
        kills = new Long2ObjectOpenHashMap<>();
        killCategories = new HashMap<>();
        Settings settings = Settings.current();
        update(s -> s.rollover(periodKills, periodCategories, oldDay, newDay, force, settings));
    }

    void update(UnaryOperator<ExtinctionState> change) {
        Extinction.WORKER.execute(() -> {
            state = change.apply(state);
            dirty = true;
        });
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ExtinctionState s = state;
        tag.putLong("day", day);
        CompoundTag categories = new CompoundTag();
        s.categories().forEach((pool, category) -> categories.putString(pool.toString(), category.getName()));
        killCategories.forEach((pool, category) -> categories.putString(pool.toString(), category.getName()));
        tag.put("categories", categories);
        ListTag regions = new ListTag();
        for (Long2ObjectMap.Entry<RegionState> e : s.regions().long2ObjectEntrySet()) {
            CompoundTag region = new CompoundTag();
            region.putLong("key", e.getLongKey());
            region.put("pressure", writePressure(e.getValue().pressure));
            region.put("peak", writePressure(e.getValue().peak));
            region.put("banked", writePressure(e.getValue().banked));
            ListTag permanent = new ListTag();
            e.getValue().permanent.forEach(pool -> permanent.add(StringTag.valueOf(pool.toString())));
            region.put("permanent", permanent);
            regions.add(region);
        }
        tag.put("regions", regions);
        ListTag pending = new ListTag();
        for (Long2ObjectMap.Entry<Object2IntOpenHashMap<ResourceLocation>> e : kills.long2ObjectEntrySet()) {
            CompoundTag region = new CompoundTag();
            region.putLong("key", e.getLongKey());
            region.put("pressure", writePressure(e.getValue()));
            pending.add(region);
        }
        tag.put("pending", pending);
        return tag;
    }

    private static ExtinctionData load(CompoundTag tag, HolderLookup.Provider registries) {
        ExtinctionData data = new ExtinctionData();
        data.day = tag.getLong("day");
        Map<ResourceLocation, MobCategory> categories = new HashMap<>();
        CompoundTag cats = tag.getCompound("categories");
        for (String pool : cats.getAllKeys()) {
            MobCategory category = categoryByName(cats.getString(pool));
            ResourceLocation id = ResourceLocation.tryParse(pool);
            if (category != null && id != null) categories.put(id, category);
        }
        Settings settings = Settings.current();
        Long2ObjectOpenHashMap<RegionState> regions = new Long2ObjectOpenHashMap<>();
        for (Tag t : tag.getList("regions", Tag.TAG_COMPOUND)) {
            CompoundTag region = (CompoundTag) t;
            Set<ResourceLocation> permanent = new HashSet<>();
            for (Tag p : region.getList("permanent", Tag.TAG_STRING)) {
                ResourceLocation id = ResourceLocation.tryParse(p.getAsString());
                if (id != null) permanent.add(id);
            }
            var pressure = readPressure(region.getCompound("pressure"));

            var peak = region.contains("peak") ? readPressure(region.getCompound("peak")) : pressure;
            var banked = readPressure(region.getCompound("banked"));
            RegionState state = RegionState.of(pressure, peak, banked, permanent, categories, settings);
            if (state != null) regions.put(region.getLong("key"), state);
        }
        data.state = new ExtinctionState(regions, Map.copyOf(categories));
        for (Tag t : tag.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag region = (CompoundTag) t;
            data.kills.put(region.getLong("key"), readPressure(region.getCompound("pressure")));
        }
        data.killCategories.putAll(categories);
        return data;
    }

    private static MobCategory categoryByName(String name) {
        for (MobCategory category : MobCategory.values()) if (category.getName().equals(name)) return category;
        return null;
    }

    private static CompoundTag writePressure(Object2IntMap<ResourceLocation> pressure) {
        CompoundTag tag = new CompoundTag();
        pressure.forEach((pool, n) -> tag.putInt(pool.toString(), n));
        return tag;
    }

    private static Object2IntOpenHashMap<ResourceLocation> readPressure(CompoundTag tag) {
        Object2IntOpenHashMap<ResourceLocation> pressure = new Object2IntOpenHashMap<>();
        for (String pool : tag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(pool);
            if (id != null && tag.getInt(pool) > 0) pressure.put(id, tag.getInt(pool));
        }
        return pressure;
    }
}
