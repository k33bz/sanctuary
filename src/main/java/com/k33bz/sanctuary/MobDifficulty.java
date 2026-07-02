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
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;

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
    private static final Identifier FOLLOW_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "wild_follow");

    /** Persistent entity tag marking a door-breaker (goals are transient, this survives reloads). */
    private static final String DOOR_BREAKER_TAG = "sanctuary_door_breaker";

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
        if (hp == null) {
            return;
        }
        if (hp.getModifier(HEALTH_ID) != null) {
            // Already baked (permanent modifiers survived the reload) — but goals are transient,
            // so a marked door-breaker needs its goal re-attached on every load.
            attachDoorBreakGoalIfMarked(mob);
            return;
        }
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, mob.getX(), mob.getZ());
        if (beyond <= 0.0) {
            return; // spawned inside a safe zone: leave it vanilla-strength (and unmarked)
        }

        double healthMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.healthPerBlock, ms.healthMaxMultiplier);
        double damageMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier);
        double speedMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.speedPerBlock, ms.speedMaxMultiplier);
        double followMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.followPerBlock, ms.followMaxMultiplier);

        addMult(hp, HEALTH_ID, healthMult - 1.0);
        mob.setHealth(mob.getMaxHealth());
        addMult(mob.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE_ID, damageMult - 1.0);
        addMult(mob.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_ID, speedMult - 1.0);
        // Hunters: deep mobs notice players from much farther away.
        addMult(mob.getAttribute(Attributes.FOLLOW_RANGE), FOLLOW_ID, followMult - 1.0);

        // Door-breakers: past the start distance, zombies roll a distance-scaled chance to smash
        // wooden doors on any difficulty. The tag persists; the goal is re-attached each load.
        if (mob instanceof Zombie) {
            double chance = (beyond - ms.doorBreakStartBlocks) * ms.doorBreakChancePerBlock;
            if (chance > 0 && mob.getRandom().nextDouble() < Math.min(1.0, chance)) {
                mob.addTag(DOOR_BREAKER_TAG);
                attachDoorBreakGoalIfMarked(mob);
            }
        }

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

    // --- threat-zone boundary messages ---

    /** Last known threat zone per player: 0 = sanctuary, 1 = wild (unnamed), 2..5 = named tiers. */
    private static final java.util.Map<java.util.UUID, Integer> LAST_ZONE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Game time of the last boundary message per player (rate limit). */
    private static final java.util.Map<java.util.UUID, Long> LAST_ZONE_MSG = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Show an actionbar message when the player crosses into a different threat zone. Zone changes are
     * always tracked, but messages are rate-limited so bouncing on a boundary can't spam.
     */
    public static void tickBoundary(ServerPlayer player, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        java.util.UUID id = player.getUUID();
        if (!ms.enabled || !ms.boundaryMessages) {
            return;
        }
        if (!cfg.isScalingDimension(player.level())) {
            LAST_ZONE.remove(id); // re-baseline silently when they come back from the Nether/End
            return;
        }
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, player.getX(), player.getZ());
        int zone = beyond <= 0.0 ? 0
                : 1 + SurvivalLogic.mobTier(
                        SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier) - 1.0);
        Integer prev = LAST_ZONE.put(id, zone);
        if (prev == null || prev == zone) {
            return; // first sighting (silent baseline) or no change
        }
        long now = player.level().getGameTime();
        long cooldown = (long) (ms.boundaryMessageCooldownSeconds * 20.0);
        Long lastMsg = LAST_ZONE_MSG.get(id);
        if (lastMsg != null && now - lastMsg < cooldown) {
            return; // zone updated silently; next change after the cooldown may speak
        }
        LAST_ZONE_MSG.put(id, now);
        player.sendOverlayMessage(zoneMessage(prev, zone));
    }

    private static Component zoneMessage(int from, int to) {
        if (to == 0) {
            return Component.literal("The sanctuary shelters you.").withStyle(ChatFormatting.GREEN);
        }
        if (to > from) {
            if (to == 1) {
                return Component.literal("You leave the sanctuary's shelter.").withStyle(ChatFormatting.YELLOW);
            }
            return Component.literal("You enter " + TITLES[to - 1] + " wildlands.").withStyle(COLORS[to - 1]);
        }
        return Component.literal("The wilds grow calmer.").withStyle(ChatFormatting.GRAY);
    }

    /** Attach the any-difficulty door-break goal to a mob carrying the door-breaker tag. */
    private static void attachDoorBreakGoalIfMarked(Monster mob) {
        if (mob instanceof Zombie && mob.entityTags().contains(DOOR_BREAKER_TAG)) {
            // Vanilla gates door-breaking behind Hard difficulty; wildlands hunters ignore that.
            // Still respects the mobGriefing gamerule (checked inside the goal).
            mob.goalSelector.addGoal(1, new BreakDoorGoal(mob, difficulty -> true));
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
