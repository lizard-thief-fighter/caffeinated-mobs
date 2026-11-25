package caffeinated_mobs.mixin;

import caffeinated_mobs.extinction.Extinction;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalMobCapCalculator.class)
abstract class LocalMobCapCalculatorMixin {
    @Shadow
    @Final
    private ChunkMap chunkMap;

    @WrapOperation(method = "canSpawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LocalMobCapCalculator$MobCounts;canSpawn(Lnet/minecraft/world/entity/MobCategory;)Z"))
    private boolean caffeinated_mobs$extinctionCap(LocalMobCapCalculator.MobCounts counts, MobCategory category,
                                                   Operation<Boolean> original, @Local(argsOnly = true) ChunkPos chunk) {
        float factor = Extinction.capFactor(chunkMap.level, chunk, category);
        if (factor >= 1f) return original.call(counts, category);
        return counts.counts.getOrDefault(category, 0) < category.getMaxInstancesPerChunk() * factor;
    }
}
