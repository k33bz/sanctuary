package com.k33bz.sanctuary;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;

import java.util.List;

/**
 * System 7 — spawn-based wild-mob difficulty. When a hostile spawns, it is buffed by its distance from
 * the nearest sanctuary anchor (health/damage/speed), so the wildlands are lethal and anchored regions
 * are calm. The buff is baked in as permanent attribute modifiers (persist in the mob's NBT), so a
 * wildlands monster stays strong even if it chases a player into a safe zone.
 */
public final class MobDifficulty {
    private MobDifficulty() {
    }

    private static final Identifier HEALTH_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "wild_health");
    private static final Identifier DAMAGE_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "wild_damage");
    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "wild_speed");

    private static final String[] TITLES = {"", "Feral", "Savage", "Ferocious", "Nightmare"};
    private static final ChatFormatting[] COLORS = {
            ChatFormatting.GRAY, ChatFormatting.YELLOW, ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.DARK_RED
    };

    /** Buff a freshly-loaded hostile by its spawn distance from the nearest anchor. Idempotent. */
    public static void onSpawn(Monster mob, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        if (!ms.enabled) {
            return;
        }
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (hp == null || hp.getModifier(HEALTH_ID) != null) {
            return; // no health attribute, or already baked (permanent modifier survived the reload)
        }
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, mob.getX(), mob.getZ());
        if (beyond <= 0.0) {
            return; // spawned inside a safe zone: leave it vanilla-strength (and unmarked)
        }

        double healthMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.healthPerBlock, ms.healthMaxMultiplier);
        double damageMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier);
        double speedMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.speedPerBlock, ms.speedMaxMultiplier);

        addMult(hp, HEALTH_ID, healthMult - 1.0);
        mob.setHealth(mob.getMaxHealth());
        addMult(mob.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE_ID, damageMult - 1.0);
        addMult(mob.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_ID, speedMult - 1.0);

        // Deeper mobs drop proportionally more XP — the payoff for braving the death zone.
        double xpMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.xpPerBlock, ms.xpMaxMultiplier);
        mob.xpReward = (int) Math.round(mob.xpReward * xpMult);

        int tier = SurvivalLogic.mobTier(damageMult - 1.0);
        if (tier > 0) {
            MutableComponent name = Component.literal(TITLES[tier] + " ").append(mob.getName().copy())
                    .withStyle(COLORS[tier]);
            mob.setCustomName(name);
            // Not always-visible: the name shows when a player looks at the mob, keeping the world uncluttered.
            mob.setCustomNameVisible(false);
        }
    }

    private static void addMult(AttributeInstance inst, Identifier id, double amount) {
        if (inst == null || amount <= 0.0) {
            return;
        }
        inst.addOrReplacePermanentModifier(
                new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    /** Emit a threat aura around buffed hostiles near one player. Call on an interval. */
    public static void tickParticles(ServerLevel level, ServerPlayer player, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        if (!ms.enabled) {
            return;
        }
        List<Monster> mobs = level.getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(ms.particleRange));
        for (Monster mob : mobs) {
            AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg == null) {
                continue;
            }
            AttributeModifier mod = dmg.getModifier(DAMAGE_ID);
            if (mod == null) {
                continue;
            }
            int tier = SurvivalLogic.mobTier(mod.amount());
            if (tier <= 0) {
                continue;
            }
            level.sendParticles(particleFor(tier), mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                    tier + 1, 0.3, 0.4, 0.3, 0.01);
        }
    }

    private static ParticleOptions particleFor(int tier) {
        return switch (tier) {
            case 1 -> ParticleTypes.SMOKE;
            case 2 -> ParticleTypes.ANGRY_VILLAGER;
            case 3 -> ParticleTypes.FLAME;
            default -> ParticleTypes.SOUL_FIRE_FLAME;
        };
    }
}
