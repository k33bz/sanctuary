package com.k33bz.sanctuary.anchor;

/**
 * Pure, game-independent fuel/upkeep math for placed sanctuary anchors.
 * No Minecraft types referenced here, so it can be unit-tested without a running game.
 *
 * <p>Fuel is measured as a game-time expiry tick. An anchor with {@code expiry <= 0} is EXEMPT
 * (admin/eternal) and never decays. {@link AnchorState.PlacedAnchor} and {@link AnchorUpkeep} keep
 * the block/entity plumbing and call these helpers for the arithmetic, so the extracted logic
 * returns exactly what the inline code did.
 */
public final class AnchorFuel {
    private AnchorFuel() {
    }

    /** Game ticks per real hour (20 ticks/second × 3600). */
    public static final double TICKS_PER_HOUR = 72000.0;

    /** An anchor with a non-positive expiry is exempt/eternal (never decays). */
    public static boolean isExempt(long expiry) {
        return expiry <= 0L;
    }

    /** Active = exempt, or fuel not yet spent ({@code expiry} still in the future). */
    public static boolean isActive(long expiry, long now) {
        return isExempt(expiry) || expiry > now;
    }

    /** Hours of fuel remaining ({@code Double.MAX_VALUE} if exempt, never negative otherwise). */
    public static double hoursLeft(long expiry, long now) {
        return isExempt(expiry) ? Double.MAX_VALUE : Math.max(0.0, (expiry - now) / TICKS_PER_HOUR);
    }

    /** The bank ceiling: no anchor may hold more than {@code maxFuelHours} of fuel past {@code now}. */
    public static long capTick(long now, double maxFuelHours) {
        return now + (long) (maxFuelHours * TICKS_PER_HOUR);
    }

    /** The tick fuel is measured forward from: an active anchor extends its expiry, a spent one starts at now. */
    public static long baseTick(long expiry, long now) {
        return Math.max(expiry, now);
    }

    /**
     * The new expiry after feeding {@code count} items each worth {@code worth} hours, clamped to
     * the bank cap. Mirrors {@code feed}: {@code min(cap, base + worth*count*TICKS_PER_HOUR)}.
     */
    public static long fedExpiry(long expiry, long now, double worth, int count, double maxFuelHours) {
        long base = baseTick(expiry, now);
        long cap = capTick(now, maxFuelHours);
        return Math.min(cap, base + (long) (worth * count * TICKS_PER_HOUR));
    }

    /**
     * How many of the {@code count} offered items are actually consumed once the bank cap is hit:
     * the fraction of the offered fuel that fit, rounded up, then clamped to {@code [1, count]}.
     * Mirrors {@code feed}'s {@code accepted} computation. Only meaningful when {@code fed > base}
     * (the caller rejects a no-op feed before reaching here).
     */
    public static int acceptedItems(long fedExpiry, long baseTick, double worth, int count) {
        int accepted = (int) Math.ceil(count * Math.min(1.0,
                (fedExpiry - baseTick) / Math.max(1.0, worth * count * TICKS_PER_HOUR)));
        return Math.max(1, Math.min(count, accepted));
    }
}
