package com.k33bz.sanctuary.event;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import com.k33bz.sanctuary.MobDifficulty;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The per-event mechanics for {@link NightEvents}. Effects only ever touch WILD ground
 * ({@code blocksBeyondNearestAnchor > 0}) in a scaling (overworld) dimension for real, survival players,
 * so sanctuaries, other dimensions, and spectators/creative admins are spared for free. Robust,
 * version-friendly effects (spawns/particles/sound/potions) go through the command sink; only the small,
 * verified Java surface (target set, explosion, attributes) is called directly.
 *
 * <p>Cadence discipline: the two interval-driven effects (Blood-Moon extra spawns, Meteor impacts) run
 * from the UNTHROTTLED {@link #worldTick} and gate on {@code server.overworld().getGameTime()} — a
 * phase-stable, monotonic clock — so they fire on a fixed schedule no matter how often the throttled
 * player loop samples, and regardless of feature enable/disable history. The two continuous effects
 * (The Hunt retarget, Still-Night boon) run from {@link #playerTick} on the throttled player loop.
 */
final class EventDrivers {
    private EventDrivers() {
    }

    /** Scheduled meteor impacts awaiting detonation (warning already shown at enqueue). */
    private record Impact(ServerLevel level, double x, double y, double z, long at, float power, boolean block) {
    }

    private static final List<Impact> METEORS = new ArrayList<>();
    /** Hard ceiling on queued impacts, so a crowd of wild players can never grow the queue without bound. */
    private static final int MAX_QUEUED_METEORS = 256;

    /** Temporary follow-range boost for The Hunt (transient — not saved, so it self-clears on reload). */
    private static final Identifier HUNT_FOLLOW_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "hunt_follow");

    static void onStart(MinecraftServer server, SanctuaryConfig cfg, NightEvent ev) {
        if (ev == NightEvent.THE_HUNT) {
            forEachWildHostile(server, cfg, (level, mob, player) -> huntMark(mob, player, cfg));
        }
    }

    static void onEnd(MinecraftServer server, SanctuaryConfig cfg, NightEvent ev) {
        if (ev == NightEvent.THE_HUNT) {
            for (ServerLevel level : server.getAllLevels()) {
                if (!cfg.isScalingDimension(level)) {
                    continue;
                }
                for (Mob mob : level.getEntitiesOfClass(Mob.class, EVERYWHERE, m -> m.entityTags().contains(NightEvents.HUNTER_TAG))) {
                    mob.removeTag(NightEvents.HUNTER_TAG);
                    mob.setTarget(null);
                    MobDifficulty.clearHuntDoorBreaker(mob); // revoke only Hunt-granted door-breaking
                    AttributeInstance fr = mob.getAttribute(Attributes.FOLLOW_RANGE);
                    if (fr != null) {
                        fr.removeModifier(HUNT_FOLLOW_ID);
                    }
                }
            }
        }
        if (ev == NightEvent.METEOR_SHOWER) {
            METEORS.clear();
        }
    }

    /** Unthrottled world tick (every server tick): detonate due meteors, then drive the cadenced effects. */
    static void worldTick(MinecraftServer server, SanctuaryConfig cfg, NightEvent ev) {
        if (!METEORS.isEmpty()) {
            long now = server.overworld().getGameTime();
            METEORS.removeIf(m -> {
                if (now < m.at) {
                    return false;
                }
                m.level.explode(null, m.x, m.y, m.z, m.power,
                        m.block ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
                return true;
            });
        }
        long gt = server.overworld().getGameTime();
        if (ev == NightEvent.BLOOD_MOON) {
            SanctuaryConfig.NightEvents.BloodMoon c = cfg.nightEvents.blood_moon;
            if (gt % Math.max(20, c.spawnIntervalTicks) == 0) {
                forEachWildPlayer(server, cfg, (level, p) -> bloodMoonSpawns(p, level, cfg));
            }
        } else if (ev == NightEvent.METEOR_SHOWER) {
            SanctuaryConfig.NightEvents.Meteor c = cfg.nightEvents.meteor_shower;
            if (gt % Math.max(20, c.intervalTicks) == 0) {
                forEachWildPlayer(server, cfg, (level, p) -> meteorSpawns(p, level, cfg, gt));
            }
        } else if (ev == NightEvent.THE_SWARM) {
            SanctuaryConfig.NightEvents.Swarm c = cfg.nightEvents.the_swarm;
            if (gt % Math.max(20, c.spawnIntervalTicks) == 0) {
                forEachWildPlayer(server, cfg, (level, p) -> undergroundSpawns(p, level, cfg, c.extraPerPlayer, c.maxNearbyHostiles));
            }
        } else if (ev == NightEvent.THE_GLOOM) {
            SanctuaryConfig.NightEvents.Gloom c = cfg.nightEvents.the_gloom;
            if (gt % Math.max(20, c.spawnIntervalTicks) == 0) {
                forEachWildPlayer(server, cfg, (level, p) -> undergroundSpawns(p, level, cfg, c.extraPerPlayer, c.maxNearbyHostiles));
            }
        } else if (ev == NightEvent.BAD_AIR) {
            SanctuaryConfig.NightEvents.BadAir c = cfg.nightEvents.bad_air;
            if (gt % Math.max(20, c.intervalTicks) == 0) {
                forEachWildPlayer(server, cfg, (level, p) -> badAirDamage(p, level, cfg));
            }
        }
    }

    /** Per-player per-throttled-tick effects: the ones that should run continuously through the night. */
    static void playerTick(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, NightEvent ev, long gt) {
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, player.getX(), player.getZ());
        if (beyond <= 0.0) {
            return; // in a sanctuary — every night event spares you here
        }
        switch (ev) {
            case THE_HUNT -> hunt(player, level, cfg, gt);
            case STILL_NIGHT -> still(player, level, cfg);
            case BAD_AIR -> badAir(player, level, cfg);
            case TREMORS -> tremors(player, level, cfg, gt);
            case THE_GLOOM -> gloomDarkness(player, level, cfg);
            case DEEP_RICHES -> deepRiches(player, level, cfg);
            default -> { } // Blood Moon, Meteor, Swarm/Gloom spawns are driven from the unthrottled worldTick
        }
    }

    // ---- Blood Moon: extra weighted wild spawns near the player ----

    private static void bloodMoonSpawns(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg) {
        SanctuaryConfig.NightEvents.BloodMoon c = cfg.nightEvents.blood_moon;
        for (int i = 0; i < Math.max(1, c.extraPerPlayer); i++) {
            // Re-check the local hostile density BEFORE each spawn, so a single pass can't blow past the
            // ceiling (each spawnCategoryForPosition can add a whole pack). Per-player, local (48 blocks).
            long nearby = level.getEntitiesOfClass(Mob.class,
                    player.getBoundingBox().inflate(48), m -> m instanceof Enemy).size();
            if (nearby >= c.maxNearbyHostiles) {
                return; // don't pile up around this player
            }
            BlockPos pos = ringSurface(level, player, c.spawnRadiusMin, c.spawnRadiusMax);
            if (pos == null || Sanctuary.blocksBeyondNearestAnchor(cfg, pos.getX() + 0.5, pos.getZ() + 0.5) <= 0.0) {
                continue; // no surface, or the spot fell inside a sanctuary
            }
            // A real weighted monster wave; ENTITY_LOAD -> MobDifficulty.onSpawn then buffs it (incl. Blood-Moon).
            NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, pos);
        }
    }

    // ---- Meteor Shower: warned, dodgeable impacts in the wild ----

    private static void meteorSpawns(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, long now) {
        SanctuaryConfig.NightEvents.Meteor c = cfg.nightEvents.meteor_shower;
        for (int i = 0; i < Math.max(1, c.perInterval); i++) {
            if (METEORS.size() >= MAX_QUEUED_METEORS) {
                return;
            }
            BlockPos pos = ringSurface(level, player, c.radiusMin, c.radiusMax);
            if (pos == null || Sanctuary.blocksBeyondNearestAnchor(cfg, pos.getX() + 0.5, pos.getZ() + 0.5) <= 0.0) {
                continue;
            }
            double ix = pos.getX() + 0.5;
            double iz = pos.getZ() + 0.5;
            double iy = pos.getY();
            // warning streak + whistle so it can be dodged
            run(level.getServer(), String.format(Locale.ROOT,
                    "particle minecraft:flame %.1f %.1f %.1f 0.2 4 0.2 0.02 30 force", ix, iy + c.startHeight, iz));
            run(level.getServer(), String.format(Locale.ROOT,
                    "execute positioned %.1f %.1f %.1f run playsound minecraft:entity.blaze.shoot hostile @a[distance=..64] ~ ~ ~ 1 0.6", ix, iy, iz));
            METEORS.add(new Impact(level, ix, iy, iz, now + Math.max(5, c.warnTicks), c.power, c.blockDamage));
        }
    }

    // ---- The Hunt: wild hostiles seek the nearest wild player and (as promised) break doors ----

    private static void hunt(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, long gt) {
        SanctuaryConfig.NightEvents.Hunt c = cfg.nightEvents.the_hunt;
        // Honor the configured retarget cadence. playerTick samples every regenIntervalTicks, so this fires
        // once per retargetIntervalTicks window (phase-stable on gameTime).
        if (gt % Math.max(20, c.retargetIntervalTicks) >= Math.max(1, cfg.regenIntervalTicks)) {
            return;
        }
        AABB box = player.getBoundingBox().inflate(c.retargetRange);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy)) {
            if (Sanctuary.blocksBeyondNearestAnchor(cfg, mob.getX(), mob.getZ()) <= 0.0) {
                continue;
            }
            if (mob.getTarget() == null || !mob.getTarget().isAlive()) {
                huntMark(mob, player, cfg);
            }
        }
    }

    private static void huntMark(Mob mob, ServerPlayer player, SanctuaryConfig cfg) {
        SanctuaryConfig.NightEvents.Hunt c = cfg.nightEvents.the_hunt;
        mob.addTag(NightEvents.HUNTER_TAG);
        mob.setTarget(player);
        // Relentless: notice + chase the player from much farther than normal (transient — clears at dawn/reload).
        AttributeInstance fr = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (fr != null && c.followRangeMult > 1.0 && fr.getModifier(HUNT_FOLLOW_ID) == null) {
            fr.addOrUpdateTransientModifier(new AttributeModifier(
                    HUNT_FOLLOW_ID, c.followRangeMult - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        // And, as the banner promises, recruit zombies to break player-placed doors.
        if (c.doorBreak) {
            MobDifficulty.makeHuntDoorBreaker(mob);
        }
    }

    // ---- Still Night: a small boon ----

    private static void still(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg) {
        SanctuaryConfig.NightEvents.Still c = cfg.nightEvents.still_night;
        // Re-apply the short boon each player-tick (~1s) so it stays continuously present through the night.
        run(level.getServer(), String.format(Locale.ROOT,
                "effect give %s minecraft:regeneration %d %d true", player.getScoreboardName(),
                Math.max(1, c.boonSeconds), Math.max(0, c.boonAmplifier)));
    }

    // ---- Underground events: only players BELOW the surface, out in the wild, are affected ----

    private static boolean isUnderground(ServerLevel level, ServerPlayer player, SanctuaryConfig cfg) {
        return isUndergroundPos(level, player.getX(), player.getY(), player.getZ(), cfg);
    }

    /** True when a position is genuinely underground: no sky access (water treated as transparent, so
     *  open-water divers don't count) AND meaningfully below sea level (so forest canopy, natural overhangs,
     *  and roofed surface bases — all at/above sea level — never read as "underground"). */
    static boolean isUndergroundPos(Level level, double x, double y, double z, SanctuaryConfig cfg) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(x, y, z);
        if (level.canSeeSkyFromBelowWater(pos)) {
            return false;
        }
        return y < level.getSeaLevel() - Math.max(1, cfg.nightEvents.undergroundDepth);
    }

    /** Extra weighted cave-mob spawns around a deep wild player (The Swarm / The Gloom). */
    private static void undergroundSpawns(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, int extra, int maxNearby) {
        if (!isUnderground(level, player, cfg)) {
            return;
        }
        for (int i = 0; i < Math.max(1, extra); i++) {
            long nearby = level.getEntitiesOfClass(Mob.class,
                    player.getBoundingBox().inflate(32), m -> m instanceof Enemy).size();
            if (nearby >= maxNearby) {
                return;
            }
            // Vanilla rejects any spawn within 24 blocks of the nearest player, so seeding at the player's
            // own position rejects every attempt. Seed a dark cave cell 25-40 blocks out instead;
            // MobDifficulty.onSpawn then buffs whatever spawns (incl. The Gloom's cave powerFactor).
            BlockPos pos = caveSpawnPos(level, player);
            if (pos != null) {
                NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, pos);
            }
        }
    }

    /** A cave-air cell (air with a floor) 25-40 blocks from the player, near their Y — far enough to clear
     *  vanilla's 24-block min-spawn-distance. spawnCategoryForPosition validates darkness/spawn rules. */
    private static BlockPos caveSpawnPos(ServerLevel level, ServerPlayer player) {
        int py = (int) Math.floor(player.getY());
        for (int tries = 0; tries < 10; tries++) {
            double ang = level.getRandom().nextDouble() * Math.PI * 2.0;
            double dist = 25.0 + level.getRandom().nextDouble() * 15.0; // 25..40 (> the 24-block rejection)
            int x = (int) Math.floor(player.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(player.getZ() + Math.sin(ang) * dist);
            for (int dy = 0; dy <= 6; dy++) {
                for (int y : new int[]{py - dy, py + dy}) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                            && !level.getBlockState(p.below()).isAir()) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** Bad Air: deep wild players suffocate unless they carry Water Breathing / Conduit Power. No water is
     *  placed — foul air is drown-type damage on an interval + a choking Nausea; the potion is the counter. */
    private static void badAir(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg) {
        if (!isUnderground(level, player, cfg)) {
            return;
        }
        if (player.hasEffect(MobEffects.WATER_BREATHING) || player.hasEffect(MobEffects.CONDUIT_POWER)) {
            return; // warded — you breathe easy
        }
        // Continuous choking Nausea; the actual toxic damage is dealt on a fixed cadence from worldTick.
        if (cfg.nightEvents.bad_air.nausea) {
            run(level.getServer(), "effect give " + player.getScoreboardName() + " minecraft:nausea 4 0 true");
        }
    }

    /** Bad Air's toxic damage — driven from the UNTHROTTLED worldTick on a fixed gameTime cadence, so each
     *  hit lands cleanly past the entity invulnerability window (a per-tick /damage would be erratic). */
    private static void badAirDamage(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg) {
        if (!isUnderground(level, player, cfg)) {
            return;
        }
        if (player.hasEffect(MobEffects.WATER_BREATHING) || player.hasEffect(MobEffects.CONDUIT_POWER)) {
            return; // warded — you breathe easy
        }
        if (player.getHealth() <= 2.0f) {
            return; // never deal the killing blow: that would loop the mod's lethal-save (unbounded XP drain).
                    // Floored at 2 HP, the toxic air leaves you at the mercy of the dark and the cave mobs.
        }
        // minecraft:magic bypasses armour + difficulty scaling (unlike drown/generic); Resistance/absorption
        // still soak it, which is fair for a prepared player.
        run(level.getServer(), String.format(Locale.ROOT, "damage %s %.1f minecraft:magic",
                player.getScoreboardName(), Math.max(0.5f, cfg.nightEvents.bad_air.damage)));
    }

    /** Tremors: interval cave-ins — Blindness + Nausea + a rumble, and a chance of falling-debris damage. */
    private static void tremors(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, long gt) {
        if (!isUnderground(level, player, cfg)) {
            return;
        }
        SanctuaryConfig.NightEvents.Tremors c = cfg.nightEvents.tremors;
        if (gt % Math.max(20, c.intervalTicks) >= Math.max(1, cfg.regenIntervalTicks)) {
            return; // fire once per interval window
        }
        String name = player.getScoreboardName();
        run(level.getServer(), "effect give " + name + " minecraft:blindness " + Math.max(1, c.blindnessTicks / 20) + " 0 true");
        run(level.getServer(), "effect give " + name + " minecraft:nausea 3 0 true");
        run(level.getServer(), String.format(Locale.ROOT,
                "execute positioned %.1f %.1f %.1f run playsound minecraft:entity.generic.explode block @a[distance=..40] ~ ~ ~ 0.6 0.4",
                player.getX(), player.getY(), player.getZ()));
        if (level.getRandom().nextDouble() < c.debrisChance) {
            // magic (armour-independent) like Bad Air, so the cave-in is felt by the geared miners who trigger it
            run(level.getServer(), String.format(Locale.ROOT, "damage %s %.1f minecraft:magic", name, Math.max(0.5f, c.debrisDamage)));
        }
    }

    /** The Gloom: continuous oppressive Darkness underground (the tougher/extra spawns come from worldTick). */
    private static void gloomDarkness(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg) {
        if (!isUnderground(level, player, cfg)) {
            return;
        }
        if (cfg.nightEvents.the_gloom.darkness) {
            run(level.getServer(), "effect give " + player.getScoreboardName() + " minecraft:darkness 3 0 true");
        }
    }

    /** Deep Riches: a miner's blessing — Haste (+ Night Vision) for deep wild diggers. */
    private static void deepRiches(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg) {
        if (!isUnderground(level, player, cfg)) {
            return;
        }
        SanctuaryConfig.NightEvents.DeepRiches c = cfg.nightEvents.deep_riches;
        String name = player.getScoreboardName();
        int sec = Math.max(1, c.boonSeconds);
        run(level.getServer(), "effect give " + name + " minecraft:haste " + sec + " " + Math.max(0, c.hasteAmplifier) + " true");
        if (c.nightVision) {
            // longer window so it doesn't flicker (vanilla warns < 10s); re-applied each player-tick anyway
            run(level.getServer(), "effect give " + name + " minecraft:night_vision " + (sec + 12) + " 0 true");
        }
    }

    // ---- helpers ----

    private static final AABB EVERYWHERE = new AABB(-3.0e7, -64, -3.0e7, 3.0e7, 320, 3.0e7);

    private interface WildPlayerAction {
        void run(ServerLevel level, ServerPlayer player);
    }

    /** Run for every survival (non-spectator, non-creative) player currently out in the wild of a scaling dim. */
    private static void forEachWildPlayer(MinecraftServer server, SanctuaryConfig cfg, WildPlayerAction action) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!cfg.isScalingDimension(level)) {
                continue;
            }
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator() || p.isCreative()) {
                    continue;
                }
                if (Sanctuary.blocksBeyondNearestAnchor(cfg, p.getX(), p.getZ()) > 0.0) {
                    action.run(level, p);
                }
            }
        }
    }

    /** A surface block in a horizontal ring around the player, or null. */
    private static BlockPos ringSurface(ServerLevel level, ServerPlayer player, int min, int max) {
        int lo = Math.max(1, Math.min(min, max));
        int hi = Math.max(lo + 1, Math.max(min, max));
        double ang = level.getRandom().nextDouble() * Math.PI * 2.0;
        double dist = lo + level.getRandom().nextDouble() * (hi - lo);
        int x = (int) Math.floor(player.getX() + Math.cos(ang) * dist);
        int z = (int) Math.floor(player.getZ() + Math.sin(ang) * dist);
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y <= level.getMinY() + 1) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private interface WildMobAction {
        void run(ServerLevel level, Mob mob, ServerPlayer player);
    }

    private static void forEachWildHostile(MinecraftServer server, SanctuaryConfig cfg, WildMobAction action) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!cfg.isScalingDimension(level)) {
                continue;
            }
            List<ServerPlayer> players = new ArrayList<>();
            for (ServerPlayer p : level.players()) {
                if (!p.isSpectator() && !p.isCreative()) {
                    players.add(p); // only real survival players are valid hunt targets
                }
            }
            if (players.isEmpty()) {
                continue;
            }
            for (Mob mob : level.getEntitiesOfClass(Mob.class, EVERYWHERE, m -> m instanceof Enemy)) {
                if (Sanctuary.blocksBeyondNearestAnchor(cfg, mob.getX(), mob.getZ()) <= 0.0) {
                    continue;
                }
                ServerPlayer nearest = null;
                double best = Double.MAX_VALUE;
                for (ServerPlayer p : players) {
                    double d = p.distanceToSqr(mob);
                    if (d < best) {
                        best = d;
                        nearest = p;
                    }
                }
                if (nearest != null) {
                    action.run(level, mob, nearest);
                }
            }
        }
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
