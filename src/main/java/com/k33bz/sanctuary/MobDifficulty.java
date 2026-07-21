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
    /** The highest scaling tier ("Nightmare"). Wild Essence's non-Warden drop keys off this. */
    public static final int MAX_TIER = 4;
    private static final ChatFormatting[] COLORS = {
            ChatFormatting.GRAY, ChatFormatting.YELLOW, ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.DARK_RED
    };

    /** Buff a freshly-loaded hostile by its spawn distance from the nearest anchor. Idempotent. */
    public static void onSpawn(Mob mob, SanctuaryConfig cfg) {
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
        // Fuzzy zone edges: this individual rolled slightly stronger or weaker than its distance
        // says, so tier boundaries are bands, not lines. All of its stats share the one roll.
        beyond = SurvivalLogic.fuzzedBeyond(beyond, mob.getRandom().nextGaussian(), ms.edgeFuzz);
        // Blood Moon: amplify the distance term so this wild mob's health/damage/speed/xp/tier all scale up
        // together (existing caps still clamp). Baked as permanent modifiers -> it stays dangerous after dawn.
        beyond *= com.k33bz.sanctuary.event.NightEvents.spawnPowerFactorAt(mob.level(), mob.getX(), mob.getY(), mob.getZ());

        double healthMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.healthPerBlock, ms.healthMaxMultiplier);
        double damageMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier,
                ms.damageCurveExponent);
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
        double xpMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.xpPerBlock, ms.xpMaxMultiplier,
                ms.damageCurveExponent);
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
                : SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier,
                        ms.damageCurveExponent);
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
        // Feral-egg bloodline: a destined bird turns at its hatched tier the moment it loads as
        // an adult — anywhere, no zone or chance roll (the gamble already happened at hatch).
        // The sanctuary revert still pacifies it while it stands inside a safe zone; the destiny
        // tag survives that, so the wilds wake the bloodline again on the next load.
        int destiny = FeralEgg.destinyOf(animal);
        if (destiny >= 2) {
            double beyond = SurvivalLogic.beyondFromDamageBonus(FeralEgg.representativeDamageBonus(destiny),
                    ms.damagePerBlock, ms.damageCurveExponent);
            turnRabid(animal, ms, hp, beyond, destiny);
            return;
        }
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, animal.getX(), animal.getZ());
        if (beyond <= 0.0) {
            return;
        }
        beyond = SurvivalLogic.fuzzedBeyond(beyond, animal.getRandom().nextGaussian(), ms.edgeFuzz);
        double damageMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock, ms.damageMaxMultiplier,
                ms.damageCurveExponent);
        int tier = SurvivalLogic.mobTier(damageMult - 1.0);
        if (tier < 2 || animal.getRandom().nextDouble() >= ms.rabidChance) {
            return; // calm below Savage, and only a fraction of Savage+ wildlife turns
        }
        turnRabid(animal, ms, hp, beyond, tier);
    }

    /** Buff, tag, arm, and title a rabid animal for the given distance-equivalent and tier. */
    private static void turnRabid(net.minecraft.world.entity.animal.Animal animal,
                                  SanctuaryConfig.MobScaling ms, AttributeInstance hp,
                                  double beyond, int tier) {
        double healthMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.healthPerBlock, ms.healthMaxMultiplier);
        double speedMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.speedPerBlock, ms.speedMaxMultiplier);
        double followMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.followPerBlock, ms.followMaxMultiplier);
        addMult(hp, HEALTH_ID, healthMult - 1.0);
        animal.setHealth(animal.getMaxHealth());
        addMult(animal.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_ID, speedMult - 1.0);
        addMult(animal.getAttribute(Attributes.FOLLOW_RANGE), FOLLOW_ID, followMult - 1.0);

        animal.addTag(RabidAttackGoal.RABID_TAG);
        attachRabidGoals(animal);

        // Animals all read "Feral <name>" — the tier speaks through the color alone, so a market
        // buyer sizing up a hen learns nothing a colorblind glance wouldn't (monsters keep their
        // tier titles; you're meant to KNOW a Nightmare Zombie is coming).
        animal.setCustomName(Component.literal("Feral ").append(animal.getName().copy())
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

    /** Human name for a tier (1..4); "any" for 0 and below. */
    public static String tierName(int tier) {
        return tier > 0 && tier < TITLES.length ? TITLES[tier] : "any";
    }

    /** Display color for a tier (0..4), clamped. */
    public static ChatFormatting tierColor(int tier) {
        return COLORS[Math.max(0, Math.min(COLORS.length - 1, tier))];
    }

    /** Attach the any-difficulty door-break goal to a mob carrying the door-breaker tag. */
    private static void attachDoorBreakGoalIfMarked(Mob mob) {
        if (mob instanceof Zombie && mob.entityTags().contains(DOOR_BREAKER_TAG)) {
            // Vanilla gates door-breaking behind Hard difficulty; wildlands hunters ignore that.
            // Still respects the mobGriefing gamerule (checked inside the goal).
            mob.goalSelector.addGoal(1, new BreakDoorGoal(mob, difficulty -> true));
            // And when the way through is blocked, smash the frame of a player-placed door.
            mob.goalSelector.addGoal(2, new com.k33bz.sanctuary.siege.SmashDoorFrameGoal(mob));
        }
    }

    /** Tag marking a door-breaker recruited by The Hunt, so its temporary goal can be revoked at dawn
     *  without disturbing the distance-based door-breakers that earned the tag legitimately. */
    public static final String HUNT_DOOR_TAG = "sanctuary_hunt_door";

    /** The Hunt recruits a wild zombie as a door-breaker for the night (the goal re-attaches on reload). */
    public static void makeHuntDoorBreaker(Mob mob) {
        if (mob instanceof Zombie && !mob.entityTags().contains(DOOR_BREAKER_TAG)) {
            mob.addTag(DOOR_BREAKER_TAG);
            mob.addTag(HUNT_DOOR_TAG); // remember WE granted it, so cleanup is surgical
            attachDoorBreakGoalIfMarked(mob);
        }
    }

    /** Undo a Hunt-granted door-breaker at dawn; distance-based breakers (no HUNT_DOOR_TAG) are left alone. */
    public static void clearHuntDoorBreaker(Mob mob) {
        if (mob.entityTags().contains(HUNT_DOOR_TAG)) {
            mob.removeTag(HUNT_DOOR_TAG);
            mob.removeTag(DOOR_BREAKER_TAG); // the BreakDoorGoal goes inert on the next reload
        }
    }

    private static void addMult(AttributeInstance inst, Identifier id, double amount) {
        if (inst == null || amount <= 0.0) {
            return;
        }
        inst.addOrReplacePermanentModifier(
                new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    /** A feral egg's smolder: a single dim red mote, so a dropped egg reads as *wrong* up close. */
    private static final net.minecraft.core.particles.DustParticleOptions FERAL_EGG_MOTE =
            new net.minecraft.core.particles.DustParticleOptions(0xB02020, 0.6f);

    /** Emit a threat aura around buffed hostiles near one player. Call on an interval. */
    public static void tickParticles(ServerLevel level, ServerPlayer player, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        if (!ms.enabled) {
            return;
        }
        // Feral eggs smolder: one subtle red mote per interval over any feral egg lying nearby.
        if (ms.feralEggsEnabled) {
            for (net.minecraft.world.entity.item.ItemEntity egg : level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    player.getBoundingBox().inflate(ms.particleRange),
                    it -> FeralEgg.tierOf(it.getItem()) >= 2)) {
                level.sendParticles(FERAL_EGG_MOTE, egg.getX(), egg.getY() + 0.35, egg.getZ(),
                        1, 0.06, 0.10, 0.06, 0.0);
            }
        }
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(ms.particleRange));
        for (Mob mob : mobs) {
            int tier = tierOf(mob, ms);
            if (tier < 0) {
                continue;
            }
            // Anti-farming: a wild mob standing inside a sanctuary loses its buffs (and its
            // scaled XP), so dragging Nightmare mobs home isn't an XP printer.
            if (ms.revertInSanctuary
                    && Sanctuary.blocksBeyondNearestAnchor(cfg, mob.getX(), mob.getZ()) <= 0.0) {
                revertToVanilla(mob, ms, tier);
                continue;
            }
            if (tier == 0) {
                continue;
            }
            level.sendParticles(particleFor(tier), mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                    tier + 1, 0.3, 0.4, 0.3, 0.01);
        }
    }

    /**
     * A buffed mob's threat tier: from its damage buff (monsters) or recovered from its health
     * buff (rabid animals, which have no attack attribute). Returns −1 for unbuffed mobs.
     */
    public static int tierOf(Mob mob, SanctuaryConfig.MobScaling ms) {
        if (mob instanceof Animal && mob.entityTags().contains(RabidAttackGoal.RABID_TAG)) {
            AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
            AttributeModifier mod = hp == null ? null : hp.getModifier(HEALTH_ID);
            if (mod == null || ms.healthPerBlock <= 0) {
                return -1;
            }
            double beyond = mod.amount() / ms.healthPerBlock;
            return SurvivalLogic.mobTier(SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock,
                    ms.damageMaxMultiplier, ms.damageCurveExponent) - 1.0);
        }
        if (mob instanceof net.minecraft.world.entity.monster.Enemy) {
            AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
            AttributeModifier mod = dmg == null ? null : dmg.getModifier(DAMAGE_ID);
            if (mod != null) {
                return SurvivalLogic.mobTier(mod.amount());
            }
            // No attack attribute (ghasts etc.): recover the tier from the health buff.
            return tierFromHealthBuff(mob, ms);
        }
        return -1;
    }

    /** Tier recovered from the (linear, always-present) health buff; −1 if unbuffed. */
    private static int tierFromHealthBuff(Mob mob, SanctuaryConfig.MobScaling ms) {
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        AttributeModifier mod = hp == null ? null : hp.getModifier(HEALTH_ID);
        if (mod == null || ms.healthPerBlock <= 0) {
            return -1;
        }
        double beyond = mod.amount() / ms.healthPerBlock;
        return SurvivalLogic.mobTier(SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock,
                ms.damageMaxMultiplier, ms.damageCurveExponent) - 1.0);
    }

    /**
     * Damage multiplier for INDIRECT attacks (projectiles, explosions), which bypass the
     * ATTACK_DAMAGE attribute entirely. Prefer the exact baked damage modifier; for mobs without
     * an attack attribute (ghasts), recover it from the health buff. 1.0 for unbuffed mobs.
     */
    public static double indirectDamageMultiplier(Mob mob, SanctuaryConfig.MobScaling ms) {
        AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        AttributeModifier mod = dmg == null ? null : dmg.getModifier(DAMAGE_ID);
        if (mod != null) {
            return 1.0 + mod.amount();
        }
        AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
        AttributeModifier hmod = hp == null ? null : hp.getModifier(HEALTH_ID);
        if (hmod != null && ms.healthPerBlock > 0) {
            double beyond = hmod.amount() / ms.healthPerBlock;
            return SurvivalLogic.mobPowerMultiplier(beyond, ms.damagePerBlock,
                    ms.damageMaxMultiplier, ms.damageCurveExponent);
        }
        return 1.0;
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
            double beyond = SurvivalLogic.beyondFromDamageBonus(dmgMod.amount(), ms.damagePerBlock,
                    ms.damageCurveExponent);
            double xpMult = SurvivalLogic.mobPowerMultiplier(beyond, ms.xpPerBlock, ms.xpMaxMultiplier,
                    ms.damageCurveExponent);
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

        // Clear OUR tier name only — an actual player-applied name tag is different text and
        // survives. Animals carry the flat "Feral <name>"; monsters carry their tier title.
        if (tier > 0 && mob.hasCustomName()) {
            String typeName = mob.getType().getDescription().getString();
            String expected = (mob instanceof Animal ? "Feral" : TITLES[tier]) + " " + typeName;
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
