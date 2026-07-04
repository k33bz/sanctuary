package com.k33bz.sanctuary.anchor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnchorFuelTest {

    private static final long H = (long) AnchorFuel.TICKS_PER_HOUR; // 72000 ticks == 1 hour

    // --- exempt / active / hoursLeft ---

    @Test
    void exemptAnchorsNeverDecay() {
        assertTrue(AnchorFuel.isExempt(0L));
        assertTrue(AnchorFuel.isExempt(-1L));
        assertFalse(AnchorFuel.isExempt(1L));
        assertTrue(AnchorFuel.isActive(0L, 999_999L));                      // exempt: always active
        assertEquals(Double.MAX_VALUE, AnchorFuel.hoursLeft(0L, 500L), 0.0); // exempt: infinite fuel
    }

    @Test
    void activeUntilExpiryTickPasses() {
        long now = 10_000L;
        assertTrue(AnchorFuel.isActive(now + 1, now));   // one tick of fuel left
        assertFalse(AnchorFuel.isActive(now, now));      // expiry == now is spent (dormant)
        assertFalse(AnchorFuel.isActive(now - 1, now));
    }

    @Test
    void hoursLeftScalesAndFloorsAtZero() {
        long now = 100_000L;
        assertEquals(2.0, AnchorFuel.hoursLeft(now + 2 * H, now), 1e-9);
        assertEquals(0.0, AnchorFuel.hoursLeft(now, now), 1e-9);      // exactly spent
        assertEquals(0.0, AnchorFuel.hoursLeft(now - H, now), 1e-9);  // past expiry never goes negative
    }

    // --- feed math: base, cap, fed expiry, accepted count ---

    @Test
    void activeAnchorExtendsFromItsExpiry() {
        long now = 1_000_000L;
        long expiry = now + 5 * H;               // 5h of fuel remaining
        // feed one 2.5h emerald, generous cap: base is the future expiry, so it stacks to 7.5h
        long fed = AnchorFuel.fedExpiry(expiry, now, 2.5, 1, 10_000);
        assertEquals(expiry + (long) (2.5 * H), fed);
        assertEquals(expiry, AnchorFuel.baseTick(expiry, now)); // extends, doesn't reset
    }

    @Test
    void spentAnchorRefuelsFromNow() {
        long now = 1_000_000L;
        long expiry = now - 3 * H;               // already dormant
        assertEquals(now, AnchorFuel.baseTick(expiry, now)); // starts fresh at now
        long fed = AnchorFuel.fedExpiry(expiry, now, 24.0, 1, 10_000); // one dragon-egg-ish feed
        assertEquals(now + 24 * H, fed);
    }

    @Test
    void feedRespectsBankCap() {
        long now = 0L;
        double maxHours = 100.0;
        long cap = AnchorFuel.capTick(now, maxHours);
        assertEquals(100 * H, cap);
        // feed 200h worth into an empty anchor -> clamped to the 100h cap
        long fed = AnchorFuel.fedExpiry(0L, now, 100.0, 2, maxHours);
        assertEquals(cap, fed);
        // under the cap, nothing is clamped
        assertEquals(50 * H, AnchorFuel.fedExpiry(0L, now, 50.0, 1, maxHours));
    }

    @Test
    void acceptedTakesEverythingWhenUnderCap() {
        long now = 0L;
        // 3 emeralds (2.5h each) into an empty anchor, huge cap: all 3 consumed
        long base = AnchorFuel.baseTick(0L, now);
        long fed = AnchorFuel.fedExpiry(0L, now, 2.5, 3, 10_000);
        assertEquals(3, AnchorFuel.acceptedItems(fed, base, 2.5, 3));
    }

    @Test
    void acceptedIsPartialWhenCapTruncatesTheStack() {
        long now = 0L;
        double maxHours = 10.0; // cap at 10h
        // offer 8 emeralds (2.5h each = 20h) into an empty anchor: only ~10h/20h fits -> ~half
        long base = AnchorFuel.baseTick(0L, now);
        long fed = AnchorFuel.fedExpiry(0L, now, 2.5, 8, maxHours);
        assertEquals(10 * H, fed);                          // clamped to cap
        assertEquals(4, AnchorFuel.acceptedItems(fed, base, 2.5, 8)); // ceil(8 * 10/20) = 4
    }

    @Test
    void acceptedNeverBelowOneNorAboveCount() {
        long now = 0L;
        // a sliver of headroom still consumes at least one item
        long base = AnchorFuel.baseTick(0L, now);
        long fed = base + 1; // barely past base
        assertEquals(1, AnchorFuel.acceptedItems(fed, base, 2.5, 5));
    }
}
