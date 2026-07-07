package com.k33bz.sanctuary.grave;

/**
 * Pure, game-independent geometry for the consecrated-graveyard GRIEF-PROTECTION region (0.8.3.3).
 * No Minecraft types here, so it is unit-testable; the caller ({@link Graves}) supplies the yard's
 * fence bounds + floor Y and asks whether a candidate block lies in the protected volume.
 *
 * <p><b>Why this exists (the 0.8.3.2 scope gap):</b> plot protection ({@code
 * Graves.isProtectedGraveBlock}) only covered REGISTERED grave plots — a headstone + its plot
 * ground. It did NOT cover the general graveyard yard floor, nor OLD/unregistered graves (e.g.
 * pre-flora gravel graves that were never in the tracked grave set). A survival player therefore
 * dug ~12 gravel blocks straight out of a fenced, consecrated yard on gmc101. This region check
 * closes that gap.
 *
 * <p><b>Pure geometry, registry-independent by design:</b> ANY block whose column lies inside the
 * yard's XZ footprint, within a Y band from {@code depth} blocks below the floor up to {@code
 * height} blocks above it, is protected. There is deliberately NO dependency on whether that block
 * is a tracked grave — that is what makes it cover the bare floor, old graves, unregistered graves,
 * new plots, the fence, and the airspace alike. The ONLY carve-out is harvestable flora on graves
 * (handled by the caller, not here) so the intended "flowers/short grass on graves" harvest survives.
 *
 * <p>The footprint is the yard's fence bounds ({@code bMinX..bMaxX / bMinZ..bMaxZ}, inclusive block
 * columns; +1 on the max edge because a block at {@code bMaxX} still occupies the [bMaxX, bMaxX+1)
 * span). Auto/default (radius-0, no-bounds) yards have no physical footprint and are NOT protected
 * here — they hold no world blocks to grief.
 */
public final class GraveyardProtect {
    private GraveyardProtect() {
    }

    /** Default blocks BELOW the yard floor that stay protected (stops tunneling up from underneath). */
    public static final int DEFAULT_DEPTH = 4;

    /** Default blocks ABOVE the yard floor that stay protected (fence + headstones + airspace). */
    public static final int DEFAULT_HEIGHT = 20;

    /**
     * Whether a block position lies in the yard's protected region. Registry-independent: this is
     * pure AABB membership over the yard footprint and Y band. The flora carve-out is applied by the
     * caller AFTER this returns true.
     *
     * @param bx block X (integer block coord)
     * @param by block Y
     * @param bz block Z
     * @param hasBounds whether the yard has real fence bounds (an auto/default yard has none → false)
     * @param bMinX inclusive min fence X
     * @param bMaxX inclusive max fence X
     * @param bMinZ inclusive min fence Z
     * @param bMaxZ inclusive max fence Z
     * @param floorY the yard floor Y (the yard's {@code y})
     * @param depth blocks below the floor to protect (>= 0)
     * @param height blocks above the floor to protect (>= 0)
     */
    public static boolean inProtectedRegion(int bx, int by, int bz,
                                            boolean hasBounds, int bMinX, int bMaxX, int bMinZ, int bMaxZ,
                                            int floorY, int depth, int height) {
        if (!hasBounds) {
            return false; // no physical footprint (auto/hold-only yard) → nothing to protect here
        }
        if (by < floorY - depth || by > floorY + height) {
            return false;
        }
        return bx >= bMinX && bx <= bMaxX && bz >= bMinZ && bz <= bMaxZ;
    }
}
