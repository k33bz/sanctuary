package com.k33bz.sanctuary.event;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The deterministic "sha256-weighted-v1" night-event schedule — a PURE function of (world seed, day,
 * weights, config). No Minecraft imports, so it unit-tests standalone AND is byte-for-byte reproducible
 * by the mc.kast.ro website (a matching Python mirror), which is the whole point: the site must agree
 * with the server. SHA-256 + integer weights + unsigned-remainder give identical results in Java and
 * Python with zero float/signed-shift subtlety.
 *
 * <p>{@code weights} is indexed by {@link NightEvent#ordinal()} (canonical order); an entry {@code <= 0}
 * means the event is disabled/excluded from the draw.
 */
public final class EventSchedule {
    private EventSchedule() {
    }

    /** Unsigned 64-bit roll = first 8 bytes of SHA-256("seed:day:nightevent:vVER[:reroll:stream]"). */
    static long roll(long seed, long day, int stream, int version) {
        String key = seed + ":" + day + ":nightevent:v" + version + (stream == 0 ? "" : ":reroll:" + stream);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 8).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // present on every JVM
        }
    }

    /** Weighted pick over the enabled events (weight &gt; 0), in canonical order, using an unsigned roll. */
    static NightEvent weightedPick(long roll64, int[] weights) {
        long total = 0;
        for (NightEvent e : NightEvent.values()) {
            int w = weights[e.ordinal()];
            if (w > 0) {
                total += w;
            }
        }
        if (total <= 0) {
            return NightEvent.ORDINARY;
        }
        long target = Long.remainderUnsigned(roll64, total);
        long acc = 0;
        for (NightEvent e : NightEvent.values()) {
            int w = weights[e.ordinal()];
            if (w <= 0) {
                continue;
            }
            acc += w;
            if (target < acc) {
                return e;
            }
        }
        return NightEvent.ORDINARY;
    }

    /** The raw (pre no-repeat) pick for a day. Days before {@code firstEventDay} are always ORDINARY. */
    static NightEvent rawPick(long seed, long day, int[] weights, int firstEventDay, int version) {
        if (day < firstEventDay) {
            return NightEvent.ORDINARY;
        }
        return weightedPick(roll(seed, day, 0, version), weights);
    }

    /**
     * The public schedule entry point: the event for {@code day}, applying the optional no-repeat rule
     * (event(d) != rawPick(d-1)) via bounded reroll sub-streams. O(1), memoryless — the website can
     * compute any single far-future day the same way.
     */
    public static NightEvent eventFor(long seed, long day, int[] weights, int firstEventDay,
                                      boolean noRepeat, int version) {
        NightEvent r = rawPick(seed, day, weights, firstEventDay, version);
        if (!noRepeat || day <= firstEventDay || r == NightEvent.ORDINARY) {
            return r;
        }
        NightEvent prev = rawPick(seed, day - 1, weights, firstEventDay, version);
        if (r != prev) {
            return r;
        }
        for (int s = 1; s <= 8; s++) {
            NightEvent e = weightedPick(roll(seed, day, s, version), weights);
            if (e != prev) {
                return e;
            }
        }
        return r; // all rerolls collided (extremely unlikely) — accept the repeat
    }
}
