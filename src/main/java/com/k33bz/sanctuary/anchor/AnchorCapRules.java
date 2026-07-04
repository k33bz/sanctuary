package com.k33bz.sanctuary.anchor;

/**
 * Pure, game-independent progression rules for a player's anchor cap.
 * No Minecraft types referenced here, so it can be unit-tested without a running game.
 *
 * <p>Everyone starts at {@code base}; each raise demands a Warden kill of the next tier up (any
 * Warden, then Feral+, Savage+, ... to Nightmare = 4), bounded by {@code max}. {@link PlayerProgress}
 * keeps the persisted uuid→cap map and calls these helpers for the actual decisions, so the
 * extracted logic returns exactly what the inline code did.
 */
public final class AnchorCapRules {
    private AnchorCapRules() {
    }

    /** The highest Warden tier (Nightmare). Raises past this still only need this tier. */
    public static final int MAX_TIER = 4;

    /** No cap change. */
    public static final int NO_RAISE = -1;

    /**
     * Warden tier required for the NEXT raise from {@code currentCap}: the first raise (cap == base)
     * takes any Warden (tier 0), each following raise the next tier up, clamped at {@link #MAX_TIER}.
     */
    public static int requiredTierForRaise(int currentCap, int base) {
        int raises = currentCap - base;
        return Math.min(MAX_TIER, raises);
    }

    /**
     * The cap after a Warden kill of {@code killTier}, or {@link #NO_RAISE} if nothing changes:
     * already at (or past) {@code max}, or the kill wasn't a high enough tier for the next raise.
     * A successful raise is always exactly one step ({@code currentCap + 1}).
     */
    public static int raisedCap(int currentCap, int killTier, int base, int max) {
        if (currentCap >= max || killTier < requiredTierForRaise(currentCap, base)) {
            return NO_RAISE;
        }
        return currentCap + 1;
    }
}
