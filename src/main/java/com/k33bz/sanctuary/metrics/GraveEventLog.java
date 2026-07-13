package com.k33bz.sanctuary.metrics;

import net.fabricmc.loader.api.FabricLoader;
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
 * Grave lifecycle history: one NDJSON line per event, appended to a per-day file
 * ({@code config/sanctuary_grave_logs/sanctuary-graves-YYYY-MM-DD.ndjson}), same pattern as
 * {@link KillEventLog}. Events: {@code created, drifted, held, claimed, summoned, decayed}.
 * {@code claimed} carries the actor and {@code robbery:true} when the actor isn't the owner —
 * the machine-readable record external dashboards need for a Graves Robbed leaderboard.
 */
public final class GraveEventLog {
    private GraveEventLog() {
    }

    private static BufferedWriter writer;
    private static LocalDate writerDay;

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_grave_logs");
    }

    /** Append one grave event (buffered; flushed by the tick loop). */
    public static synchronized void record(String event, String graveId, String ownerName,
                                           String dim, double x, double y, double z,
                                           int itemStacks, String actorName, boolean robbery) {
        try {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            if (writer == null || !today.equals(writerDay)) {
                close();
                Files.createDirectories(dir());
                writer = Files.newBufferedWriter(
                        dir().resolve("sanctuary-graves-" + today + ".ndjson"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDay = today;
            }
            writer.write(String.format(Locale.ROOT,
                    "{\"t\":%d,\"event\":\"%s\",\"grave\":\"%s\",\"owner\":\"%s\",\"dim\":\"%s\","
                            + "\"x\":%d,\"y\":%d,\"z\":%d,\"stacks\":%d,\"actor\":%s,\"robbery\":%b}%n",
                    System.currentTimeMillis(), event, graveId, escape(ownerName), dim,
                    (int) x, (int) y, (int) z, itemStacks,
                    actorName == null ? "null" : "\"" + escape(actorName) + "\"", robbery));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] grave event log write failed", e);
            close();
        }
    }

    public static synchronized void flush() {
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (IOException ignored) {
        }
    }

    public static synchronized void close() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException ignored) {
        } finally {
            writer = null;
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
