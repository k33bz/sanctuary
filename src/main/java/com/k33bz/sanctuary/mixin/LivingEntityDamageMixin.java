package com.k33bz.sanctuary.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.k33bz.sanctuary.SurvivalLogic;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

/**
 * Adjusts incoming damage to players on the server damage path.
 * Order matters: world-danger scaling (up) is applied before level mitigation (down),
 * so high-level players can still be out-paced by a sufficiently dangerous world.
 */
@Mixin(LivingEntity.class)
public class LivingEntityDamageMixin {

    @ModifyVariable(
            method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            argsOnly = true
    )
    private float sanctuary$adjustIncomingDamage(float amount, ServerLevel serverLevel,
                                                  DamageSource source, float originalAmount) {
        // Only players are affected. World-danger scaling only applies to damage from a living
        // attacker (mobs/players), never environmental damage (fall, drowning, lava, cactus, ...).
        if (!((Object) this instanceof Player player)) {
            return amount;
        }
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null) {
            return amount;
        }
        // Any incoming damage marks the player in-combat (gates shield regen).
        Sanctuary.markCombat(player.getUUID(), serverLevel.getGameTime());

        // Danger scaling is distance-from-anchor based, so it only applies in scaling dimensions
        // (default: Overworld). The Nether/End keep vanilla damage.
        if (!cfg.danger.enabled || !cfg.isScalingDimension(serverLevel) || !hasLivingAttacker(source)) {
            return amount;
        }

        float worldMult = SurvivalLogic.worldDangerMultiplier(
                serverLevel.getDifficulty().getId(),
                com.k33bz.sanctuary.DangerClock.ageTicks(),
                Sanctuary.blocksBeyondNearestAnchor(cfg, player.getX(), player.getZ()),
                cfg.danger
        );

        // Scale damage up here at HEAD; vanilla armor reduction (from the level-granted armor points)
        // then applies afterward in the same method — preserving "scale up, then reduce".
        return amount * worldMult;
    }

    private static boolean hasLivingAttacker(DamageSource source) {
        // Players are excluded: the world-danger multiplier is about the WORLD getting harder,
        // and letting it amplify PvP would quietly inflate player damage with world age.
        Entity attacker = source.getEntity();
        return attacker instanceof LivingEntity && !(attacker instanceof Player);
    }
}
