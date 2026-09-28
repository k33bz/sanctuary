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

/**
 * System 12 audit trail: one NDJSON line per base-repair event, per-day files under
 * {@code config/sanctuary_repair_logs/}. Same shape and cost model as {@link KillEventLog}
 * (buffered, flushed by the once-per-second tick). Events:
 * <ul>
 *   <li>{@code journaled} — a damage burst was captured for rebuilding (cause, count, actor)</li>
 *   <li>{@code restored} — blocks rebuilt this pass, and the fuel they cost</li>
 *   <li>{@code superseded} — journal entries dropped because someone built over the spot</li>
 *   <li>{@code player_break} — a NON-owner broke a block inside an active sanctuary. Not rebuilt
 *       (the breaker keeps the drop); logged so raid patterns and dupe attempts are visible.</li>
 * </ul>
 * Anti-farming is observed, not nerfed: this log is how an admin spots an owner-plus-alt dupe loop.
 */
public final class BaseRepairLog {
    private BaseRepairLog() {
    }

    private static BufferedWriter writer;
    private static LocalDate writerDay;

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_repair_logs");
    }

    /**
     * Append one event. {@code kv} alternates keys and values; values that are {@link Number} or
     * {@link Boolean} are written raw, everything else as an escaped string, null as JSON null.
     */
    public static synchronized void event(String type, Object... kv) {
        try {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            if (writer == null || !today.equals(writerDay)) {
                close();
                Files.createDirectories(dir());
                writer = Files.newBufferedWriter(
                        dir().resolve("sanctuary-repairs-" + today + ".ndjson"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDay = today;
            }
            StringBuilder b = new StringBuilder(160);
            b.append("{\"t\":").append(System.currentTimeMillis())
                    .append(",\"event\":\"").append(escape(type)).append('"');
            for (int i = 0; i + 1 < kv.length; i += 2) {
                b.append(",\"").append(escape(String.valueOf(kv[i]))).append("\":");
                Object v = kv[i + 1];
                if (v == null) {
                    b.append("null");
                } else if (v instanceof Number || v instanceof Boolean) {
                    b.append(v);
                } else {
                    b.append('"').append(escape(String.valueOf(v))).append('"');
                }
            }
            b.append('}').append(System.lineSeparator());
            writer.write(b.toString());
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Repair log write failed", e);
            close();
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    /** Push buffered lines to disk (called by the tick loop; cheap no-op when idle). */
    public static synchronized void flush() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                Sanctuary.LOGGER.warn("[sanctuary] Repair log flush failed", e);
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
