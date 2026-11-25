package caffeinated_mobs;

import com.mojang.serialization.MapCodec;
import caffeinated_mobs.extinction.Extinction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ambient.Bat;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

@Mod(CaffeinatedMobs.MOD_ID)
public final class CaffeinatedMobs {
    public static final String MOD_ID = "caffeinated_mobs";

    private static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MOD_ID);
    static final DeferredHolder<MapCodec<? extends BiomeModifier>, MapCodec<SetSpawnsBiomeModifier>> SET_SPAWNS =
            BIOME_MODIFIERS.register("set_spawns", () -> SetSpawnsBiomeModifier.CODEC);

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);

    // never-spawning spawn list filler. not MISC (spawner data swaps those for pigs), noSummon gets it rejected
    public static final DeferredHolder<EntityType<?>, EntityType<Bat>> VACANCY = ENTITY_TYPES.register("vacancy",
            () -> EntityType.Builder.of(Bat::new, MobCategory.AMBIENT).noSummon().sized(0.1F, 0.1F)
                    .clientTrackingRange(0).build("vacancy"));

    public CaffeinatedMobs(IEventBus modBus, ModContainer container) {
        BIOME_MODIFIERS.register(modBus);
        ENTITY_TYPES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent e) -> e.put(VACANCY.get(), Bat.createAttributes().build()));
        if (FMLEnvironment.dist.isClient()) modBus.addListener(ClientSetup::registerRenderers);
        Extinction.register(NeoForge.EVENT_BUS);
        modBus.addListener(Spawning::registerPlacements);
        NeoForge.EVENT_BUS.addListener(Spawning::onJoinLevel);
        NeoForge.EVENT_BUS.addListener(Spawning::onFinalizeSpawn);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
