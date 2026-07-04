package com.k33bz.sanctuary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeralEggNamesTest {

    private static String stars(int n) {
        return String.valueOf(FeralEggNames.STAR).repeat(n);
    }

    @Test
    void parseStarsReadsPlainAndStarredNames() {
        assertEquals(0, FeralEggNames.parseStars("Feral Egg"));
        assertEquals(1, FeralEggNames.parseStars("Feral Egg " + stars(1)));
        assertEquals(3, FeralEggNames.parseStars("Feral Egg " + stars(3)));
        assertEquals(5, FeralEggNames.parseStars("Feral Egg " + stars(5))); // max valid
    }

    @Test
    void parseStarsRejectsOverCap() {
        assertEquals(-1, FeralEggNames.parseStars("Feral Egg " + stars(6)));
        assertEquals(-1, FeralEggNames.parseStars("Feral Egg " + stars(9)));
    }

    @Test
    void parseStarsRejectsNonMatchingNames() {
        assertEquals(-1, FeralEggNames.parseStars("Egg"));
        assertEquals(-1, FeralEggNames.parseStars("feral egg"));         // case sensitive
        assertEquals(-1, FeralEggNames.parseStars("Feral Eggs"));        // trailing garbage, no space
        assertEquals(-1, FeralEggNames.parseStars("Feral Egg "));        // trailing space, no stars
        assertEquals(-1, FeralEggNames.parseStars("Feral Egg " + stars(2) + "x")); // stars then junk
        assertEquals(-1, FeralEggNames.parseStars("Feral Egg xxx"));     // non-star suffix
        assertEquals(-1, FeralEggNames.parseStars(null));
        assertEquals(-1, FeralEggNames.parseStars(""));
    }

    @Test
    void isAllStarsGlyphEdgeCases() {
        assertTrue(FeralEggNames.isAllStars(stars(1)));
        assertTrue(FeralEggNames.isAllStars(stars(4)));
        assertFalse(FeralEggNames.isAllStars(""));            // empty is not a star run
        assertFalse(FeralEggNames.isAllStars("*"));           // ASCII asterisk is not the glyph
        assertFalse(FeralEggNames.isAllStars(stars(2) + " ")); // trailing space
        assertFalse(FeralEggNames.isAllStars(null));
    }

    @Test
    void starsFromLoreLineMatchesStarOnlyLines() {
        assertEquals(0, FeralEggNames.starsFromLoreLine(""));                       // empty
        assertEquals(1, FeralEggNames.starsFromLoreLine(stars(1)));
        assertEquals(5, FeralEggNames.starsFromLoreLine(stars(5)));
        assertEquals(0, FeralEggNames.starsFromLoreLine(stars(6)));                 // over the length cap
        assertEquals(0, FeralEggNames.starsFromLoreLine("Something Savage stirs")); // prose line
        assertEquals(0, FeralEggNames.starsFromLoreLine("Generation 3 of a proud line"));
        assertEquals(0, FeralEggNames.starsFromLoreLine(null));
    }
}
