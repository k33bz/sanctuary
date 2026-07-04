package com.k33bz.sanctuary;

/**
 * Pure string/int parsing for Feral Egg names and their star-quality lore line.
 * No Minecraft types referenced here, so it can be unit-tested without a running game.
 *
 * <p>{@link FeralEgg} keeps the {@code Component}/{@code ItemStack} plumbing and calls these
 * helpers for the actual character math, so the extracted logic returns exactly what the inline
 * code did.
 */
public final class FeralEggNames {
    private FeralEggNames() {
    }

    /** The plain custom name every Feral Egg carries (the tier lives in the name's color). */
    public static final String NAME = "Feral Egg";

    /** Star glyph Minecraft's font renders crisply (same reasoning as the threat skulls). */
    public static final char STAR = '★';

    /** Highest star quality a bloodline reaches. A line with more stars than this is not valid. */
    public static final int MAX_STARS = 5;

    /**
     * Parse a Feral Egg display name into its star count: {@code "Feral Egg"} → 0,
     * {@code "Feral Egg ★★★"} → 3. Returns −1 for anything that isn't a Feral Egg name, has a
     * malformed star suffix, or claims more than {@link #MAX_STARS} stars.
     */
    public static int parseStars(String displayName) {
        if (displayName == null) {
            return -1;
        }
        if (displayName.equals(NAME)) {
            return 0;
        }
        String prefix = NAME + " ";
        if (!displayName.startsWith(prefix)) {
            return -1;
        }
        String suffix = displayName.substring(prefix.length());
        if (suffix.isEmpty() || !isAllStars(suffix)) {
            return -1;
        }
        int stars = suffix.length();
        return stars <= MAX_STARS ? stars : -1;
    }

    /** True when every character in {@code s} is the star glyph (empty string is not a star line). */
    public static boolean isAllStars(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != STAR) {
                return false;
            }
        }
        return true;
    }

    /**
     * The star count carried by a single tooltip lore line, matching {@link FeralEgg#starsOf}'s
     * rule: a run of 1..{@link #MAX_STARS} star glyphs and nothing else counts; any other line
     * (prose, empty) contributes 0.
     */
    public static int starsFromLoreLine(String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_STARS || !isAllStars(line)) {
            return 0;
        }
        return line.length();
    }
}
