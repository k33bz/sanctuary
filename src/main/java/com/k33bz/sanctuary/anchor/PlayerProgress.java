package com.k33bz.sanctuary.anchor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import com.k33bz.sanctuary.Sanctuary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-player progression: how many sanctuaries a player may bind. Everyone starts at
 * {@code anchorCapBase} (1); each raise demands a Warden kill of the next tier up (any Warden,
 * then Feral+, Savage+, ... to Nightmare), bounded by {@code anchorCapMax} unless an admin sets
 * a player's cap directly. Persisted to {@code config/sanctuary_players.json}.
 */
public final class PlayerProgress {
    private PlayerProgress() {
    }

    /** uuid -> anchor cap (absent = base). */
    private static Map<String, Integer> caps;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_players.json");
    }

    private static Map<String, Integer> caps() {
        if (caps == null) {
            caps = load();
        }
        return caps;
    }

    /** This player's current anchor cap. */
    public static int capOf(String uuid, int base) {
        return Math.max(base, caps().getOrDefault(uuid, base));
    }

    /** Admin override — may exceed anchorCapMax. */
    public static void setCap(String uuid, int cap) {
        caps().put(uuid, cap);
        save();
    }

    /**
     * Warden tier required for this player's NEXT cap raise: the first raise takes any Warden
     * (tier 0+), each following raise the next tier up, clamped at Nightmare (4).
     */
    public static int requiredTierForNextRaise(String uuid, int base) {
        int raises = capOf(uuid, base) - base;
        return Math.min(4, raises);
    }

    /** Attempt a Warden-kill raise. Returns the new cap, or -1 if nothing changed. */
    public static int tryRaise(String uuid, int killTier, int base, int max) {
        int cap = capOf(uuid, base);
        if (cap >= max || killTier < requiredTierForNextRaise(uuid, base)) {
            return -1;
        }
        caps().put(uuid, cap + 1);
        save();
        return cap + 1;
    }

    private static Map<String, Integer> load() {
        try {
            if (Files.exists(path())) {
                Map<String, Integer> data = GSON.fromJson(Files.readString(path()),
                        new TypeToken<Map<String, Integer>>() {
                        }.getType());
                if (data != null) {
                    return data;
                }
            }
        } catch (IOException | RuntimeException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to load player progress; starting empty", e);
        }
        return new HashMap<>();
    }

    private static void save() {
        try {
            Files.writeString(path(), GSON.toJson(caps()));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to save player progress", e);
        }
    }
}
