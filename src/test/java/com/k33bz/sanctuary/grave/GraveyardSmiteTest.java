package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the smite zone-membership predicate (0.8.3): the fence bounds + horizontal
 * margin and the vertical Y band, plus the auto/default (no-bounds) radius-square case.
 */
class GraveyardSmiteTest {

    // A 9x9 fenced yard: bounds [0..8]x[0..8], center-ish Y = 64. Margin 10, Y band 8.
    private static final int MINX = 0, MAXX = 8, MINZ = 0, MAXZ = 8, YARDY = 64;
    private static final int MARGIN = 10, YBAND = 8;

    private static boolean inBounded(double x, double y, double z) {
        return GraveyardSmite.inZone(x, y, z, true, MINX, MAXX, MINZ, MAXZ, 4, 4, YARDY, MARGIN, YBAND);
    }

    @Test
    void insideFenceIsInZone() {
        assertTrue(inBounded(4.5, 64, 4.5), "yard center is in-zone");
        assertTrue(inBounded(0.5, 64, 0.5), "a fence corner is in-zone");
        assertTrue(inBounded(8.5, 64, 8.5), "the far corner is in-zone");
    }

    @Test
    void withinMarginIsInZone() {
        // margin 10 beyond the fence: bounds expand to [-10 .. 8+1+10 = 19]
        assertTrue(inBounded(-9.5, 64, 4.5), "just inside the -x margin");
        assertTrue(inBounded(18.5, 64, 4.5), "just inside the +x margin");
        assertTrue(inBounded(4.5, 64, 18.5), "just inside the +z margin");
    }

    @Test
    void beyondMarginIsOutOfZone() {
        assertFalse(inBounded(-11.0, 64, 4.5), "past the -x margin");
        assertFalse(inBounded(20.0, 64, 4.5), "past the +x margin");
        assertFalse(inBounded(4.5, 64, 20.0), "past the +z margin");
    }

    @Test
    void outsideYBandIsOutOfZone() {
        assertTrue(inBounded(4.5, 64 + YBAND, 4.5), "top of the Y band is in-zone");
        assertTrue(inBounded(4.5, 64 - YBAND, 4.5), "bottom of the Y band is in-zone");
        assertFalse(inBounded(4.5, 64 + YBAND + 1, 4.5), "above the Y band is out");
        assertFalse(inBounded(4.5, 64 - YBAND - 1, 4.5), "below the Y band is out");
    }

    // --- auto/default (no bounds): a margin-radius square around the keeper/center ---

    private static boolean inAuto(double x, double y, double z) {
        // no bounds; center (cx,cz) = (100, 100)
        return GraveyardSmite.inZone(x, y, z, false, 0, 0, 0, 0, 100, 100, YARDY, MARGIN, YBAND);
    }

    @Test
    void autoYardUsesRadiusSquareAroundCenter() {
        assertTrue(inAuto(100.5, 64, 100.5), "the keeper spot is in-zone");
        assertTrue(inAuto(100 - MARGIN, 64, 100.5), "within the -x margin radius");
        assertTrue(inAuto(100 + 1 + MARGIN, 64, 100.5), "within the +x margin radius");
        assertFalse(inAuto(100 - MARGIN - 1, 64, 100.5), "past the -x margin radius");
        assertFalse(inAuto(100.5, 64 + YBAND + 1, 100.5), "above the Y band is out");
    }

    @Test
    void hasBoundsDistinguishesRealYardsFromAuto() {
        assertTrue(GraveyardSmite.hasBounds(0, 8), "a real fenced yard has bounds");
        assertFalse(GraveyardSmite.hasBounds(0, 0), "an auto/default yard has no bounds");
    }
}
