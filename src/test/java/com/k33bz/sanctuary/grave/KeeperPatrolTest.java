package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correction-of-Error coverage for the Gravekeeper slow-patrol drift (0.8.4, issue #5). The pure
 * {@link KeeperPatrol} logic is unit-tested for the four load-bearing guarantees:
 *
 * <ul>
 *   <li><b>bounds:</b> a target outside the yard AABB is clamped inside (respecting the margin), and
 *       the keeper never produces a position outside the fence;</li>
 *   <li><b>slow:</b> the per-tick step magnitude never exceeds {@code speed} — red-proven by the fact
 *       that removing the cap makes the step too big;</li>
 *   <li><b>linger:</b> once a linger begins, no new target is picked until it elapses;</li>
 *   <li><b>target selection:</b> grave positions are preferred when present.</li>
 * </ul>
 *
 * <p>Live movement <i>feel</i> (does the glide read as ghostly? is the linger the right length?) can't
 * be judged by a harness — that is flagged for a k33bz in-world playtest.
 */
class KeeperPatrolTest {

    // A 9x9 fenced yard: bounds [0..8]x[0..8] → interval [0..9] on each axis after the +1.
    private static final double MINX = 0, MAXX = 9, MINZ = 0, MAXZ = 9;
    private static final double MARGIN = 2.0;
    private static final double SPEED = 0.025;
    private static final int LINGER_MIN = 60, LINGER_MAX = 160;

    /** Deterministic RNG: replays queued doubles/ints, defaulting to 0 when drained. */
    private static final class StubRng implements KeeperPatrol.Rng {
        final Deque<Double> doubles = new ArrayDeque<>();
        final Deque<Integer> ints = new ArrayDeque<>();

        StubRng doubles(double... ds) {
            for (double d : ds) {
                doubles.add(d);
            }
            return this;
        }

        StubRng ints(int... is) {
            for (int i : is) {
                ints.add(i);
            }
            return this;
        }

        @Override
        public double nextDouble() {
            return doubles.isEmpty() ? 0.0 : doubles.poll();
        }

        @Override
        public int nextInt(int bound) {
            return ints.isEmpty() ? 0 : Math.min(bound - 1, Math.max(0, ints.poll()));
        }
    }

    // --- (a) bounds: targets + positions clamped inside, respecting the margin ---

    @Test
    void clampPullsAnOutOfBoundsTargetInsideTheMargin() {
        // Way past the +x fence → clamped to maxX - margin = 9 - 2 = 7.
        assertEquals(7.0, KeeperPatrol.clampInside(100.0, MINX, MAXX, MARGIN), 1e-9);
        // Way past the -x fence → clamped to minX + margin = 0 + 2 = 2.
        assertEquals(2.0, KeeperPatrol.clampInside(-50.0, MINX, MAXX, MARGIN), 1e-9);
        // Already inside → unchanged.
        assertEquals(4.5, KeeperPatrol.clampInside(4.5, MINX, MAXX, MARGIN), 1e-9);
    }

    @Test
    void pickTargetOutsideBoundsIsClampedInside() {
        // A single grave sitting far OUTSIDE the fence must be pulled inside the margin band.
        List<KeeperPatrol.Target> graves = new ArrayList<>();
        graves.add(new KeeperPatrol.Target(1000.0, -1000.0));
        KeeperPatrol.Target t = KeeperPatrol.pickTarget(graves, MINX, MAXX, MINZ, MAXZ, MARGIN,
                new StubRng().ints(0));
        assertTrue(t.x >= MINX + MARGIN - 1e-9 && t.x <= MAXX - MARGIN + 1e-9,
                "clamped target x is inside the margin band");
        assertTrue(t.z >= MINZ + MARGIN - 1e-9 && t.z <= MAXZ - MARGIN + 1e-9,
                "clamped target z is inside the margin band");
    }

    @Test
    void keeperPositionNeverLeavesBoundsAcrossAWholePatrol() {
        // Start the keeper OUTSIDE the fence; drive many ticks; it must never be produced outside.
        KeeperPatrol.State st = new KeeperPatrol.State();
        double x = 50.0, z = -20.0; // well outside
        List<KeeperPatrol.Target> graves = new ArrayList<>();
        graves.add(new KeeperPatrol.Target(4.5, 4.5));
        StubRng rng = new StubRng();
        for (int i = 0; i < 5000; i++) {
            KeeperPatrol.Move mv = KeeperPatrol.advance(st, x, z, graves,
                    MINX, MAXX, MINZ, MAXZ, MARGIN, SPEED, 1, 1, rng);
            x = mv.x;
            z = mv.z;
            assertTrue(x >= MINX + MARGIN - 1e-9 && x <= MAXX - MARGIN + 1e-9,
                    "keeper x stays inside the margin band at tick " + i + " (was " + x + ")");
            assertTrue(z >= MINZ + MARGIN - 1e-9 && z <= MAXZ - MARGIN + 1e-9,
                    "keeper z stays inside the margin band at tick " + i + " (was " + z + ")");
        }
    }

    // --- (b) slow: the per-tick step magnitude never exceeds speed (red-proven by removing the cap) ---

    @Test
    void perTickStepNeverExceedsSpeed() {
        // The target is FAR from the keeper, so an uncapped step would be huge — proving the cap.
        double x = 2.0, z = 2.0;
        double tx = 7.0, tz = 7.0; // ~7.07 blocks away
        double[] next = KeeperPatrol.stepToward(x, z, tx, tz, SPEED);
        double stepped = Math.hypot(next[0] - x, next[1] - z);
        assertTrue(stepped <= SPEED + 1e-9,
                "one step must be <= speed (" + SPEED + "), got " + stepped);
    }

    @Test
    void redProof_uncappedStepWouldBeTooBig() {
        // Documents the RED failure: the raw (uncapped) displacement toward a far target is FAR bigger
        // than speed. The cap in stepToward is exactly what turns this into the passing case above; if
        // that cap were removed, the step would equal this raw distance and the "slow" test would fail.
        double x = 2.0, z = 2.0, tx = 7.0, tz = 7.0;
        double rawDistance = Math.hypot(tx - x, tz - z);
        assertTrue(rawDistance > SPEED * 100,
                "the uncapped move would be two orders of magnitude too fast (" + rawDistance + ")");
    }

    @Test
    void everyStepOfAFullDriftObeysTheSpeedCap() {
        // Across an entire glide from one corner toward another, no single tick's displacement exceeds
        // speed — the whole motion is slow, not just the first step.
        KeeperPatrol.State st = new KeeperPatrol.State();
        double x = 2.0, z = 2.0;
        List<KeeperPatrol.Target> graves = new ArrayList<>();
        graves.add(new KeeperPatrol.Target(7.0, 7.0));
        StubRng rng = new StubRng();
        for (int i = 0; i < 400; i++) {
            double px = x, pz = z;
            KeeperPatrol.Move mv = KeeperPatrol.advance(st, x, z, graves,
                    MINX, MAXX, MINZ, MAXZ, MARGIN, SPEED, 1, 1, rng);
            x = mv.x;
            z = mv.z;
            double stepped = Math.hypot(x - px, z - pz);
            assertTrue(stepped <= SPEED + 1e-9,
                    "tick " + i + " step " + stepped + " exceeds speed " + SPEED);
        }
    }

    @Test
    void stepToTargetSnapsWithoutOvershoot() {
        // Within one speed of the target, it lands exactly ON the target (no overshoot past it).
        double[] next = KeeperPatrol.stepToward(5.0, 5.0, 5.01, 5.0, SPEED);
        assertEquals(5.01, next[0], 1e-9);
        assertEquals(5.0, next[1], 1e-9);
    }

    // --- (c) linger: no new target until the linger elapses ---

    @Test
    void lingerHoldsPositionAndDefersTheNextTarget() {
        // Put the keeper AT its first target so it immediately begins a fixed 5-tick linger.
        KeeperPatrol.State st = new KeeperPatrol.State();
        List<KeeperPatrol.Target> graves = new ArrayList<>();
        graves.add(new KeeperPatrol.Target(4.5, 4.5)); // the only grave → deterministic pick
        StubRng rng = new StubRng();
        double x = 4.5, z = 4.5;

        // Tick 1: picks the (co-located) target, reaches it, and begins a linger of [5..5] = 5 ticks.
        KeeperPatrol.Move first = KeeperPatrol.advance(st, x, z, graves,
                MINX, MAXX, MINZ, MAXZ, MARGIN, SPEED, 5, 5, rng);
        assertTrue(first.lingering, "arriving on the target begins a linger");
        assertEquals(5, st.lingerTicks, "linger initialised to the fixed duration");

        // Across the whole 5-tick linger the position must NOT drift and no new target is chosen.
        // (lingerTicks was set to 5; draining it fully takes 5 more advances: 5→4→3→2→1→0.)
        double heldX = first.x, heldZ = first.z;
        for (int i = 0; i < 5; i++) {
            assertTrue(st.lingerTicks > 0, "still within the linger before sub-tick " + i);
            KeeperPatrol.Move mv = KeeperPatrol.advance(st, heldX, heldZ, graves,
                    MINX, MAXX, MINZ, MAXZ, MARGIN, SPEED, 5, 5, rng);
            assertTrue(mv.lingering, "still lingering at sub-tick " + i);
            assertEquals(heldX, mv.x, 1e-9, "position held during linger");
            assertEquals(heldZ, mv.z, 1e-9, "position held during linger");
            assertFalse(st.hasTarget, "no new target is committed mid-linger");
        }
        // The linger has now fully elapsed; the NEXT advance is free to pick a fresh target.
        assertTrue(st.lingerTicks <= 0, "linger has elapsed after its full duration");
    }

    @Test
    void lingerDurationRespectsTheConfiguredRange() {
        // A [60..160] linger with a nextInt draw of 40 → 60 + 40 = 100 ticks.
        KeeperPatrol.State st = new KeeperPatrol.State();
        List<KeeperPatrol.Target> graves = new ArrayList<>();
        graves.add(new KeeperPatrol.Target(4.5, 4.5));
        StubRng rng = new StubRng().ints(0 /*grave index*/, 40 /*linger span draw*/);
        KeeperPatrol.advance(st, 4.5, 4.5, graves,
                MINX, MAXX, MINZ, MAXZ, MARGIN, SPEED, LINGER_MIN, LINGER_MAX, rng);
        assertEquals(100, st.lingerTicks, "linger = min + rng.nextInt(span+1)");
        assertTrue(st.lingerTicks >= LINGER_MIN && st.lingerTicks <= LINGER_MAX,
                "linger stays within the configured range");
    }

    // --- (d) target selection prefers grave positions when present ---

    @Test
    void targetSelectionPrefersGravePositions() {
        // Two graves; index draw 1 → the second grave (clamped) is chosen, not a random point.
        List<KeeperPatrol.Target> graves = new ArrayList<>();
        graves.add(new KeeperPatrol.Target(3.0, 3.0));
        graves.add(new KeeperPatrol.Target(6.0, 6.0));
        KeeperPatrol.Target t = KeeperPatrol.pickTarget(graves, MINX, MAXX, MINZ, MAXZ, MARGIN,
                new StubRng().ints(1));
        assertEquals(6.0, t.x, 1e-9, "the chosen grave x (in-bounds, unclamped)");
        assertEquals(6.0, t.z, 1e-9, "the chosen grave z (in-bounds, unclamped)");
    }

    @Test
    void fallsBackToRandomInBoundsPointWhenNoGraves() {
        // No graves → a random in-bounds point; the draw 0.5 lands mid-band on each axis.
        List<KeeperPatrol.Target> graves = new ArrayList<>();
        KeeperPatrol.Target t = KeeperPatrol.pickTarget(graves, MINX, MAXX, MINZ, MAXZ, MARGIN,
                new StubRng().doubles(0.5, 0.5));
        // band is [2 .. 7]; midpoint = 2 + 0.5*(7-2) = 4.5
        assertEquals(4.5, t.x, 1e-9);
        assertEquals(4.5, t.z, 1e-9);
        assertTrue(t.x >= MINX + MARGIN && t.x <= MAXX - MARGIN, "fallback x in-bounds");
        assertTrue(t.z >= MINZ + MARGIN && t.z <= MAXZ - MARGIN, "fallback z in-bounds");
    }

    @Test
    void faceYawTracksTravelDirection() {
        // Facing +x (east) → yaw -90; +z (south) → yaw 0 (Minecraft convention).
        assertEquals(-90.0f, KeeperPatrol.faceYaw(1.0, 0.0, 123.0f), 1e-4);
        assertEquals(0.0f, KeeperPatrol.faceYaw(0.0, 1.0, 123.0f), 1e-4);
        // Zero vector → the fallback yaw (a lingering keeper keeps its last facing).
        assertEquals(123.0f, KeeperPatrol.faceYaw(0.0, 0.0, 123.0f), 1e-9);
    }
}
