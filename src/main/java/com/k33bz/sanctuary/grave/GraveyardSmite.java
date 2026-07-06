package com.k33bz.sanctuary.grave;

/**
 * Pure, game-independent geometry for the Gravekeeper's smite zone (0.8.3). No Minecraft types here,
 * so it is unit-testable; {@link Gravekeeper} builds the AABB and finds targets, and asks this class
 * whether a candidate lies in the protected zone.
 *
 * <p>The zone is the yard's fence bounds ({@code bMinX..bMaxX / bMinZ..bMaxZ}) expanded horizontally
 * by {@code margin}, within a vertical band of {@code yBand} blocks around the yard's Y. For an
 * auto/default (radius-0, no-bounds) yard the zone is a square of half-width {@code margin} around
 * the keeper/yard center.
 */
public final class GraveyardSmite {
    private GraveyardSmite() {
    }

    /** Vertical half-height of the smite band around the yard Y (blocks). */
    public static final int Y_BAND = 8;

    /**
     * Whether a point is inside the smite zone. {@code hasBounds} = the yard has real fence bounds
     * (a consecrated yard); false = an auto/default yard, where the zone is a {@code margin}-radius
     * square around ({@code cx},{@code cz}). Coordinates are block/entity doubles.
     */
    public static boolean inZone(double px, double py, double pz,
                                 boolean hasBounds, int bMinX, int bMaxX, int bMinZ, int bMaxZ,
                                 int cx, int cz, int yardY, int margin, int yBand) {
        if (py < yardY - yBand || py > yardY + yBand) {
            return false;
        }
        if (hasBounds) {
            return px >= bMinX - margin && px <= bMaxX + 1 + margin
                    && pz >= bMinZ - margin && pz <= bMaxZ + 1 + margin;
        }
        // Auto/default yard: a margin-radius square around the center.
        return px >= cx - margin && px <= cx + 1 + margin
                && pz >= cz - margin && pz <= cz + 1 + margin;
    }

    /** Whether a yard has real fence bounds (vs an auto/default radius-0 yard). */
    public static boolean hasBounds(int bMinX, int bMaxX) {
        return bMaxX > bMinX;
    }
}
