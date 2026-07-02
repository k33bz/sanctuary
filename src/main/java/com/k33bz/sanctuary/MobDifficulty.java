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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import com.k33bz.sanctuary.siege.RabidAttackGoal;

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

    public static final Identifier HEALTH_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "wild_health");
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
        double zoneMult = beyond <= 0.0 ? 1.0
                : SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier);
        int zone = beyond <= 0.0 ? 0 : 1 + SurvivalLogic.mobTier(zoneMult - 1.0);
        long now = player.level().getGameTime();

        Integer prev = LAST_ZONE.get(id);
        if (prev == null) {
            // Fresh login: tell them where they woke up — but give the loading screen ~3s to clear
            // first, or the actionbar message plays to nobody.
            if (player.tickCount < 60) {
                return;
            }
            LAST_ZONE.put(id, zone);
            LAST_ZONE_MSG.put(id, now);
            player.sendOverlayMessage(currentZoneMessage(player, ms, zone, zoneMult));
            return;
        }
        LAST_ZONE.put(id, zone);
        if (prev == zone) {
            return;
        }
        long cooldown = (long) (ms.boundaryMessageCooldownSeconds * 20.0);
        Long lastMsg = LAST_ZONE_MSG.get(id);
        if (lastMsg != null && now - lastMsg < cooldown) {
            return; // zone updated silently; next change after the cooldown may speak
        }
        LAST_ZONE_MSG.put(id, now);
        player.sendOverlayMessage(zoneMessage(player, ms, prev, zone, zoneMult));
    }

    /** Forget a player's zone tracking (on disconnect), so the next login re-announces their zone. */
    public static void clearPlayer(java.util.UUID id) {
        LAST_ZONE.remove(id);
        LAST_ZONE_MSG.remove(id);
    }

    /** Skull glyph that Minecraft's font actually renders (emoji like U+1F480 show as boxes). */
    private static final String SKULL = "☠";

    /** Message color by how outmatched the player is (skull count 0-5). */
    private static final ChatFormatting[] THREAT_COLORS = {
            ChatFormatting.GREEN, ChatFormatting.GREEN, ChatFormatting.YELLOW,
            ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.DARK_RED
    };

    /**
     * 0-5 skulls: the zone's mob-damage multiplier measured against this player's level-derived
     * power. 3 skulls is an even fight; 5 is near-certain death; 0-1 is farmland.
     */
    private static int skulls(ServerPlayer player, SanctuaryConfig.MobScaling ms, double zoneMult) {
        double perMult = Math.max(1.0, ms.skullLevelsPerMultiplier);
        double playerPower = 1.0 + player.experienceLevel / perMult;
        double ratio = zoneMult / playerPower;
        return (int) Math.max(0, Math.min(5, Math.round(ratio * 3.0)));
    }

    private static Component zoneMessage(ServerPlayer player, SanctuaryConfig.MobScaling ms,
                                         int from, int to, double zoneMult) {
        if (to == 0) {
            return Component.literal("The sanctuary shelters you.").withStyle(ChatFormatting.GREEN);
        }
        String text;
        if (to > from) {
            text = to == 1 ? "Entering the wildlands " : "Entering " + TITLES[to - 1] + " wildlands ";
        } else {
            text = "The wilds grow calmer ";
        }
        return withSkulls(text, skulls(player, ms, zoneMult));
    }

    /** Login readout: where the player currently stands, same skull scale. */
    private static Component currentZoneMessage(ServerPlayer player, SanctuaryConfig.MobScaling ms,
                                                int zone, double zoneMult) {
        if (zone == 0) {
            return Component.literal("The sanctuary shelters you.").withStyle(ChatFormatting.GREEN);
        }
        String text = zone == 1 ? "You are in the wildlands " : "You are in " + TITLES[zone - 1] + " wildlands ";
        return withSkulls(text, skulls(player, ms, zoneMult));
    }

    private static Component withSkulls(String text, int filled) {
        ChatFormatting color = THREAT_COLORS[filled];
        MutableComponent msg = Component.literal(text).withStyle(color);
        if (filled > 0) {
            msg.append(Component.literal(SKULL.repeat(filled)).withStyle(color));
        }
        if (filled < 5) {
            msg.append(Component.literal(SKULL.repeat(5 - filled)).withStyle(ChatFormatting.DARK_GRAY));
        }
        return msg;
    }

    /**
     * Rabid wildlife — in Savage+ zones, animals roll a chance to turn on players. Buffed like
     * monsters (health/speed/follow), tier-named, and given hunt goals. Tamed animals and babies
     * are exempt. Idempotent per load; goals are transient and re-attached like door-breakers'.
     */
    public static void onAnimalLoad(net.minecraft.world.entity.animal.Animal animal, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        if (!ms.enabled || !ms.rabidEnabled) {
            return;
        }
        if (animal.entityTags().contains(RabidAttackGoal.RABID_TAG)) {
            attachRabidGoals(animal); // reloaded rabid animal: buffs persisted, goals didn't
            return;
        }
        AttributeInstance hp = animal.getAttribute(Attributes.MAX_HEALTH);
        if (hp == null || hp.getModifier(HEALTH_ID) != null) {
            return;
        }
        if (animal.isBaby()
                || (animal instanceof net.minecraft.world.entity.TamableAnimal tamable && tamable.isTame())
                || (animal instanceof net.minecraft.world.entity.animal.equine.AbstractHorse horse
                        && horse.isTamed())) {
            return;
        }
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, animal.getX(), animal.getZ());
        if (beyond <= 0.0) {
            return;
        }
        double damageMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier);
        int tier = SurvivalLogic.mobTier(damageMult - 1.0);
        if (tier < 2 || animal.getRandom().nextDouble() >= ms.rabidChance) {
            return; // calm below Savage, and only a fraction of Savage+ wildlife turns
        }

        double healthMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.healthPerBlock, ms.healthMaxMultiplier);
        double speedMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.speedPerBlock, ms.speedMaxMultiplier);
        double followMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.followPerBlock, ms.followMaxMultiplier);
        addMult(hp, HEALTH_ID, healthMult - 1.0);
        animal.setHealth(animal.getMaxHealth());
        addMult(animal.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_ID, speedMult - 1.0);
        addMult(animal.getAttribute(Attributes.FOLLOW_RANGE), FOLLOW_ID, followMult - 1.0);

        animal.addTag(RabidAttackGoal.RABID_TAG);
        attachRabidGoals(animal);

        animal.setCustomName(Component.literal(TITLES[tier] + " ").append(animal.getName().copy())
                .withStyle(COLORS[tier]));
        animal.setCustomNameVisible(false);
    }

    private static void attachRabidGoals(net.minecraft.world.entity.animal.Animal animal) {
        // Both goals gate on the rabid tag, so the sanctuary revert pacifies without goal surgery.
        animal.targetSelector.addGoal(1,
                new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(animal,
                        net.minecraft.world.entity.player.Player.class, true) {
                    @Override
                    public boolean canUse() {
                        return animal.entityTags().contains(RabidAttackGoal.RABID_TAG) && super.canUse();
                    }
                });
        animal.goalSelector.addGoal(1, new RabidAttackGoal(animal));
    }

    /** Attach the any-difficulty door-break goal to a mob carrying the door-breaker tag. */
    private static void attachDoorBreakGoalIfMarked(Monster mob) {
        if (mob instanceof Zombie && mob.entityTags().contains(DOOR_BREAKER_TAG)) {
            // Vanilla gates door-breaking behind Hard difficulty; wildlands hunters ignore that.
            // Still respects the mobGriefing gamerule (checked inside the goal).
            mob.goalSelector.addGoal(1, new BreakDoorGoal(mob, difficulty -> true));
            // And when the way through is blocked, smash the frame of a player-placed door.
            mob.goalSelector.addGoal(2, new com.k33bz.sanctuary.siege.SmashDoorFrameGoal(mob));
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
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(ms.particleRange));
        for (Mob mob : mobs) {
            int tier;
            boolean rabid = mob instanceof Animal && mob.entityTags().contains(RabidAttackGoal.RABID_TAG);
            if (rabid) {
                // Recover the tier from the health buff (animals have no attack attribute).
                AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
                AttributeModifier mod = hp == null ? null : hp.getModifier(HEALTH_ID);
                if (mod == null || ms.healthPerBlock <= 0) {
                    continue;
                }
                double beyond = mod.amount() / ms.healthPerBlock;
                tier = SurvivalLogic.mobTier(
                        SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier) - 1.0);
            } else if (mob instanceof Monster) {
                AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
                AttributeModifier mod = dmg == null ? null : dmg.getModifier(DAMAGE_ID);
                if (mod == null) {
                    continue;
                }
                tier = SurvivalLogic.mobTier(mod.amount());
            } else {
                continue;
            }
            // Anti-farming: a wild mob standing inside a sanctuary loses its buffs (and its
            // scaled XP), so dragging Nightmare mobs home isn't an XP printer.
            if (ms.revertInSanctuary
                    && Sanctuary.blocksBeyondNearestAnchor(cfg, mob.getX(), mob.getZ()) <= 0.0) {
                revertToVanilla(mob, ms, tier);
                continue;
            }
            if (tier <= 0) {
                continue;
            }
            level.sendParticles(particleFor(tier), mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                    tier + 1, 0.3, 0.4, 0.3, 0.01);
        }
    }

    /**
     * Strip a wild mob back to vanilla: attribute buffs removed, XP reward un-scaled (monsters;
     * the spawn distance is recovered from the damage modifier), rabid/door-breaker status
     * dropped, the mod's tier name cleared. A name a player gave it with a real name tag is
     * different text and is left alone.
     */
    private static void revertToVanilla(Mob mob, SanctuaryConfig.MobScaling ms, int tier) {
        // Un-scale a monster's XP reward using the multipliers it got at spawn.
        AttributeInstance dmgAttr = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        AttributeModifier dmgMod = dmgAttr == null ? null : dmgAttr.getModifier(DAMAGE_ID);
        if (dmgMod != null && ms.damagePerBlock > 0) {
            double beyond = dmgMod.amount() / ms.damagePerBlock;
            double xpMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.xpPerBlock, ms.xpMaxMultiplier);
            if (xpMult > 1.0) {
                mob.xpReward = (int) Math.max(0, Math.round(mob.xpReward / xpMult));
            }
        }

        for (Identifier id : new Identifier[]{HEALTH_ID, DAMAGE_ID, SPEED_ID, FOLLOW_ID}) {
            AttributeInstance inst;
            if (id == HEALTH_ID) {
                inst = mob.getAttribute(Attributes.MAX_HEALTH);
            } else if (id == DAMAGE_ID) {
                inst = mob.getAttribute(Attributes.ATTACK_DAMAGE);
            } else if (id == SPEED_ID) {
                inst = mob.getAttribute(Attributes.MOVEMENT_SPEED);
            } else {
                inst = mob.getAttribute(Attributes.FOLLOW_RANGE);
            }
            if (inst != null) {
                inst.removeModifier(id);
            }
        }
        mob.setHealth(Math.min(mob.getHealth(), mob.getMaxHealth()));
        mob.removeTag(DOOR_BREAKER_TAG); // no more sieging either (goal dies with next reload)
        mob.removeTag(RabidAttackGoal.RABID_TAG); // rabid goals gate on the tag, so this pacifies too
        if (mob.getTarget() instanceof net.minecraft.world.entity.player.Player) {
            mob.setTarget(null);
        }

        // Clear OUR tier name only — an actual player-applied name tag is different text and survives.
        if (tier > 0 && mob.hasCustomName()) {
            String expected = TITLES[tier] + " " + mob.getType().getDescription().getString();
            if (expected.equals(mob.getCustomName().getString())) {
                mob.setCustomName(null);
            }
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
