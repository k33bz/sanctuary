package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraveLifecycleTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    private static final long BASE = 1_000_000_000_000L; // an arbitrary fixed "diedAt"

    // --- isPublic: unlooted, elapsed >= publicHours ---

    @Test
    void publicOnlyOnceUnlootedThresholdMet() {
        // 48h threshold; just before, exactly at, just after
        assertFalse(GraveLifecycle.isPublic(BASE + 48 * HOUR - 1, BASE, false, 48));
        assertTrue(GraveLifecycle.isPublic(BASE + 48 * HOUR, BASE, false, 48));      // boundary is inclusive
        assertTrue(GraveLifecycle.isPublic(BASE + 49 * HOUR, BASE, false, 48));
    }

    @Test
    void lootedGraveIsNeverPublic() {
        assertFalse(GraveLifecycle.isPublic(BASE + 1000 * HOUR, BASE, true, 48));
    }

    @Test
    void freshGraveIsNotPublic() {
        assertFalse(GraveLifecycle.isPublic(BASE, BASE, false, 48));
    }

    // --- memorial decay: looted && !inGraveyard && elapsedDays >= decayDays, gated by decayDays > 0 ---

    @Test
    void memorialDecayNeedsLootedWildAndThreshold() {
        // 7 day threshold
        assertFalse(GraveLifecycle.isMemorialDecayDue(BASE + 7 * DAY - 1, BASE, true, false, 7));
        assertTrue(GraveLifecycle.isMemorialDecayDue(BASE + 7 * DAY, BASE, true, false, 7)); // at boundary
        assertTrue(GraveLifecycle.isMemorialDecayDue(BASE + 8 * DAY, BASE, true, false, 7));
    }

    @Test
    void memorialDecayExcludesUnlootedAndCemeteryGraves() {
        long late = BASE + 100 * DAY;
        assertFalse(GraveLifecycle.isMemorialDecayDue(late, BASE, false, false, 7)); // unlooted never decays
        assertFalse(GraveLifecycle.isMemorialDecayDue(late, BASE, true, true, 7));   // in graveyard: history kept
    }

    @Test
    void memorialDecayDisabledWhenThresholdNonPositive() {
        long late = BASE + 100 * DAY;
        assertFalse(GraveLifecycle.isMemorialDecayDue(late, BASE, true, false, 0));
        assertFalse(GraveLifecycle.isMemorialDecayDue(late, BASE, true, false, -1));
    }

    // --- drift: !inGraveyard && !heldByKeeper && !looted && elapsedHours >= driftHours ---

    @Test
    void driftNeedsFreshWildGraveAndThreshold() {
        // 24h threshold
        assertFalse(GraveLifecycle.isDriftDue(BASE + 24 * HOUR - 1, BASE, false, false, false, 24));
        assertTrue(GraveLifecycle.isDriftDue(BASE + 24 * HOUR, BASE, false, false, false, 24)); // boundary
        assertTrue(GraveLifecycle.isDriftDue(BASE + 25 * HOUR, BASE, false, false, false, 24));
    }

    @Test
    void driftExcludedByEachFlag() {
        long late = BASE + 1000 * HOUR;
        assertFalse(GraveLifecycle.isDriftDue(late, BASE, false, true, false, 24));  // already in graveyard
        assertFalse(GraveLifecycle.isDriftDue(late, BASE, false, false, true, 24));  // keeper-held
        assertFalse(GraveLifecycle.isDriftDue(late, BASE, true, false, false, 24));  // looted
    }
}
