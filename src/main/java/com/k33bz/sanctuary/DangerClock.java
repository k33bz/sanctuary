package com.k33bz.sanctuary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The world-danger AGE clock — occupied time, not wall/world time.
 *
 * <p>System 4's age term must only advance while the world is actually being PLAYED. The world game-clock
 * ticks at 20/s whenever the server is up — including an empty server and a Chunky pregen — so measuring
 * age off game-time silently made mobs tougher during maintenance (a 20k pregen aged the world ~50
 * "days" with nobody online). Instead we accrue OCCUPIED ticks: +1 per server tick while at least one
 * player is connected. 24000 occupied ticks = one played day, the same unit {@code danger.perDayWeight}
 * expects, so the tuning numbers keep their meaning.
 *
 * <p>Persisted to {@code config/sanctuary_danger.json}. The live counter is a {@code volatile long} the
 * damage mixin reads per hit; it is only ever written on the server thread (the tick handler, the reset
 * command, and server stop), so no locking is needed.
 */
public final class DangerClock {
    private DangerClock() {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_EVERY = 600L; // checkpoint ~every 30s of occupied time (20 tps)

    private static volatile long occupiedTicks = 0L;
    private static long lastSaved = 0L;

    /** Persisted shape; public field for trivial Gson (de)serialization. */
    private static final class State {
        long occupiedTicks;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_danger.json");
    }

    /** Occupied ticks accrued since the last reset — the age basis for the world-danger multiplier. */
    public static long ageTicks() {
        return occupiedTicks;
    }

    /** Load the persisted counter at server start (SERVER_STARTED). */
    public static void load(MinecraftServer server) {
        try {
            Path p = path();
            if (Files.exists(p)) {
                State s = GSON.fromJson(Files.readString(p), State.class);
                if (s != null) {
                    occupiedTicks = Math.max(0L, s.occupiedTicks);
                }
            }
        } catch (Exception e) {
            // Starting age at 0 is the safe direction (softer, not harder), so a bad read is non-fatal.
            Sanctuary.LOGGER.warn("[sanctuary] could not load the danger clock; starting age at 0", e);
        }
        lastSaved = occupiedTicks;
    }

    /** +1 per tick while occupied; periodic checkpoint. Registered on END_SERVER_TICK. */
    public static void tick(MinecraftServer server) {
        if (server.getPlayerList().getPlayerCount() <= 0) {
            return; // an empty server (or a pregen) does not age the world — the whole point of this clock
        }
        occupiedTicks++;
        if (occupiedTicks - lastSaved >= SAVE_EVERY) {
            save();
        }
    }

    /** Zero the age. Called by the danger-reset command; persists immediately. */
    public static void reset() {
        occupiedTicks = 0L;
        save();
    }

    /** Persist the counter (periodic checkpoint, reset, and server stop). */
    public static void save() {
        Path p = path();
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        try {
            State s = new State();
            s.occupiedTicks = occupiedTicks;
            // Atomic: write a sibling temp, then rename it over the target. A crash mid-write can then only
            // corrupt the temp, never the live file — so an unlucky checkpoint can never truncate away the
            // ENTIRE accrued age (weeks of a season); at worst the last checkpoint is lost. Same-directory
            // temp guarantees a same-filesystem, atomic rename.
            Files.writeString(tmp, GSON.toJson(s));
            try {
                Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); // best effort on exotic filesystems
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] failed to save the danger clock", e);
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // leave the stray temp; the next successful save overwrites it
            }
        } finally {
            // Advance the checkpoint marker even on failure, so a persistently failing write (full or
            // read-only disk) backs off to one attempt per SAVE_EVERY ticks instead of retrying 20x/second.
            lastSaved = occupiedTicks;
        }
    }
}
