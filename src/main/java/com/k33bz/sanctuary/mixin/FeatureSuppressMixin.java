package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.rift.RiftWorldgen;

/**
 * Retires listed worldgen FEATURES inside the gathering world: monster rooms, by default.
 *
 * <p>Dungeons are not structures, so {@link StructureSuppressMixin} never sees them: they are a placed
 * feature ({@code minecraft:monster_room}) that biomes decorate with, and they need this separate hook.
 * Matching is on the feature TYPE id, so the single default entry covers both the shallow and the deep
 * dungeon placements without naming either.
 *
 * <p>Scoped by {@code riftSuppressedFeatures}, which is empty-checked before the dimension lookup so every
 * other world pays one null/empty test per placement. Ores, caves and vegetation are untouched, since a
 * gathering world with no ore would be pointless.
 *
 * <p>26.3 folded {@code ConfiguredFeature} into {@code Feature}, which became an interface with an abstract
 * {@code place}, so there is no concrete method left on the feature to hook. {@link PlacedFeature#place} is
 * the one concrete entry every biome decoration goes through, and it carries the feature as a holder.
 */
@Mixin(PlacedFeature.class)
public class FeatureSuppressMixin {

    @Inject(method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void sanctuary$suppressFeature(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
                                           BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        PlacedFeature self = (PlacedFeature) (Object) this;
        if (RiftWorldgen.suppressFeature(Sanctuary.CONFIG, level, self.feature().value())) {
            cir.setReturnValue(false);
        }
    }
}
