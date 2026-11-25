package caffeinated_mobs;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.MobSpawnSettingsBuilder;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

// runs in MODIFY: neo removes after it adds, so remove+add cant reweight
record SetSpawnsBiomeModifier(HolderSet<Biome> biomes, List<SpawnerData> spawners, Optional<MobCategory> category)
        implements BiomeModifier {
    static final MapCodec<SetSpawnsBiomeModifier> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.LIST_CODEC.fieldOf("biomes").forGetter(SetSpawnsBiomeModifier::biomes),
            Codec.either(SpawnerData.CODEC.listOf(), SpawnerData.CODEC)
                    .xmap(e -> e.map(Function.identity(), List::of), Either::<List<SpawnerData>, SpawnerData>left)
                    .fieldOf("spawners").forGetter(SetSpawnsBiomeModifier::spawners),
            MobCategory.CODEC.optionalFieldOf("category").forGetter(SetSpawnsBiomeModifier::category)
    ).apply(i, SetSpawnsBiomeModifier::new));

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.MODIFY || !biomes.contains(biome)) return;
        MobSpawnSettingsBuilder spawns = builder.getMobSpawnSettings();
        for (SpawnerData spawner : spawners) {
            spawns.getSpawner(spawner.type.getCategory()).removeIf(s -> s.type == spawner.type);
            spawns.getSpawner(category(spawner)).removeIf(s -> s.type == spawner.type);
        }
        for (SpawnerData spawner : spawners) spawns.addSpawn(category(spawner), spawner);
    }

    private MobCategory category(SpawnerData spawner) {
        return category.orElse(spawner.type.getCategory());
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return CaffeinatedMobs.SET_SPAWNS.get();
    }
}
