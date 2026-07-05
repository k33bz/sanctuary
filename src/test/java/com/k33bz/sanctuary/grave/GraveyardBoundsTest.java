package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit coverage for the resize-graveyard validation (part d): accept a strictly-larger pen that
 * still contains all graves; reject a smaller/same pen; reject one that would strand a grave.
 */
class GraveyardBoundsTest {

    private static final GraveyardBounds.Rect BASE = new GraveyardBounds.Rect(0, 8, 0, 8); // 9x9

    @Test
    void acceptsLargerPenContainingAllGraves() {
        GraveyardBounds.Rect bigger = new GraveyardBounds.Rect(-2, 12, -2, 12); // 15x15
        List<double[]> graves = List.of(new double[]{4.5, 4.5}, new double[]{1.5, 7.5});
        assertEquals(GraveyardBounds.Result.OK, GraveyardBounds.validateResize(BASE, bigger, graves));
    }

    @Test
    void rejectsSmallerOrSamePen() {
        GraveyardBounds.Rect smaller = new GraveyardBounds.Rect(0, 6, 0, 6); // 7x7
        assertEquals(GraveyardBounds.Result.NOT_LARGER,
                GraveyardBounds.validateResize(BASE, smaller, List.of()));
        GraveyardBounds.Rect same = new GraveyardBounds.Rect(0, 8, 0, 8); // 9x9 identical area
        assertEquals(GraveyardBounds.Result.NOT_LARGER,
                GraveyardBounds.validateResize(BASE, same, List.of()));
    }

    @Test
    void rejectsResizeThatWouldStrandAGrave() {
        // larger overall, but shifted so a grave at the old far corner falls outside
        GraveyardBounds.Rect shifted = new GraveyardBounds.Rect(5, 25, 5, 25); // bigger area, moved
        List<double[]> graves = List.of(new double[]{1.5, 1.5}); // sits in the OLD yard, outside the new
        assertEquals(GraveyardBounds.Result.STRANDS_GRAVE,
                GraveyardBounds.validateResize(BASE, shifted, graves));
    }

    @Test
    void largerWithNoGravesIsFine() {
        GraveyardBounds.Rect bigger = new GraveyardBounds.Rect(0, 20, 0, 20);
        assertEquals(GraveyardBounds.Result.OK, GraveyardBounds.validateResize(BASE, bigger, null));
        assertEquals(GraveyardBounds.Result.OK, GraveyardBounds.validateResize(BASE, bigger, List.of()));
    }

    @Test
    void containmentTreatsBlockCellsInclusively() {
        // a grave centered at x=8.5 sits in block cell 8, which the rect [0..8] must contain
        GraveyardBounds.Rect bigger = new GraveyardBounds.Rect(0, 10, 0, 10);
        assertEquals(GraveyardBounds.Result.OK,
                GraveyardBounds.validateResize(BASE, bigger, List.of(new double[]{8.5, 8.5})));
    }
}
