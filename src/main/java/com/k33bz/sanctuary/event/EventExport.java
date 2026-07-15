package com.k33bz.sanctuary.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code config/sanctuary_events.json} — the authoritative "tonight + upcoming nights" feed that
 * the mc.kast.ro (mcweb) panel reads (synced over WireGuard). It includes admin overrides the pure
 * schedule can't know, and the seed + weights + algorithm id so the website can independently recompute
 * (and verify) any night. Seed and game-time fields are STRINGS (JS loses precision past 2^53).
 */
public final class EventExport {
    private EventExport() {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_events.json");
    }

    static void write(MinecraftServer server, SanctuaryConfig cfg, NightEvent active) {
        try {
            SanctuaryConfig.NightEvents c = cfg.nightEvents;
            long seed = server.overworld().getSeed();
            long clock = server.overworld().getOverworldClockTime();
            long day = Math.floorDiv(clock, 24000L);
            int tod = (int) Math.floorMod(clock, 24000L);
            boolean night = tod >= c.nightStartTime;
            NightEventStore store = NightEventStore.get();

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("version", 1);
            Map<String, Object> algo = new LinkedHashMap<>();
            algo.put("name", "sha256-weighted-v1");
            algo.put("scheduleVersion", c.scheduleVersion);
            algo.put("firstEventDay", c.firstEventDay); // grace days — the website needs these to recompute
            algo.put("noRepeat", c.noRepeat);           // the no-repeat rule the schedule was drawn with
            List<String> order = new ArrayList<>();
            for (NightEvent e : NightEvent.values()) {
                order.add(e.key());
            }
            algo.put("eventOrder", order);
            root.put("algorithm", algo);
            root.put("worldSeed", Long.toString(seed));
            root.put("currentDayNumber", day);
            root.put("timeOfDay", tod);
            root.put("isNight", night);
            root.put("nightStartTime", c.nightStartTime);
            root.put("enabled", c.enabled);

            Map<String, Integer> weights = new LinkedHashMap<>();
            int[] w = NightEvents.weights(c);
            for (NightEvent e : NightEvent.values()) {
                weights.put(e.key(), w[e.ordinal()]);
            }
            root.put("weights", weights);

            NightEvent tonightEv = NightEvents.resolveFor(server, day, c);
            root.put("tonight", nightNode(day, tonightEv, sourceOf(store, day),
                    night && active == tonightEv && tonightEv.isActiveEvent(),
                    Long.toString(store.activatedAtGameTime)));

            List<Map<String, Object>> upcoming = new ArrayList<>();
            int n = Math.max(1, c.exportUpcomingDays);
            for (int i = 1; i <= n; i++) {
                long d = day + i;
                upcoming.add(nightNode(d, NightEvents.resolveFor(server, d, c), sourceOf(store, d), false, null));
            }
            root.put("upcoming", upcoming);

            Files.writeString(path(), GSON.toJson(root));
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to write night-events export", e);
        }
    }

    private static Map<String, Object> nightNode(long day, NightEvent ev, String source, boolean active, String startedGameTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("event", ev.key());
        m.put("displayName", ev.displayName());
        m.put("color", ev.hex());
        m.put("source", source);
        m.put("active", active);
        if (startedGameTime != null) {
            m.put("startedGameTime", startedGameTime);
        }
        return m;
    }

    private static String sourceOf(NightEventStore store, long day) {
        if (store.skippedDays.contains(day)) {
            return "skipped";
        }
        if (store.forcedByDay.containsKey(Long.toString(day))) {
            return "forced";
        }
        return "scheduled";
    }
}
