package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correction-of-Error coverage for the keeper over-spawn fix (0.8.3.3).
 *
 * <p>The bug: the self-heal culled to one but extras kept reappearing between passes, and each cull
 * used {@code /kill} → a "Gravekeeper was killed" chat broadcast. The fix moves the invariant to the
 * SPAWN side: {@link Graves#keeperResetNeeded} decides a (re)spawn ONLY when the keeper count is not
 * exactly one (or a tagged stray lingers), and {@code spawnKeeper} silently {@code discard()}s
 * strays before summoning — so a lone healthy keeper is never touched and no death message fires.
 */
class KeeperInvariantTest {

    // --- the single-keeper invariant predicate ---

    @Test
    void redProof_loneHealthyKeeperIsNeverReset() {
        // exactly one valid keeper, no strays → NO reset (the fix: don't churn a healthy keeper).
        assertFalse(Graves.keeperResetNeeded(1, 1),
                "a single healthy keeper must be left untouched (no respawn, no death spam)");
    }

    @Test
    void resetWhenNoKeeper() {
        assertTrue(Graves.keeperResetNeeded(0, 0), "zero keepers → re-raise one");
    }

    @Test
    void resetOnOverSpawn() {
        // The reported over-spawn: 2 or 3 keepers must collapse back to one.
        assertTrue(Graves.keeperResetNeeded(2, 2), "two keepers → reset to one");
        assertTrue(Graves.keeperResetNeeded(3, 3), "three keepers → reset to one");
    }

    @Test
    void resetWhenTaggedStrayLingers() {
        // One valid keeper but a lightning-converted witch still carries the tag (tagged=2, valid=1):
        // reset so spawnKeeper's tag-scoped discard purges the stray.
        assertTrue(Graves.keeperResetNeeded(1, 2),
                "a lingering tagged non-keeper (witch) forces a purge-and-respawn");
    }

    @Test
    void loneKeeperWithNoStraysIsStable() {
        // Idempotence check across the invariant: the only stable state is exactly (1 valid, 1 tagged).
        for (int valid = 0; valid <= 3; valid++) {
            for (int tagged = valid; tagged <= valid + 2; tagged++) {
                boolean stable = (valid == 1 && tagged == 1);
                assertEquals(!stable, Graves.keeperResetNeeded(valid, tagged),
                        "reset needed unless exactly (1 valid, 1 tagged): valid=" + valid + " tagged=" + tagged);
            }
        }
    }
}
