package caffeinated_mobs.extinction;

import caffeinated_mobs.CaffeinatedMobs;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class Pools {
    private static final String PREFIX = "extinction_pools/";
    private static final Map<EntityType<?>, ResourceLocation> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("deprecation")
    public static ResourceLocation of(EntityType<?> type) {
        return CACHE.computeIfAbsent(type, t -> t.builtInRegistryHolder().tags()
                .map(TagKey::location).filter(Pools::isTagPool).findFirst()
                .orElseGet(() -> BuiltInRegistries.ENTITY_TYPE.getKey(t)));
    }

    @Nullable
    static MobCategory category(ResourceLocation pool) {
        if (isTagPool(pool)) {
            return BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, pool))
                    .flatMap(set -> set.stream().findFirst()).map(h -> h.value().getCategory()).orElse(null);
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(pool).map(EntityType::getCategory)
                .filter(c -> c != MobCategory.MISC).orElse(null);
    }

    static Stream<ResourceLocation> all() {
        return Stream.concat(
                BuiltInRegistries.ENTITY_TYPE.getTagNames().map(TagKey::location).filter(Pools::isTagPool),
                BuiltInRegistries.ENTITY_TYPE.stream().filter(t -> t.getCategory() != MobCategory.MISC).map(Pools::of)).distinct();
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static boolean isTagPool(ResourceLocation id) {
        return id.getNamespace().equals(CaffeinatedMobs.MOD_ID) && id.getPath().startsWith(PREFIX);
    }

    private Pools() {}
}
