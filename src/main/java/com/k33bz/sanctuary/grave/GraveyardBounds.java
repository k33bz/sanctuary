package com.k33bz.sanctuary.grave;

/**
 * Pure, game-independent validation for RESIZING a graveyard (re-consecrating the effigy with a
 * larger fence). No Minecraft types here, so it is unit-testable. The caller ({@link GraveyardRitual})
 * supplies the old fence bounds, the new flood-filled bounds, and the (x,z) of every grave already
 * resting in the yard; this decides whether the resize is allowed.
 *
 * <p>Rules: the new bounds must be STRICTLY LARGER in area (a genuine expansion, not a same-size or
 * smaller re-run) AND must fully contain every existing grave. Otherwise the resize is rejected
 * (a smaller pen, or one that would strand a grave outside the fence, is refused so no grave is
 * ever lost or orphaned).
 */
public final class GraveyardBounds {
    private GraveyardBounds() {
    }

    /** Inclusive integer rectangle in x/z (block coords). */
    public record Rect(int minX, int maxX, int minZ, int maxZ) {
        public long area() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX + 1 && z >= minZ && z <= maxZ + 1;
        }
    }

    /** Why a resize was rejected (or {@link #OK}). */
    public enum Result {
        OK, NOT_LARGER, STRANDS_GRAVE
    }

    /**
     * Decide whether re-consecrating with {@code next} bounds is a valid resize of {@code current},
     * given the block positions of every grave already in the yard.
     */
    public static Result validateResize(Rect current, Rect next, java.util.List<double[]> gravePositions) {
        if (next.area() <= current.area()) {
            return Result.NOT_LARGER;
        }
        if (gravePositions != null) {
            for (double[] pos : gravePositions) {
                if (pos == null || pos.length < 2) {
                    continue;
                }
                if (!next.contains(pos[0], pos[1])) {
                    return Result.STRANDS_GRAVE;
                }
            }
        }
        return Result.OK;
    }
}
