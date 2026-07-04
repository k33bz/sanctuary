package com.k33bz.sanctuary.grave;

/**
 * Pure, game-independent timing rules for a grave's real-time lifecycle.
 * No Minecraft types referenced here, so it can be unit-tested without a running game.
 *
 * <p>Everything is millis-in / config-value-in, boolean-out: the caller ({@link Graves}) passes
 * {@code now} and {@code diedAtMs} (both {@code System.currentTimeMillis()} readings) plus the
 * relevant flags and threshold from config, and gets back the same decision the inline code made.
 */
public final class GraveLifecycle {
    private GraveLifecycle() {
    }

    private static final double MS_PER_HOUR = 3_600_000.0;
    private static final double MS_PER_DAY = 86_400_000.0;

    /** Real hours elapsed since death. */
    public static double elapsedHours(long nowMs, long diedAtMs) {
        return (nowMs - diedAtMs) / MS_PER_HOUR;
    }

    /** Real days elapsed since death. */
    public static double elapsedDays(long nowMs, long diedAtMs) {
        return (nowMs - diedAtMs) / MS_PER_DAY;
    }

    /**
     * A grave turns public (anyone may loot) once it has sat unlooted for {@code publicHours}.
     * Looted graves are never public — they hold nothing to claim.
     */
    public static boolean isPublic(long nowMs, long diedAtMs, boolean looted, double publicHours) {
        return !looted && elapsedHours(nowMs, diedAtMs) >= publicHours;
    }

    /**
     * A looted stone out in the wild (not in a graveyard) crumbles after {@code memorialDecayDays}.
     * Cemeteries keep their history; only wild litter decays. Mirrors the {@code decayDays > 0}
     * gate the caller already applies, so a non-positive threshold never decays.
     */
    public static boolean isMemorialDecayDue(long nowMs, long diedAtMs, boolean looted,
                                             boolean inGraveyard, double memorialDecayDays) {
        if (memorialDecayDays <= 0) {
            return false;
        }
        return looted && !inGraveyard && elapsedDays(nowMs, diedAtMs) >= memorialDecayDays;
    }

    /**
     * A fresh grave still in the world (not yet in a graveyard, not keeper-held, not looted) drifts
     * to the nearest sanctuary graveyard after {@code driftHours}.
     */
    public static boolean isDriftDue(long nowMs, long diedAtMs, boolean looted, boolean inGraveyard,
                                     boolean heldByKeeper, double driftHours) {
        return !inGraveyard && !heldByKeeper && !looted && elapsedHours(nowMs, diedAtMs) >= driftHours;
    }
}
