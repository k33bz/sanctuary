package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the nature-reclaims flora aging (part b): ground block by age (podzol → grass)
 * and the flower-tier gate + weighted flower selection.
 */
class GraveFloraTest {

    // defaults: grass at d3, flower at d7
    private static final double GRASS = 3, FLOWER = 7;

    // --- GRAVEYARD-ONLY gate (0.8.2.1): wild graves get NO flora ---

    @Test
    void floraOnlyAppliesToGraveyardGraves() {
        assertTrue(GraveFlora.appliesTo(true), "a graveyard grave grows flora");
        // Red-prove the fix: a WILD grave (inGraveyard=false) must NOT flora — it keeps its ground.
        assertFalse(GraveFlora.appliesTo(false), "a WILD grave must NOT get podzol/grass/flower");
    }

    @Test
    void groundIsPodzolWhenFreshThenGrass() {
        assertEquals("minecraft:podzol", GraveFlora.groundBlock(0.0, GRASS, FLOWER));
        assertEquals("minecraft:podzol", GraveFlora.groundBlock(2.9, GRASS, FLOWER));
        assertEquals("minecraft:grass_block", GraveFlora.groundBlock(3.0, GRASS, FLOWER)); // boundary inclusive
        assertEquals("minecraft:grass_block", GraveFlora.groundBlock(50.0, GRASS, FLOWER));
    }

    @Test
    void flowerOnlyAtOrPastFlowerDays() {
        assertFalse(GraveFlora.hasFlower(6.9, GRASS, FLOWER));
        assertTrue(GraveFlora.hasFlower(7.0, GRASS, FLOWER));   // boundary inclusive
        assertTrue(GraveFlora.hasFlower(30.0, GRASS, FLOWER));
    }

    @Test
    void flowerRequiresFlowerDaysAtLeastGrassDays() {
        // a misconfigured flowerDays < grassDays never blooms (grass must come first)
        assertFalse(GraveFlora.hasFlower(100.0, 7, 3));
    }

    @Test
    void witherRoseIsRareAndGatedByRoll() {
        // roll below the chance -> wither rose regardless of pick
        assertEquals("minecraft:wither_rose", GraveFlora.flowerBlock(0.0, 0.5, 0.05));
        assertEquals("minecraft:wither_rose", GraveFlora.flowerBlock(0.049, 0.9, 0.05));
        // roll at/above the chance -> a common flower by the pick buckets
        assertEquals("minecraft:lily_of_the_valley", GraveFlora.flowerBlock(0.5, 0.1, 0.05));
        assertEquals("minecraft:oxeye_daisy", GraveFlora.flowerBlock(0.5, 0.5, 0.05));
        assertEquals("minecraft:white_tulip", GraveFlora.flowerBlock(0.5, 0.9, 0.05));
    }

    @Test
    void zeroWitherChanceNeverYieldsRose() {
        assertEquals("minecraft:lily_of_the_valley", GraveFlora.flowerBlock(0.0, 0.0, 0.0));
    }
}
