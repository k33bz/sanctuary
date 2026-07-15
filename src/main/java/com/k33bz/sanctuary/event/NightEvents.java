package com.k33bz.sanctuary.event;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

/**
 * The night-events lifecycle: each Minecraft night a themed {@link NightEvent} activates until dawn,
 * chosen by the deterministic {@link EventSchedule} (seeded by world seed + daylight-clock day, so the
 * schedule is reproducible — the mc.kast.ro website computes the same nights). The unthrottled
 * {@link #tick} reconciles the active event against the clock (robust to sleeping, {@code /time set},
 * and restarts); per-player effects run from the throttled player loop via {@link #tickPlayer}. All
 * effects gate on "in the wild" ({@code blocksBeyondNearestAnchor > 0}) + overworld, so sanctuaries and
 * the Nether/End/Rift are untouched for free. OFF-safe: does nothing unless {@code nightEvents.enabled}.
 */
public final class NightEvents {
    private NightEvents() {
    }

    /** Persistent entity tag marking a mob recruited into The Hunt (survives chunk reload). */
    public static final String HUNTER_TAG = "sanctuary_hunter";

    /** The currently-active event (ORDINARY = none). Read from spawn hooks on any thread → volatile. */
    public static volatile NightEvent ACTIVE = NightEvent.ORDINARY;

    private static long counter = 0;

    // ---- config → schedule glue ----

    /** Build the weight array (indexed by NightEvent.ordinal) from config; disabled/0-weight excluded. */
    static int[] weights(SanctuaryConfig.NightEvents c) {
        int[] w = new int[NightEvent.values().length];
        w[NightEvent.ORDINARY.ordinal()] = c.ordinary.enabled ? Math.max(0, c.ordinary.weight) : 0;
        w[NightEvent.BLOOD_MOON.ordinal()] = c.blood_moon.enabled ? Math.max(0, c.blood_moon.weight) : 0;
        w[NightEvent.THE_HUNT.ordinal()] = c.the_hunt.enabled ? Math.max(0, c.the_hunt.weight) : 0;
        w[NightEvent.METEOR_SHOWER.ordinal()] = c.meteor_shower.enabled ? Math.max(0, c.meteor_shower.weight) : 0;
        w[NightEvent.STILL_NIGHT.ordinal()] = c.still_night.enabled ? Math.max(0, c.still_night.weight) : 0;
        return w;
    }

    /** The scheduled (or forced/skipped) event for a given daylight day. */
    public static NightEvent resolveFor(MinecraftServer server, long day, SanctuaryConfig.NightEvents c) {
        NightEventStore store = NightEventStore.get();
        if (store.skippedDays.contains(day)) {
            return NightEvent.ORDINARY;
        }
        String forced = store.forcedByDay.get(Long.toString(day));
        if (forced != null) {
            return NightEvent.parse(forced);
        }
        return EventSchedule.eventFor(server.overworld().getSeed(), day, weights(c),
                c.firstEventDay, c.noRepeat, c.scheduleVersion);
    }

    /** ACTIVE, but only while the feature is enabled (spawn hooks call this). */
    public static NightEvent active(SanctuaryConfig cfg) {
        return (cfg != null && cfg.nightEvents.enabled) ? ACTIVE : NightEvent.ORDINARY;
    }

    /** Blood-Moon spawn/buff amplifier fed into MobDifficulty (multiplies the distance term). 1.0 = off. */
    public static double spawnPowerFactor() {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg != null && cfg.nightEvents.enabled && ACTIVE == NightEvent.BLOOD_MOON) {
            return Math.max(1.0, cfg.nightEvents.blood_moon.powerFactor);
        }
        return 1.0;
    }

    // ---- lifecycle (unthrottled END_SERVER_TICK) ----

    public static void tick(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null) {
            return;
        }
        SanctuaryConfig.NightEvents c = cfg.nightEvents;
        if (!c.enabled) {
            if (ACTIVE != NightEvent.ORDINARY) {
                deactivate(server, cfg);
            }
            return;
        }
        long clock = server.overworld().getOverworldClockTime();
        long day = Math.floorDiv(clock, 24000L);
        int tod = (int) Math.floorMod(clock, 24000L);
        boolean night = tod >= c.nightStartTime;
        NightEventStore store = NightEventStore.get();

        if (night) {
            NightEvent desired = resolveFor(server, day, c);
            if (ACTIVE == NightEvent.ORDINARY || store.activeNightDay != day) {
                if (ACTIVE != NightEvent.ORDINARY) {
                    deactivate(server, cfg); // a stale event from a prior night
                }
                if (desired != NightEvent.ORDINARY) {
                    activate(server, cfg, day, desired);
                }
            }
        } else if (ACTIVE != NightEvent.ORDINARY) {
            deactivate(server, cfg); // dawn
        }

        if (ACTIVE != NightEvent.ORDINARY) {
            EventDrivers.worldTick(server, cfg, ACTIVE, counter);
        }
        counter++;
        maybeWriteExport(server, cfg);
    }

    private static void activate(MinecraftServer server, SanctuaryConfig cfg, long day, NightEvent ev) {
        ACTIVE = ev;
        NightEventStore store = NightEventStore.get();
        store.activeEvent = ev.key();
        store.activeNightDay = day;
        store.activatedAtGameTime = server.overworld().getGameTime();
        store.save();
        EventDrivers.onStart(server, cfg, ev);
        announceStart(server, ev);
        writeExport(server, cfg);
    }

    private static void deactivate(MinecraftServer server, SanctuaryConfig cfg) {
        NightEvent was = ACTIVE;
        ACTIVE = NightEvent.ORDINARY;
        NightEventStore store = NightEventStore.get();
        store.activeEvent = NightEvent.ORDINARY.key();
        store.activeNightDay = -1;
        store.save();
        if (was != NightEvent.ORDINARY) {
            EventDrivers.onEnd(server, cfg, was);
            announceEnd(server, was);
        }
        writeExport(server, cfg);
    }

    /** Per-player effects, called from Sanctuary's throttled player loop. */
    public static void tickPlayer(ServerPlayer player, SanctuaryConfig cfg) {
        if (cfg == null || !cfg.nightEvents.enabled || ACTIVE == NightEvent.ORDINARY) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !cfg.isScalingDimension(level)) {
            return;
        }
        EventDrivers.playerTick(player, level, cfg, ACTIVE, counter);
    }

    // ---- boot recovery ----

    public static void onServerStarted(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null) {
            return;
        }
        // Publish the seed so the website can recompute the schedule; anchor + prune the store.
        cfg.nightEvents.seed = server.overworld().getSeed();
        NightEventStore store = NightEventStore.get();
        long day = Math.floorDiv(server.overworld().getOverworldClockTime(), 24000L);
        store.forcedByDay.keySet().removeIf(k -> {
            try { return Long.parseLong(k) < day; } catch (NumberFormatException e) { return true; }
        });
        store.skippedDays.removeIf(d -> d < day);
        // ACTIVE stays ORDINARY; the first tick() reconciles it to the (deterministic) night if it's dark.
        store.activeNightDay = -1;
        store.save();
        writeExport(server, cfg);
        Sanctuary.LOGGER.info("[sanctuary] night-events enabled={} seed={} (schedule is deterministic)",
                cfg.nightEvents.enabled, cfg.nightEvents.seed);
    }

    // ---- announcements ----

    private static void announceStart(MinecraftServer server, NightEvent ev) {
        Component banner = Component.literal(startLine(ev)).withStyle(ev.color(), ChatFormatting.BOLD);
        server.getPlayerList().broadcastSystemMessage(banner, false);
        Component title = Component.literal(ev.displayName()).withStyle(ev.color(), ChatFormatting.BOLD);
        Component sub = Component.literal(subLine(ev)).withStyle(ChatFormatting.GRAY);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitleTextPacket(title));
            p.connection.send(new ClientboundSetSubtitleTextPacket(sub));
        }
    }

    private static void announceEnd(MinecraftServer server, NightEvent ev) {
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("Dawn breaks — " + ev.displayName() + " fades.").withStyle(ChatFormatting.GRAY),
                false);
    }

    private static String startLine(NightEvent ev) {
        return switch (ev) {
            case BLOOD_MOON -> "A Blood Moon rises — the wild swells with buffed horrors. Stay in a sanctuary.";
            case THE_HUNT -> "The Hunt begins — out in the wild, they will seek you and break your doors.";
            case METEOR_SHOWER -> "A Meteor Shower falls on the wildlands — danger, and rare spoils, rain down.";
            case STILL_NIGHT -> "A Still Night settles — the wild rests easy tonight.";
            default -> "The night is ordinary.";
        };
    }

    private static String subLine(NightEvent ev) {
        return switch (ev) {
            case BLOOD_MOON -> "Wild mobs are stronger and more numerous";
            case THE_HUNT -> "You are being hunted in the wild";
            case METEOR_SHOWER -> "Falling hazards + distance-tiered drops";
            case STILL_NIGHT -> "Fewer spawns, a small boon";
            default -> "";
        };
    }

    // ---- export (for mc.kast.ro) ----

    private static void maybeWriteExport(MinecraftServer server, SanctuaryConfig cfg) {
        NightEventStore store = NightEventStore.get();
        long now = server.overworld().getGameTime();
        if (now - store.lastExportGameTime >= Math.max(20, cfg.nightEvents.exportRefreshTicks)) {
            writeExport(server, cfg);
        }
    }

    static void writeExport(MinecraftServer server, SanctuaryConfig cfg) {
        NightEventStore.get().lastExportGameTime = server.overworld().getGameTime();
        EventExport.write(server, cfg, ACTIVE);
    }
}
