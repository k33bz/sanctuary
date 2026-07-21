package com.k33bz.sanctuary.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import com.k33bz.sanctuary.Sanctuary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persisted night-events state (config/sanctuary_events_state.json, same Gson + config-dir idiom as
 * RiftStore): which event is active tonight, plus admin overrides ({@code force}/{@code skip}) that the
 * pure schedule can't know. The scheduled pick itself is NOT stored — it is recomputed deterministically.
 */
public final class NightEventStore {

    public String activeEvent = "ordinary";
    public long activeNightDay = -1;         // daylight day the active event belongs to (-1 = none)
    public long activatedAtGameTime = 0;
    public Map<String, String> forcedByDay = new HashMap<>();  // day(String) -> event key
    public Set<Long> skippedDays = new HashSet<>();
    public int scheduleVersion = 1;
    public long lastExportGameTime = 0;

    private static NightEventStore instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static NightEventStore get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_events_state.json");
    }

    private static NightEventStore load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                NightEventStore s = GSON.fromJson(Files.readString(p), NightEventStore.class);
                if (s != null) {
                    if (s.forcedByDay == null) {
                        s.forcedByDay = new HashMap<>();
                    }
                    if (s.skippedDays == null) {
                        s.skippedDays = new HashSet<>();
                    }
                    if (s.activeEvent == null) {
                        s.activeEvent = "ordinary";
                    }
                    return s;
                }
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to load night-events state; starting fresh", e);
        }
        return new NightEventStore();
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to save night-events state", e);
        }
    }
}
