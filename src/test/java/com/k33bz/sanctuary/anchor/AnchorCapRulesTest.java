package com.k33bz.sanctuary.anchor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnchorCapRulesTest {

    private static final int BASE = 1;
    private static final int MAX = 5;

    @Test
    void requiredTierClimbsWithEachRaiseThenClampsAtNightmare() {
        assertEquals(0, AnchorCapRules.requiredTierForRaise(1, BASE)); // first raise: any Warden
        assertEquals(1, AnchorCapRules.requiredTierForRaise(2, BASE));
        assertEquals(2, AnchorCapRules.requiredTierForRaise(3, BASE));
        assertEquals(3, AnchorCapRules.requiredTierForRaise(4, BASE));
        assertEquals(4, AnchorCapRules.requiredTierForRaise(5, BASE)); // Nightmare
        assertEquals(4, AnchorCapRules.requiredTierForRaise(9, BASE)); // stays clamped past the top
    }

    @Test
    void raiseSucceedsOnlyWithSufficientTier() {
        // At base (cap 1) the first raise needs tier 0 — any Warden clears it.
        assertEquals(2, AnchorCapRules.raisedCap(1, 0, BASE, MAX));
        assertEquals(2, AnchorCapRules.raisedCap(1, 4, BASE, MAX));  // higher tier also fine
        // At cap 3 the next raise needs tier 2.
        assertEquals(AnchorCapRules.NO_RAISE, AnchorCapRules.raisedCap(3, 1, BASE, MAX)); // too low
        assertEquals(4, AnchorCapRules.raisedCap(3, 2, BASE, MAX));                       // exactly enough
        assertEquals(4, AnchorCapRules.raisedCap(3, 3, BASE, MAX));                       // more than enough
    }

    @Test
    void raiseIsAlwaysExactlyOneStep() {
        assertEquals(3, AnchorCapRules.raisedCap(2, 4, BASE, MAX));
    }

    @Test
    void raiseStopsAtMax() {
        assertEquals(AnchorCapRules.NO_RAISE, AnchorCapRules.raisedCap(5, 4, BASE, MAX)); // at max
        assertEquals(AnchorCapRules.NO_RAISE, AnchorCapRules.raisedCap(6, 4, BASE, MAX)); // past max (admin override)
    }
}
