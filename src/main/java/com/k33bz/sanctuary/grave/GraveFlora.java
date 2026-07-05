package com.k33bz.sanctuary.grave;

/**
 * Pure, game-independent mapping of a grave plot's ground block + flora by REAL grave age. No
 * Minecraft types here, so it is unit-testable; the caller ({@link Graves}) turns the returned
 * block ids into setblocks. Nature reclaims a plot over time:
 *
 * <ul>
 *   <li>fresh (age &lt; grassDays) — bare {@code podzol}, freshly turned earth</li>
 *   <li>age &ge; grassDays — {@code grass_block}, the lawn returns</li>
 *   <li>age &ge; flowerDays — {@code grass_block} + a flower blooms on top</li>
 * </ul>
 */
public final class GraveFlora {
    private GraveFlora() {
    }

    /** The ground block id for a plot at {@code ageDays} old. */
    public static String groundBlock(double ageDays, double grassDays, double flowerDays) {
        return ageDays >= grassDays ? "minecraft:grass_block" : "minecraft:podzol";
    }

    /** Whether a flower blooms on top of the plot at this age (implies a grass ground). */
    public static boolean hasFlower(double ageDays, double grassDays, double flowerDays) {
        return ageDays >= flowerDays && flowerDays >= grassDays;
    }

    /**
     * Pick the flower block id for a plot. {@code roll} is a uniform [0,1) draw: below
     * {@code witherRoseChance} yields the rare, hazardous wither rose; otherwise a common bloom
     * chosen by {@code pick} (also [0,1), split into three equal buckets). Deterministic given the
     * draws, so it is unit-testable.
     */
    public static String flowerBlock(double roll, double pick, double witherRoseChance) {
        if (roll < witherRoseChance) {
            return "minecraft:wither_rose";
        }
        if (pick < 1.0 / 3.0) {
            return "minecraft:lily_of_the_valley";
        }
        if (pick < 2.0 / 3.0) {
            return "minecraft:oxeye_daisy";
        }
        return "minecraft:white_tulip";
    }
}
