package com.k33bz.sanctuary.siege;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.SurvivalLogic;

import java.util.EnumSet;

/**
 * Melee for rabid wildlife. Most passive mobs have no ATTACK_DAMAGE attribute, so vanilla's
 * {@code MeleeAttackGoal} can't drive them — this goal chases the target and deals config-driven
 * damage directly. Gated on the rabid entity tag, so the sanctuary revert (which strips the tag)
 * also pacifies the animal without needing to detach goals.
 */
public class RabidAttackGoal extends Goal {
    /** Marks a rabid animal; persists in NBT. Public so bake/revert/goal all share it. */
    public static final String RABID_TAG = "sanctuary_rabid";

    private final PathfinderMob mob;
    private int attackCooldown;

    public RabidAttackGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.entityTags().contains(RABID_TAG)) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0f, 30.0f);
        if (mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(target, 1.25);
        }
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }
        double reach = mob.getBbWidth() * 1.2 + target.getBbWidth() + 0.6;
        if (mob.distanceToSqr(target) > reach * reach || !mob.getSensing().hasLineOfSight(target)) {
            return;
        }
        mob.swing(InteractionHand.MAIN_HAND);
        target.hurtServer(level, level.damageSources().mobAttack(mob), damage());
        level.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE,
                0.8f, 0.7f);
        attackCooldown = adjustedTickDelay(20);
    }

    /** Base damage scaled by the zone multiplier this animal spawned with (recovered from its health buff). */
    private float damage() {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null) {
            return 2.0f;
        }
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        double mult = 1.0;
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null && ms.healthPerBlock > 0) {
            AttributeModifier mod = hp.getModifier(com.k33bz.sanctuary.MobDifficulty.HEALTH_ID);
            if (mod != null) {
                double beyond = mod.amount() / ms.healthPerBlock;
                mult = SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier,
                        ms.damageCurveExponent);
            }
        }
        return (float) (ms.rabidBaseDamage * mult);
    }
}
