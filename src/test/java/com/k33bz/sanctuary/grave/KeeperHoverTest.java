package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the keeper hover-bob math (0.8.3.3): a gentle, bounded sine bob centered on a
 * small base lift above the yard floor — hovers just off the ground, never floats high.
 */
class KeeperHoverTest {

    private static final int FLOOR = 63;

    @Test
    void hoverStaysWithinASmallBandJustAboveTheFloor() {
        double base = FLOOR + KeeperHover.BASE_LIFT;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (long t = 0; t < 400; t++) {
            double y = KeeperHover.hoverY(FLOOR, t, 0.0);
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        // Bounded: never dips below base-amplitude, never rises above base+amplitude.
        assertTrue(min >= base - KeeperHover.AMPLITUDE - 1e-9, "never dips below the bob floor");
        assertTrue(max <= base + KeeperHover.AMPLITUDE + 1e-9, "never rises above the bob ceiling");
        // "Just above the ground": the whole band sits within a fraction of a block of the floor,
        // not high in the air.
        assertTrue(max - FLOOR < 0.4, "the keeper hovers just above the floor, not high up");
        assertTrue(min - FLOOR > 0.0, "the keeper stays above the floor, never clipping into it");
    }

    @Test
    void bobIsCenteredOnTheBaseLift() {
        // Average over a full period is the base lift (sine has zero mean).
        double sum = 0;
        int n = (int) KeeperHover.PERIOD_TICKS;
        for (long t = 0; t < n; t++) {
            sum += KeeperHover.hoverY(FLOOR, t, 0.0);
        }
        assertEquals(FLOOR + KeeperHover.BASE_LIFT, sum / n, 0.01,
                "the bob averages to the base lift over a period");
    }

    @Test
    void bobMovesOverTime() {
        // It actually bounces: the quarter-period value differs from the start value.
        double y0 = KeeperHover.hoverY(FLOOR, 0, 0.0);
        double yQuarter = KeeperHover.hoverY(FLOOR, (long) (KeeperHover.PERIOD_TICKS / 4), 0.0);
        assertTrue(Math.abs(yQuarter - y0) > 0.05, "the keeper visibly bobs between tick samples");
    }

    @Test
    void phaseDesyncsKeepers() {
        // Two keepers with different phases are at different heights at the same tick.
        double a = KeeperHover.hoverY(FLOOR, 10, 0.0);
        double b = KeeperHover.hoverY(FLOOR, 10, Math.PI);
        assertTrue(Math.abs(a - b) > 0.05, "distinct phases desync the bob so keepers don't lockstep");
    }
}
