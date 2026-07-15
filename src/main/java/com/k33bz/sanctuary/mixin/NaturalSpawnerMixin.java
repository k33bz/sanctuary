package com.k33bz.sanctuary.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

/**
 * Sanctuaries actually shelter: natural hostile spawns are suppressed inside active safe zones.
 * Only NATURAL spawning is touched — spawner blocks, spawn eggs, and commands work as always,
 * and mobs that wander in are handled by the sanctuary-revert pass instead.
 */
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {

    @Inject(method = "isValidPositionForMob", at = @At("HEAD"), cancellable = true)
    private static void sanctuary$suppressHostileSpawns(ServerLevel level, Mob mob, double distance,
                                                        CallbackInfoReturnable<Boolean> cir) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !(mob instanceof Enemy) || !cfg.isScalingDimension(level)) {
            return;
        }
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, mob.getX(), mob.getZ());
        // Still Night: thin out wild hostile spawns for a calmer night.
        if (cfg.nightEvents.enabled && beyond > 0.0
                && com.k33bz.sanctuary.event.NightEvents.ACTIVE == com.k33bz.sanctuary.event.NightEvent.STILL_NIGHT
                && level.getRandom().nextFloat() < cfg.nightEvents.still_night.spawnCut) {
            cir.setReturnValue(false);
            return;
        }
        if (cfg.suppressHostileSpawnsInSanctuary && beyond <= 0.0) {
            cir.setReturnValue(false);
        }
    }
}
