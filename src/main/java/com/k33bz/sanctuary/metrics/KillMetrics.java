package com.k33bz.sanctuary.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import com.k33bz.sanctuary.Sanctuary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Silent kill ledger for farm/activity detection: every mob death is binned into a 64-block cell,
 * counting totals, player-caused kills, spawner-born mobs, and XP paid out. No gameplay effect —
 * admins read it via {@code /sanctuary metrics top} to see where the kills (and farms) are.
 */
public final class KillMetrics {
    private KillMetrics() {
    }

    /** Per-cell counters. Public fields for Gson. */
    public static class Cell {
        public long kills;
        public long playerKills;
        public long spawnerBorn;
        public long xp;
    }

    public static final int CELL_SIZE = 64;

    private static Map<String, Cell> cells;
    private static boolean dirty = false;

    private static final Gson GSON = new GsonBuilder().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_metrics.json");
    }

    private static Map<String, Cell> cells() {
        if (cells == null) {
            cells = load();
        }
        return cells;
    }

    /** Record one mob death. Cheap: a map bump; persisted on the periodic flush. */
    public static void record(Mob mob, ServerLevel level, DamageSource source) {
        int cx = Math.floorDiv(mob.getBlockX(), CELL_SIZE) * CELL_SIZE;
        int cz = Math.floorDiv(mob.getBlockZ(), CELL_SIZE) * CELL_SIZE;
        String key = level.dimension().identifier().getPath() + ":" + cx + "," + cz;
        Cell cell = cells().computeIfAbsent(key, k -> new Cell());
        cell.kills++;
        if (source.getEntity() instanceof ServerPlayer) {
            cell.playerKills++;
            cell.xp += mob.xpReward;
        }
        if (mob.entityTags().contains("sanctuary_src_spawner")
                || mob.entityTags().contains("sanctuary_src_trial_spawner")) {
            cell.spawnerBorn++;
        }
        dirty = true;
    }

    /** The busiest cells, sorted by kill count. */
    public static List<Map.Entry<String, Cell>> top(int n) {
        return cells().entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Cell> e) -> e.getValue().kills).reversed())
                .limit(n)
                .toList();
    }

    public static int size() {
        return cells().size();
    }

    public static void clear() {
        cells().clear();
        dirty = true;
        flush();
    }

    /** Persist if anything changed (called on an interval; cheap no-op otherwise). */
    public static void flush() {
        if (!dirty || cells == null) {
            return;
        }
        dirty = false;
        try {
            Files.writeString(path(), GSON.toJson(cells));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to save kill metrics", e);
        }
    }

    private static Map<String, Cell> load() {
        try {
            if (Files.exists(path())) {
                Map<String, Cell> data = GSON.fromJson(Files.readString(path()),
                        new TypeToken<Map<String, Cell>>() {
                        }.getType());
                if (data != null) {
                    return data;
                }
            }
        } catch (IOException | RuntimeException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to load kill metrics; starting empty", e);
        }
        return new HashMap<>();
    }
}
