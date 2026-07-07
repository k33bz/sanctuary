package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correction-of-Error coverage for the Gravekeeper ambient mutterings (0.8.5). The pure
 * {@link KeeperMutter} selection/gate logic is unit-tested for the load-bearing guarantees:
 *
 * <ul>
 *   <li><b>(a) proximity gate:</b> no player within radius → no mutter (red-proven: removing the gate
 *       makes it "fire" with nobody near);</li>
 *   <li><b>(b) no immediate repeat:</b> a static line is never chosen twice in a row per keeper;</li>
 *   <li><b>(c) contextual gating:</b> a night line is never chosen by day (and vice-versa); a
 *       grave-name line only appears when a known owner is near, with the name templated in;</li>
 *   <li><b>(d) interval:</b> the jittered cadence stays within [min, max].</li>
 * </ul>
 *
 * <p>Plus the memory payoff: cause→phrase mapping, the {@code daysAgo} math, and that a grave with no
 * stored cause falls back to the "lost memory" line. Live tone/feel is manual — flagged for playtest.
 */
class KeeperMutterTest {

    /** Deterministic RNG: replays queued doubles/ints, defaulting to 0 when drained. */
    private static final class StubRng implements KeeperMutter.Rng {
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
            int v = ints.isEmpty() ? 0 : ints.poll();
            return Math.min(bound - 1, Math.max(0, v));
        }
    }

    private static final int POOL = 18; // matches en_us.json static pool size

    // --- (a) proximity gate — the caller-facing predicate, red-proven ---

    @Test
    void withinRadiusIsTrueOnlyInsideTheRadius() {
        // Keeper at origin, radius 14. A player 10 blocks away is heard; 20 blocks away is not.
        assertTrue(KeeperMutter.withinRadius(0, 0, 10, 0, 14.0), "10 blocks < 14 → nearby");
        assertFalse(KeeperMutter.withinRadius(0, 0, 20, 0, 14.0), "20 blocks > 14 → not nearby");
        // On the boundary (exactly 14) counts as within.
        assertTrue(KeeperMutter.withinRadius(0, 0, 14, 0, 14.0), "on the radius → nearby");
    }

    @Test
    void redProof_removingTheGateWouldFireWithNobodyNear() {
        // The RED failure this gate prevents: a far player (50 blocks) must NOT satisfy the gate. If the
        // caller skipped withinRadius (treated everyone as nearby), the keeper would mutter to a player
        // 50 blocks away — audible-to-nobody spam. The gate returning false here is exactly what stops it.
        boolean gate = KeeperMutter.withinRadius(0, 0, 50, 0, 14.0);
        assertFalse(gate, "a 50-block-away player is NOT nearby; the mutter must be skipped");
        // Sanity: an ungated check (radius huge) WOULD include them — documenting the removed-gate bug.
        assertTrue(KeeperMutter.withinRadius(0, 0, 50, 0, 1000.0),
                "with the gate defeated (huge radius) the far player wrongly counts — the red case");
    }

    // --- (b) never the same static line twice in a row, per keeper ---

    @Test
    void staticLineNeverRepeatsTheImmediatelyPreviousIndex() {
        // For every possible "last index", drawing the whole remaining space must never yield lastIndex.
        for (int last = 0; last < POOL; last++) {
            for (int draw = 0; draw < POOL - 1; draw++) {
                KeeperMutter.Line line = KeeperMutter.staticLine(POOL, last, new StubRng().ints(draw));
                assertEquals(KeeperMutter.Kind.STATIC, line.kind);
                assertFalse(line.index == last,
                        "static pick repeated last index " + last + " (draw " + draw + ")");
                assertTrue(line.index >= 0 && line.index < POOL, "index in range");
            }
        }
    }

    @Test
    void staticLineCoversAllOtherIndicesUniformly() {
        // With lastIndex=3, the 17 draws 0..16 must map onto exactly the other 17 indices (0..17 minus 3).
        int last = 3;
        Set<Integer> produced = new HashSet<>();
        for (int draw = 0; draw < POOL - 1; draw++) {
            produced.add(KeeperMutter.staticLine(POOL, last, new StubRng().ints(draw)).index);
        }
        assertEquals(POOL - 1, produced.size(), "every non-last index is reachable");
        assertFalse(produced.contains(last), "the last index is never produced");
    }

    @Test
    void selectThenRepeatDoesNotPickTheSameStaticLine() {
        // Two consecutive selects (no grave, day, few graves) must not return the same static index when
        // the caller feeds back the previous index — the end-to-end no-repeat contract.
        KeeperMutter.Context day = new KeeperMutter.Context(POOL, false, 0, 100, null);
        // Force the static path: grave weight irrelevant (no grave), context roll fails (draw 0.99).
        KeeperMutter.Line first = KeeperMutter.select(day, -1, 0.65, 0.30,
                new StubRng().doubles(0.99).ints(5));
        assertEquals(KeeperMutter.Kind.STATIC, first.kind);
        KeeperMutter.Line second = KeeperMutter.select(day, first.index, 0.65, 0.30,
                new StubRng().doubles(0.99).ints(first.index)); // even if the raw draw == last, it shifts
        assertFalse(second.index == first.index, "consecutive statics differ");
    }

    // --- (c) contextual gating: night ≠ day; grave-name only with a known owner ---

    @Test
    void nightLinesAreOnlyEligibleAtNight() {
        KeeperMutter.Context night = new KeeperMutter.Context(POOL, true, 0, 100, null);
        KeeperMutter.Context day = new KeeperMutter.Context(POOL, false, 0, 100, null);
        var nightEligible = KeeperMutter.contextualEligible(night);
        var dayEligible = KeeperMutter.contextualEligible(day);
        // Every night-eligible line's text is a known night line; none is the day line, and vice-versa.
        for (KeeperMutter.Line l : nightEligible) {
            assertTrue(contains(KeeperMutter.NIGHT_LINES, l.text),
                    "at night, only night lines are eligible: " + l.text);
        }
        for (KeeperMutter.Line l : dayEligible) {
            assertEquals(KeeperMutter.DAY_LINES[0], l.text, "by day, only the day line is eligible");
        }
    }

    @Test
    void nightLineNeverChosenDuringDay() {
        // Force the contextual branch by day (grave null, context roll succeeds with draw 0.0).
        KeeperMutter.Context day = new KeeperMutter.Context(POOL, false, 0, 100, null);
        KeeperMutter.Line l = KeeperMutter.select(day, -1, 0.65, 0.30,
                new StubRng().doubles(0.0).ints(0));
        assertEquals(KeeperMutter.Kind.CONTEXTUAL, l.kind);
        assertFalse(contains(KeeperMutter.NIGHT_LINES, l.text), "no night line by day");
        assertEquals(KeeperMutter.DAY_LINES[0], l.text);
    }

    @Test
    void manyGravesLineOnlyEligibleAtOrAboveThreshold() {
        int th = KeeperMutter.MANY_GRAVES_THRESHOLD;
        var below = KeeperMutter.contextualEligible(new KeeperMutter.Context(POOL, false, th - 1, 1, null));
        var atOrAbove = KeeperMutter.contextualEligible(new KeeperMutter.Context(POOL, false, th, 1, null));
        assertFalse(below.stream().anyMatch(l -> l.text.equals(KeeperMutter.MANY_GRAVES_LINE)),
                "below threshold: no many-graves line");
        assertTrue(atOrAbove.stream().anyMatch(l -> l.text.equals(KeeperMutter.MANY_GRAVES_LINE)),
                "at/above threshold: many-graves line eligible");
    }

    @Test
    void graveNameLineOnlyWithAKnownOwnerAndTemplatesTheName() {
        // A grave WITH a cause, forced onto the gentle plain-name variety (reveal draw 0 → plain),
        // and the name must be templated in. (A causeless grave routes to lost-memory instead — see
        // graveWithNoStoredCauseFallsBackToLostMemory.)
        KeeperMutter.GraveMemory mem = new KeeperMutter.GraveMemory(
                "Steve", GraveEpitaph.Cause.FALL, null, 40L, true);
        // ints: 0 → plain variety; 0 → POOR_NAME template.
        KeeperMutter.Line l = KeeperMutter.graveLine(mem, 50L, new StubRng().ints(0, 0));
        assertEquals(KeeperMutter.Kind.CONTEXTUAL, l.kind);
        assertTrue(l.text.contains("Steve"), "the owner name is templated in: " + l.text);
    }

    @Test
    void graveMemoryLineRevealsTheCauseAndDayTheStoneNoLongerShows() {
        // A grave WITH a captured mob cause + day → a cause-bearing line naming the method (payoff).
        KeeperMutter.GraveMemory mem = new KeeperMutter.GraveMemory(
                "Alex", GraveEpitaph.Cause.MOB, "skeleton", 12L, true);
        // ints: 1 → NOT plain (reveal the cause); 1 → TAKEN_BY_DAY template.
        KeeperMutter.Line l = KeeperMutter.graveLine(mem, 100L, new StubRng().ints(1, 1));
        assertTrue(l.text.contains("Alex"), "name templated");
        assertTrue(l.text.contains("a skeleton's arrow"), "cause phrased: " + l.text);
        assertTrue(l.text.contains("day 12"), "in-game death day templated: " + l.text);
    }

    @Test
    void daysAgoLineTemplatesTheElapsedDays() {
        KeeperMutter.GraveMemory mem = new KeeperMutter.GraveMemory(
                "Alex", GraveEpitaph.Cause.FALL, null, 30L, true);
        // ints: 1 → reveal cause; 3 → DAYS_LAIN: "{daysAgo} days {name}'s lain here… {cause}, it was."
        KeeperMutter.Line l = KeeperMutter.graveLine(mem, 95L, new StubRng().ints(1, 3));
        assertTrue(l.text.startsWith("65 days"), "daysAgo = 95 - 30 = 65: " + l.text);
        assertTrue(l.text.contains("the long fall"), "fall phrased");
    }

    // --- (d) interval respects min/max ---

    @Test
    void rollIntervalStaysWithinMinMax() {
        int min = 800, max = 1800;
        // Draw 0 → the minimum; draw (span) → the maximum; a middling draw lands between.
        assertEquals(min, KeeperMutter.rollInterval(min, max, new StubRng().ints(0)));
        assertEquals(max, KeeperMutter.rollInterval(min, max, new StubRng().ints(max - min)));
        int mid = KeeperMutter.rollInterval(min, max, new StubRng().ints(500));
        assertTrue(mid >= min && mid <= max, "mid interval in range: " + mid);
    }

    @Test
    void rollIntervalToleratesInvertedOrZeroRange() {
        // max < min → treated as [min, min]; min<1 → floored at 1.
        assertEquals(800, KeeperMutter.rollInterval(800, 100, new StubRng().ints(999)));
        assertTrue(KeeperMutter.rollInterval(0, 0, new StubRng()) >= 1, "floored at 1");
    }

    // --- memory payoff: cause→phrase mapping ---

    @Test
    void causePhraseMapsEachKnownCause() {
        assertEquals("the long fall", KeeperMutter.causePhrase(GraveEpitaph.Cause.FALL, null));
        assertEquals("the burning", KeeperMutter.causePhrase(GraveEpitaph.Cause.BURN, null));
        assertEquals("the water", KeeperMutter.causePhrase(GraveEpitaph.Cause.DROWN, null));
        assertEquals("the withering", KeeperMutter.causePhrase(GraveEpitaph.Cause.WITHER, null));
        assertEquals("the void", KeeperMutter.causePhrase(GraveEpitaph.Cause.VOID, null));
        assertEquals("a skeleton's arrow", KeeperMutter.causePhrase(GraveEpitaph.Cause.MOB, "skeleton"));
        assertEquals("the blast", KeeperMutter.causePhrase(GraveEpitaph.Cause.MOB, "creeper"));
        assertEquals("cold hands", KeeperMutter.causePhrase(GraveEpitaph.Cause.MOB, "zombie"));
        assertEquals("the deep dark", KeeperMutter.causePhrase(GraveEpitaph.Cause.MOB, "warden"));
        // A named foe with no set flavor still phrases as "{killer}'s hand".
        assertEquals("a phantom's hand", KeeperMutter.causePhrase(GraveEpitaph.Cause.MOB, "phantom"));
    }

    @Test
    void causePhraseIsNullWhenThereIsNoUsableMethod() {
        assertNull(KeeperMutter.causePhrase(GraveEpitaph.Cause.GENERIC, null), "generic → no phrase");
        assertNull(KeeperMutter.causePhrase(null, null), "null cause → no phrase");
        assertNull(KeeperMutter.causePhrase(GraveEpitaph.Cause.MOB, null), "mob w/o killer → no phrase");
    }

    // --- memory payoff: daysAgo math ---

    @Test
    void daysAgoMathIsCurrentMinusDeathFlooredAtZero() {
        assertEquals(65L, KeeperMutter.daysAgo(100L, 35L));
        assertEquals(0L, KeeperMutter.daysAgo(100L, 100L), "same day → 0");
        assertEquals(0L, KeeperMutter.daysAgo(50L, 100L), "future death (clock reset) → floored at 0");
        assertEquals(0L, KeeperMutter.daysAgo(100L, 0L), "legacy deathDay 0 → 0 (unknown)");
    }

    // --- memory payoff: no stored cause → the lost-memory line ---

    @Test
    void graveWithNoStoredCauseFallsBackToLostMemory() {
        // A legacy grave: has a name, but no captured cause (hasCause=false, day 0).
        KeeperMutter.GraveMemory legacy = new KeeperMutter.GraveMemory(
                "OldTimer", GraveEpitaph.Cause.GENERIC, null, 0L, false);
        // Draw 0 → HOW_DID_YOU_GO (the with-name lost-memory line), name templated.
        KeeperMutter.Line l = KeeperMutter.graveLine(legacy, 500L, new StubRng().ints(0));
        assertNotNull(l);
        assertEquals(KeeperMutter.Kind.CONTEXTUAL, l.kind);
        assertTrue(l.text.contains("OldTimer") || l.text.contains("name's all that's left"),
                "lost-memory line for a causeless grave: " + l.text);
        assertTrue(l.text.contains("forget") || l.text.contains("all that's left"),
                "reads as forgetting, not a method: " + l.text);
        // And critically, no fabricated cause phrase leaks in.
        assertFalse(l.text.contains("arrow") || l.text.contains("the burning"),
                "no invented method for a causeless grave");
    }

    @Test
    void graveWithNoNameAndNoCauseUsesTheNamelessLostLine() {
        KeeperMutter.GraveMemory blank = new KeeperMutter.GraveMemory(
                null, GraveEpitaph.Cause.GENERIC, null, 0L, false);
        KeeperMutter.Line l = KeeperMutter.graveLine(blank, 10L, new StubRng().ints(0));
        assertEquals("so long ago… the name's all that's left.", l.text);
    }

    @Test
    void selectFavorsGraveMemoryWhenAUsableGraveIsNear() {
        // Near a grave with real data + a low RNG double → the grave-memory branch is taken.
        KeeperMutter.GraveMemory mem = new KeeperMutter.GraveMemory(
                "Alex", GraveEpitaph.Cause.MOB, "creeper", 5L, true);
        KeeperMutter.Context ctx = new KeeperMutter.Context(POOL, false, 0, 20, mem);
        // doubles: 0.10 < 0.65 → grave branch. ints: 1 → reveal cause; 0 → STONE_WONT_SAY template.
        KeeperMutter.Line l = KeeperMutter.select(ctx, -1, 0.65, 0.30,
                new StubRng().doubles(0.10).ints(1, 0));
        assertEquals(KeeperMutter.Kind.CONTEXTUAL, l.kind);
        assertTrue(l.text.contains("Alex") && l.text.contains("the blast"),
                "favored grave-memory line: " + l.text);
    }

    private static boolean contains(String[] arr, String s) {
        for (String a : arr) {
            if (a.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
