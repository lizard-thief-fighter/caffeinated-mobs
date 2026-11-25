package caffeinated_mobs;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent.Operation;

final class Spawning {
    private static final TagKey<EntityType<?>> NO_BABIES = TagKey.create(Registries.ENTITY_TYPE, CaffeinatedMobs.id("no_babies"));
    private static final TagKey<Biome> CAVE_SLIMES = biomes("cave_slimes");
    private static final TagKey<Biome> SURFACE_BATS = biomes("surface_bats");
    private static final TagKey<Biome> RARE_AXOLOTLS = biomes("rare_axolotls");
    private static final int ANIMAL_MIN_LIGHT = 8;
    private static final int SURFACE_BAT_CHANCE = 8;
    private static final int RARE_AXOLOTL_CHANCE = 20;

    static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;

        if (mob.isBaby() && mob.getType().is(NO_BABIES)) {
            mob.setBaby(false);

            if (mob.getVehicle() instanceof Chicken) mob.stopRiding();
        }

        if (mob instanceof Hoglin hoglin && !event.getLevel().dimensionType().piglinSafe()) {
            hoglin.setImmuneToZombification(true);
        }
    }

    static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.getEntity().getType() != EntityType.HOGLIN) return;
        if (event.getSpawnType() != MobSpawnType.NATURAL && event.getSpawnType() != MobSpawnType.CHUNK_GENERATION) return;
        var level = event.getLevel();
        if (level.dimensionType().piglinSafe()) return;
        int x = (int) Math.floor(event.getX()), z = (int) Math.floor(event.getZ());
        // surface hoglins come from the creature list, keep them like animals
        if (event.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 2) {
            event.getEntity().setPersistenceRequired();
        }
    }

    static void registerPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(EntityType.SLIME, (type, level, reason, pos, random) -> level.getBiome(pos).is(CAVE_SLIMES)
                && level.getDifficulty() != Difficulty.PEACEFUL
                && Monster.isDarkEnoughToSpawn(level, pos, random)
                && Mob.checkMobSpawnRules(type, level, reason, pos, random), Operation.OR);

        event.register(EntityType.BAT, (type, level, reason, pos, random) -> level.getBiome(pos).is(SURFACE_BATS)
                && pos.getY() >= level.getSeaLevel()
                && level.getLevel().isNight()
                && level.getBrightness(LightLayer.BLOCK, pos) <= 4
                && random.nextInt(SURFACE_BAT_CHANCE) == 0, Operation.OR);

        event.register(EntityType.AXOLOTL, (type, level, reason, pos, random) -> !level.getBiome(pos).is(RARE_AXOLOTLS)
                || random.nextInt(RARE_AXOLOTL_CHANCE) == 0, Operation.AND);

        // vanilla has no camel rule. replaces nmls (we load after it)
        event.register(EntityType.CAMEL, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> {
                    var below = level.getBlockState(pos.below());
                    return (below.is(BlockTags.SAND) || below.is(BlockTags.ANIMALS_SPAWNABLE_ON))
                            && level.getRawBrightness(pos, 0) > ANIMAL_MIN_LIGHT;
                }, Operation.REPLACE);
    }

    private static TagKey<Biome> biomes(String name) {
        return TagKey.create(Registries.BIOME, CaffeinatedMobs.id("spawns/" + name));
    }

    private Spawning() {}
}
