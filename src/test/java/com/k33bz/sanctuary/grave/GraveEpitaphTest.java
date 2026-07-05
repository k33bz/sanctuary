package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the age-fuzzy epitaph (part a): the tier boundaries (exact → vague → generic →
 * lost) and the cause→generic collapse once a death is "long ago".
 */
class GraveEpitaphTest {

    // defaults: exact<7d, vague<28d, generic<90d, else lost
    private static final double EXACT = 7, VAGUE = 28, GENERIC = 90;

    private static String ep(GraveEpitaph.Cause c, String killer, long day, double age) {
        return GraveEpitaph.epitaph(c, killer, day, age, EXACT, VAGUE, GENERIC);
    }

    // --- tier 0: fresh, exact cause + exact day ---

    @Test
    void freshMobDeathShowsExactCauseAndDay() {
        assertEquals("Slain by a skeleton · Day 16", ep(GraveEpitaph.Cause.MOB, "skeleton", 16, 0.0));
        assertEquals("Slain by a skeleton · Day 16", ep(GraveEpitaph.Cause.MOB, "skeleton", 16, 6.9));
    }

    @Test
    void freshUsesCorrectArticleAndNonMobClauses() {
        assertEquals("Slain by an enderman · Day 3", ep(GraveEpitaph.Cause.MOB, "enderman", 3, 1.0));
        assertEquals("Fell from a height · Day 5", ep(GraveEpitaph.Cause.FALL, null, 5, 1.0));
        assertEquals("Lost to the void · Day 9", ep(GraveEpitaph.Cause.VOID, null, 9, 1.0));
    }

    // --- tier 1: 7–28d, exact cause, blurred time ---

    @Test
    void weeksTierKeepsCauseButBlursTime() {
        assertEquals("Slain by a skeleton, some weeks past", ep(GraveEpitaph.Cause.MOB, "skeleton", 16, 7.0));
        assertEquals("Slain by a skeleton, some weeks past", ep(GraveEpitaph.Cause.MOB, "skeleton", 16, 27.9));
        // no day number leaks in this tier
        assertFalse(ep(GraveEpitaph.Cause.MOB, "skeleton", 16, 10.0).contains("Day"));
    }

    // --- tier 2: 28–90d, cause collapses to a generic place ---

    @Test
    void longAgoCollapsesCauseToGeneric() {
        // a skeleton death and a fall death read the same generic "Fell in the wilds, long ago"
        assertEquals("Fell in the wilds, long ago", ep(GraveEpitaph.Cause.MOB, "skeleton", 16, 28.0));
        assertEquals("Fell in the wilds, long ago", ep(GraveEpitaph.Cause.FALL, null, 5, 40.0));
        assertEquals("Fell in the wilds, long ago", ep(GraveEpitaph.Cause.BURN, null, 5, 89.9));
        // the void keeps its identity even long ago
        assertEquals("Lost to the void, long ago", ep(GraveEpitaph.Cause.VOID, null, 9, 50.0));
    }

    // --- tier 3: 90d+, lost to time ---

    @Test
    void ancientGraveIsLostToTime() {
        assertEquals("Lost to time", ep(GraveEpitaph.Cause.MOB, "skeleton", 16, 90.0));
        assertEquals("Lost to time", ep(GraveEpitaph.Cause.VOID, null, 9, 1000.0));
    }

    // --- boundaries are inclusive at the lower edge of each next tier ---

    @Test
    void tierBoundariesAreInclusive() {
        // exactly 7.0 leaves the exact tier
        assertTrue(ep(GraveEpitaph.Cause.MOB, "zombie", 1, 7.0).contains("some weeks past"));
        // exactly 28.0 leaves the weeks tier into generic
        assertEquals("Fell in the wilds, long ago", ep(GraveEpitaph.Cause.MOB, "zombie", 1, 28.0));
        // exactly 90.0 is lost
        assertEquals("Lost to time", ep(GraveEpitaph.Cause.MOB, "zombie", 1, 90.0));
    }

    @Test
    void causeParseIsLenient() {
        assertEquals(GraveEpitaph.Cause.MOB, GraveEpitaph.Cause.parse("mob"));
        assertEquals(GraveEpitaph.Cause.WITHER, GraveEpitaph.Cause.parse("WITHER"));
        assertEquals(GraveEpitaph.Cause.GENERIC, GraveEpitaph.Cause.parse(null));
        assertEquals(GraveEpitaph.Cause.GENERIC, GraveEpitaph.Cause.parse("nonsense"));
    }
}
