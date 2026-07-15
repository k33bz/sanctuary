package com.k33bz.sanctuary.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The per-event mechanics for {@link NightEvents}. Effects only ever touch WILD ground
 * ({@code blocksBeyondNearestAnchor > 0}) in a scaling (overworld) dimension, so sanctuaries and other
 * dimensions are spared for free. Robust, version-friendly effects (spawns/particles/sound/potions) go
 * through the command sink; only the small, verified Java surface (target set, explosion) is called directly.
 */
final class EventDrivers {
    private EventDrivers() {
    }

    /** Scheduled meteor impacts awaiting detonation (warning already shown at enqueue). */
    private record Impact(ServerLevel level, double x, double y, double z, long at, float power, boolean block) {
    }

    private static final List<Impact> METEORS = new ArrayList<>();

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
                }
            }
        }
        if (ev == NightEvent.METEOR_SHOWER) {
            METEORS.clear();
        }
    }

    /** World-level per-tick: detonate any due meteors. */
    static void worldTick(MinecraftServer server, SanctuaryConfig cfg, NightEvent ev, long counter) {
        if (METEORS.isEmpty()) {
            return;
        }
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

    /** Per-player per-throttled-tick effects. */
    static void playerTick(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, NightEvent ev, long counter) {
        double beyond = Sanctuary.blocksBeyondNearestAnchor(cfg, player.getX(), player.getZ());
        if (beyond <= 0.0) {
            return; // in a sanctuary — every night event spares you here
        }
        switch (ev) {
            case BLOOD_MOON -> bloodMoon(player, level, cfg, counter);
            case THE_HUNT -> hunt(player, level, cfg, counter);
            case METEOR_SHOWER -> meteor(player, level, cfg, counter);
            case STILL_NIGHT -> still(player, level, cfg, counter);
            default -> { }
        }
    }

    // ---- Blood Moon: extra weighted wild spawns near the player ----

    private static void bloodMoon(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, long counter) {
        SanctuaryConfig.NightEvents.BloodMoon c = cfg.nightEvents.blood_moon;
        if (counter % Math.max(20, c.spawnIntervalTicks) != 0) {
            return;
        }
        long hostiles = level.getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(48), m -> m instanceof Enemy).size();
        if (hostiles >= c.maxAddedGlobal) {
            return; // don't pile up
        }
        for (int i = 0; i < Math.max(1, c.extraPerPlayer); i++) {
            BlockPos pos = ringSurface(level, player, c.spawnRadiusMin, c.spawnRadiusMax);
            if (pos == null) {
                continue;
            }
            if (Sanctuary.blocksBeyondNearestAnchor(cfg, pos.getX() + 0.5, pos.getZ() + 0.5) <= 0.0) {
                continue; // spot fell inside a sanctuary
            }
            // A real weighted monster wave; ENTITY_LOAD -> MobDifficulty.onSpawn then buffs it (incl. Blood-Moon).
            NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, pos);
        }
    }

    // ---- The Hunt: wild hostiles seek the nearest wild player ----

    private static void hunt(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, long counter) {
        SanctuaryConfig.NightEvents.Hunt c = cfg.nightEvents.the_hunt;
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
        mob.addTag(NightEvents.HUNTER_TAG);
        mob.setTarget(player);
    }

    // ---- Meteor Shower: warned, dodgeable impacts in the wild ----

    private static void meteor(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, long counter) {
        SanctuaryConfig.NightEvents.Meteor c = cfg.nightEvents.meteor_shower;
        if (counter % Math.max(20, c.intervalTicks) != 0) {
            return;
        }
        long now = level.getServer().overworld().getGameTime();
        for (int i = 0; i < Math.max(1, c.perInterval); i++) {
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
                    "playsound minecraft:entity.blaze.shoot hostile @a[distance=..64] %.1f %.1f %.1f 1 0.6", ix, iy, iz));
            METEORS.add(new Impact(level, ix, iy, iz, now + Math.max(5, c.warnTicks), c.power, c.blockDamage));
        }
    }

    // ---- Still Night: a small boon ----

    private static void still(ServerPlayer player, ServerLevel level, SanctuaryConfig cfg, long counter) {
        SanctuaryConfig.NightEvents.Still c = cfg.nightEvents.still_night;
        // Re-apply the short boon each player-tick (~1s) so it stays continuously present through the night.
        run(level.getServer(), String.format(Locale.ROOT,
                "effect give %s minecraft:regeneration %d %d true", player.getScoreboardName(),
                Math.max(1, c.boonSeconds), Math.max(0, c.boonAmplifier)));
    }

    // ---- helpers ----

    private static final AABB EVERYWHERE = new AABB(-3.0e7, -64, -3.0e7, 3.0e7, 320, 3.0e7);

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
            List<ServerPlayer> players = level.players();
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
