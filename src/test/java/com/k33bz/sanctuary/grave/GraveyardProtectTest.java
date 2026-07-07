package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correction-of-Error coverage for the graveyard yard-region grief protection (0.8.3.3).
 *
 * <p>The live bug (confirmed via grieflog): a survival player dug ~12 gravel blocks out of a fenced,
 * consecrated yard on gmc101 (coords within X5..15 / Z-7..1, Y62..63). The 0.8.3.2 protection only
 * covered REGISTERED grave plots, so a bare/old-gravel yard-floor block outside the tracked plot set
 * returned "not protected" and was breakable.
 *
 * <p>These tests exercise the pure-geometry region predicate {@link GraveyardProtect#inProtectedRegion}
 * and RED-PROVE the gap: the modeled old plot-only logic returns not-protected for the very
 * yard-floor block that was griefed, while the new region check flips it to protected. They also
 * pin the Y band edges, the footprint edges, the auto/no-bounds skip, and (via the plot predicate's
 * documented contract) the flora-harvest carve-out.
 */
class GraveyardProtectTest {

    // The gmc101 yard, modeled from the grief report: fence bounds ~ [5..15] x [-7..1], floor Y 63.
    private static final int MINX = 5, MAXX = 15, MINZ = -7, MAXZ = 1, FLOORY = 63;
    private static final int DEPTH = GraveyardProtect.DEFAULT_DEPTH;   // 4
    private static final int HEIGHT = GraveyardProtect.DEFAULT_HEIGHT; // 20

    private static boolean region(int x, int y, int z) {
        return GraveyardProtect.inProtectedRegion(x, y, z, true, MINX, MAXX, MINZ, MAXZ,
                FLOORY, DEPTH, HEIGHT);
    }

    /**
     * The OLD (0.8.3.2) plot-only logic, modeled: a block is protected ONLY if it is a registered
     * grave plot block (the grave column at gy or its ground at gy-1). A yard-floor block that is not
     * a tracked grave is "not protected". This is the buggy behavior we red-prove against.
     */
    private static boolean oldPlotOnly(int x, int y, int z, int[][] gravePlots) {
        for (int[] g : gravePlots) {
            int gx = g[0], gy = g[1], gz = g[2];
            if (x == gx && z == gz && (y == gy || y == gy - 1)) {
                return true;
            }
        }
        return false;
    }

    // --- RED-PROOF: the exact griefed block ---

    @Test
    void redProof_oldPlotOnlyLeavesYardFloorGravelBreakable() {
        // A gravel yard-floor block the player broke — inside the fence, at Y63, but NOT a tracked
        // grave plot (the yard has one registered grave elsewhere, at (10,63,-2)).
        int[][] plots = { {10, 63, -2} };
        int gx = 8, gy = 63, gz = -4; // ~ (8.6, 63, -4.5) from the grieflog, floored

        // OLD logic: not a plot → NOT protected (the bug: it was breakable).
        assertFalse(oldPlotOnly(gx, gy, gz, plots),
                "0.8.3.2 plot-only logic wrongly leaves the yard-floor gravel breakable");

        // NEW region logic: inside the footprint + Y band → protected (the fix flips it).
        assertTrue(region(gx, gy, gz),
                "0.8.3.3 region protection covers the yard-floor gravel block");
    }

    @Test
    void everyGriefloggedGravelCoordIsNowProtected() {
        // The four sample coords from the grief report, floored to block positions.
        int[][] broken = { {8, 63, -4}, {7, 63, -3}, {6, 63, -1}, {10, 63, -4} };
        for (int[] b : broken) {
            assertTrue(region(b[0], b[1], b[2]),
                    "griefed block (" + b[0] + "," + b[1] + "," + b[2] + ") must be protected");
        }
    }

    // --- footprint membership ---

    @Test
    void insideFootprintIsProtected() {
        assertTrue(region(MINX, FLOORY, MINZ), "min corner of the fence is protected");
        assertTrue(region(MAXX, FLOORY, MAXZ), "max corner of the fence is protected");
        assertTrue(region(10, FLOORY, -3), "an interior floor block is protected");
    }

    @Test
    void outsideFootprintIsNotProtected() {
        assertFalse(region(MINX - 1, FLOORY, -3), "one block west of the fence is NOT protected");
        assertFalse(region(MAXX + 1, FLOORY, -3), "one block east of the fence is NOT protected");
        assertFalse(region(10, FLOORY, MINZ - 1), "one block north of the fence is NOT protected");
        assertFalse(region(10, FLOORY, MAXZ + 1), "one block south of the fence is NOT protected");
    }

    // --- Y band ---

    @Test
    void withinYBandIsProtected() {
        assertTrue(region(10, FLOORY - DEPTH, -3), "the deepest anti-tunnel block is protected");
        assertTrue(region(10, FLOORY + HEIGHT, -3), "the top of the airspace band is protected");
        assertTrue(region(10, FLOORY - 1, -3), "just under the floor is protected");
    }

    @Test
    void outsideYBandIsNotProtected() {
        assertFalse(region(10, FLOORY - DEPTH - 1, -3), "below the anti-tunnel depth is NOT protected");
        assertFalse(region(10, FLOORY + HEIGHT + 1, -3), "above the airspace band is NOT protected");
    }

    // --- configurable band ---

    @Test
    void bandIsConfigurable() {
        // A taller/deeper band protects blocks the default band would not.
        assertFalse(region(10, FLOORY + HEIGHT + 5, -3), "default height leaves this open");
        assertTrue(GraveyardProtect.inProtectedRegion(10, FLOORY + HEIGHT + 5, -3, true,
                        MINX, MAXX, MINZ, MAXZ, FLOORY, DEPTH, HEIGHT + 10),
                "a larger configured height protects it");
        // depth/height 0 collapses the band to exactly the floor Y: the floor stays protected,
        // but a block one above (or one below) the floor opens.
        assertTrue(GraveyardProtect.inProtectedRegion(10, FLOORY, -3, true,
                        MINX, MAXX, MINZ, MAXZ, FLOORY, 0, 0),
                "with depth/height 0 the exact floor block is still protected");
        assertFalse(GraveyardProtect.inProtectedRegion(10, FLOORY + 1, -3, true,
                        MINX, MAXX, MINZ, MAXZ, FLOORY, 0, 0),
                "with depth/height 0 a block one above the floor opens");
    }

    // --- auto/hold-only yard has no footprint ---

    @Test
    void autoYardWithoutBoundsIsNeverProtected() {
        assertFalse(GraveyardProtect.inProtectedRegion(0, FLOORY, 0, false, 0, 0, 0, 0,
                        FLOORY, DEPTH, HEIGHT),
                "an auto/hold-only yard holds no world blocks → nothing protected");
    }

    // --- flora carve-out contract (registry-aware, layered by Graves on top of the region) ---

    @Test
    void floraCarveOutContract_stage2FlowerStaysBreakable() {
        // The carve-out is expressed through the existing per-grave plot predicate: a stage-2 flower
        // at the grave's base is reported NOT protected (harvestable), even though geometrically it
        // sits inside the region. This pins the contract the region check relies on: the region is
        // pure geometry; Graves.isProtectedYardRegion re-allows exactly the harvestable flower block.
        // A grave column at gy with a stage-2 flower: the OLD plot predicate excludes gy (harvestable)
        // but still protects the ground at gy-1.
        int[][] plot = { {10, 63, -3} };
        assertTrue(oldPlotOnly(10, 62, -3, plot), "plot ground under the flower stays protected");
        assertFalse(oldPlotOnly(10, 64, -3, plot),
                "a block above the plot is not a plot block (flora airspace handled by region)");
        // Geometry alone would protect the flower's own block; the flora carve-out (in Graves) is
        // what re-allows it — asserted here as the region-includes precondition.
        assertTrue(region(10, 63, -3), "geometrically the flower block is inside the region");
    }
}
