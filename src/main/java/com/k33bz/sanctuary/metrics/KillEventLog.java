package com.k33bz.sanctuary.metrics;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import com.k33bz.sanctuary.Sanctuary;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Raw kill history: one NDJSON line per mob death, appended to a per-day file
 * ({@code logs/sanctuary-kills-YYYY-MM-DD.ndjson} under the config dir). Buffered — lines land in
 * memory and are flushed by the once-per-second tick, so per-event cost is a string format.
 * Append-only sequential writes are the cheapest I/O pattern there is, and NDJSON imports
 * losslessly into SQLite/pandas/jq whenever deeper analysis is wanted.
 */
public final class KillEventLog {
    private KillEventLog() {
    }

    private static BufferedWriter writer;
    private static LocalDate writerDay;

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_kill_logs");
    }

    /** Append one kill event (buffered; flushed by the tick loop). */
    public static synchronized void record(Mob mob, ServerLevel level, DamageSource source, int tier) {
        try {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            if (writer == null || !today.equals(writerDay)) {
                close();
                Files.createDirectories(dir());
                writer = Files.newBufferedWriter(
                        dir().resolve("sanctuary-kills-" + today + ".ndjson"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDay = today;
            }
            String src = mob.entityTags().stream()
                    .filter(t -> t.startsWith("sanctuary_src_"))
                    .map(t -> t.substring("sanctuary_src_".length()))
                    .findFirst().orElse("unknown");
            String killer = source.getEntity() instanceof ServerPlayer p
                    ? "\"" + p.getGameProfile().name() + "\"" : "null";
            writer.write(String.format(Locale.ROOT,
                    "{\"t\":%d,\"dim\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,\"mob\":\"%s\",\"src\":\"%s\","
                            + "\"tier\":%d,\"xp\":%d,\"killer\":%s}%n",
                    System.currentTimeMillis(),
                    level.dimension().identifier().getPath(),
                    mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                    mob.getType().getDescriptionId().replace("entity.minecraft.", ""),
                    src, tier, mob.xpReward, killer));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Kill event log write failed", e);
            close();
        }
    }

    /** Push buffered lines to disk (called by the tick loop; cheap no-op when idle). */
    public static synchronized void flush() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                Sanctuary.LOGGER.warn("[sanctuary] Kill event log flush failed", e);
                close();
            }
        }
    }

    public static synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
            writerDay = null;
        }
    }
}
