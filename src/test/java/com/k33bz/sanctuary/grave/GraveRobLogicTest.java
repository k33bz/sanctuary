package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraveRobLogicTest {

    @Test
    void eligible_happyPath() {
        // enabled, not owner, wild, unlooted, night (nightOnly), aged past window
        assertTrue(GraveRobLogic.eligible(true, false, false, false, true, true, 25.0, 24.0));
    }

    @Test
    void eligible_blockedByEachGate() {
        assertFalse(GraveRobLogic.eligible(false, false, false, false, true, true, 25, 24), "disabled");
        assertFalse(GraveRobLogic.eligible(true, true, false, false, true, true, 25, 24), "own grave");
        assertFalse(GraveRobLogic.eligible(true, false, true, false, true, true, 25, 24), "in graveyard");
        assertFalse(GraveRobLogic.eligible(true, false, false, true, true, true, 25, 24), "already looted");
        assertFalse(GraveRobLogic.eligible(true, false, false, false, true, false, 25, 24), "day + nightOnly");
        assertFalse(GraveRobLogic.eligible(true, false, false, false, true, true, 23.9, 24), "too fresh");
    }

    @Test
    void eligible_nightOnlyOff_allowsDay() {
        assertTrue(GraveRobLogic.eligible(true, false, false, false, false, false, 25, 24),
                "nightOnly off should permit a daytime rob");
    }

    @Test
    void eligible_exactlyAtWindow() {
        assertTrue(GraveRobLogic.eligible(true, false, false, false, false, true, 24.0, 24.0),
                ">= window boundary is eligible");
    }

    @Test
    void fate_alwaysShattersWhenYieldZero() {
        Random rng = new Random(1);
        for (int i = 0; i < 200; i++) {
            assertEquals(GraveRobLogic.Fate.SHATTER, GraveRobLogic.fate(rng, 0.0, 0.5));
        }
    }

    @Test
    void fate_neverShattersAndNeverDamagesAtExtremes() {
        Random rng = new Random(2);
        for (int i = 0; i < 200; i++) {
            assertEquals(GraveRobLogic.Fate.YIELD_INTACT, GraveRobLogic.fate(rng, 1.0, 0.0),
                    "full yield, zero damage => always intact");
        }
    }

    @Test
    void fate_distributionRoughlyMatchesFractions() {
        Random rng = new Random(42);
        int shatter = 0, intact = 0, damaged = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            switch (GraveRobLogic.fate(rng, 0.60, 0.25)) {
                case SHATTER -> shatter++;
                case YIELD_INTACT -> intact++;
                case YIELD_DAMAGED -> damaged++;
            }
        }
        // ~40% shatter; of the ~60% yielded, ~25% damaged (~15% of total) and ~45% intact.
        assertTrue(Math.abs(shatter / (double) n - 0.40) < 0.03, "shatter ~40%: " + shatter);
        assertTrue(Math.abs(damaged / (double) n - 0.15) < 0.03, "damaged ~15%: " + damaged);
        assertTrue(Math.abs(intact / (double) n - 0.45) < 0.03, "intact ~45%: " + intact);
    }

    @Test
    void wraithRises_boundsHold() {
        Random rng = new Random(7);
        int rises = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            if (GraveRobLogic.wraithRises(rng, 0.35)) {
                rises++;
            }
        }
        assertTrue(Math.abs(rises / (double) n - 0.35) < 0.03, "rises ~35%: " + rises);
    }

    @Test
    void damageValue_zeroForNonDamageable() {
        Random rng = new Random(3);
        assertEquals(0, GraveRobLogic.damageValue(rng, 0));
        assertEquals(0, GraveRobLogic.damageValue(rng, 1));
    }

    @Test
    void damageValue_inHalfToNinetyBandAndNeverBreaks() {
        Random rng = new Random(9);
        int max = 250; // e.g. an iron tool
        for (int i = 0; i < 500; i++) {
            int d = GraveRobLogic.damageValue(rng, max);
            assertTrue(d >= max / 2 - 1 && d <= max - 1, "damage in band, never fully broken: " + d);
        }
    }
}
