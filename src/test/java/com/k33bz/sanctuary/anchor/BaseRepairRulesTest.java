package com.k33bz.sanctuary.anchor;

import com.k33bz.sanctuary.anchor.BaseRepairRules.Mode;
import com.k33bz.sanctuary.anchor.BaseRepairRules.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** System 12 — base auto-repair policy. */
class BaseRepairRulesTest {

    private static final long H = (long) AnchorFuel.TICKS_PER_HOUR;

    // Mirrors the SanctuaryConfig defaults so the tests read like the shipped balance.
    private static final List<Double> HARDNESS = List.of(0.6, 2.0, 5.0);
    private static final List<String> PRECIOUS = List.of(
            "minecraft:diamond_block", "minecraft:netherite_block", "minecraft:beacon");
    private static final List<Double> DELAY_S = List.of(60.0, 300.0, 1800.0, 14400.0);
    private static final List<Double> COST_H = List.of(0.001, 0.005, 0.05, 0.5);

    // --- tiers: cheaper blocks are lower tiers ---

    @Test
    void tiersRiseWithHardness() {
        assertEquals(0, BaseRepairRules.tier(0.5, "minecraft:dirt", HARDNESS, PRECIOUS));
        assertEquals(0, BaseRepairRules.tier(0.6, "minecraft:sand", HARDNESS, PRECIOUS)); // inclusive ceiling
        assertEquals(1, BaseRepairRules.tier(1.5, "minecraft:stone", HARDNESS, PRECIOUS));
        assertEquals(1, BaseRepairRules.tier(2.0, "minecraft:oak_planks", HARDNESS, PRECIOUS));
        assertEquals(2, BaseRepairRules.tier(5.0, "minecraft:iron_block", HARDNESS, PRECIOUS));
        assertEquals(3, BaseRepairRules.tier(50.0, "minecraft:obsidian", HARDNESS, PRECIOUS));
    }

    @Test
    void preciousBlocksAreTopTierEvenWhenSoft() {
        // A diamond block is only hardness 5, but it must never rebuild on the cheap schedule.
        assertEquals(3, BaseRepairRules.tier(5.0, "minecraft:diamond_block", HARDNESS, PRECIOUS));
        assertEquals(3, BaseRepairRules.tier(3.0, "minecraft:beacon", HARDNESS, PRECIOUS));
    }

    @Test
    void unbreakableHardnessIsTopTier() {
        assertEquals(3, BaseRepairRules.tier(-1.0, "minecraft:bedrock", HARDNESS, PRECIOUS));
    }

    // --- delay and cost scale with the owner's chosen speed ---

    @Test
    void cheapBlocksComeBackFasterAndCheaper() {
        long dirt = BaseRepairRules.delayTicks(0, Mode.NORMAL, DELAY_S);
        long netherite = BaseRepairRules.delayTicks(3, Mode.NORMAL, DELAY_S);
        assertEquals(60 * 20, dirt);
        assertEquals(14400 * 20, netherite);
        assertTrue(BaseRepairRules.costHours(0, Mode.NORMAL, COST_H)
                < BaseRepairRules.costHours(3, Mode.NORMAL, COST_H));
    }

    @Test
    void fasterModesWaitLessButBurnMore() {
        long slow = BaseRepairRules.delayTicks(1, Mode.SLOW, DELAY_S);
        long normal = BaseRepairRules.delayTicks(1, Mode.NORMAL, DELAY_S);
        long fast = BaseRepairRules.delayTicks(1, Mode.FAST, DELAY_S);
        long turbo = BaseRepairRules.delayTicks(1, Mode.TURBO, DELAY_S);
        assertTrue(slow > normal && normal > fast && fast > turbo);

        double cSlow = BaseRepairRules.costHours(1, Mode.SLOW, COST_H);
        double cNormal = BaseRepairRules.costHours(1, Mode.NORMAL, COST_H);
        double cTurbo = BaseRepairRules.costHours(1, Mode.TURBO, COST_H);
        assertTrue(cSlow < cNormal && cNormal < cTurbo);
    }

    @Test
    void delayNeverDropsBelowOneSecond() {
        // Even turbo on the cheapest tier with a zero-second config waits a second, so a blast
        // never rebuilds inside the same tick the explosion is still resolving.
        assertEquals(20, BaseRepairRules.delayTicks(0, Mode.TURBO, List.of(0.0)));
    }

    @Test
    void perTierClampsToTheListEnds() {
        assertEquals(0.5, BaseRepairRules.perTier(COST_H, 99), 0.0);
        assertEquals(0.001, BaseRepairRules.perTier(COST_H, -3), 0.0);
        assertEquals(0.0, BaseRepairRules.perTier(List.of(), 1), 0.0);
    }

    // --- paying for repairs out of the fuel bank ---

    @Test
    void repairsDrainTheFuelBank() {
        long now = 1_000_000L;
        long expiry = now + 10 * H;
        long after = BaseRepairRules.paidExpiry(expiry, 0.5);
        assertEquals(expiry - H / 2, after); // half an hour of fuel gone
        assertEquals(9.5, AnchorFuel.hoursLeft(after, now), 1e-9);
    }

    @Test
    void repairsNeverTipASanctuaryIntoDormancy() {
        long now = 1_000_000L;
        long expiry = now + H;                      // one hour left
        assertTrue(BaseRepairRules.canAfford(expiry, now, 0.5, 0.5));   // leaves exactly the reserve
        assertFalse(BaseRepairRules.canAfford(expiry, now, 0.6, 0.5));  // would dip under it
        assertFalse(BaseRepairRules.canAfford(now - 1, now, 0.001, 0.0)); // already dormant
    }

    @Test
    void eternalAnchorsRepairForFree() {
        assertTrue(BaseRepairRules.canAfford(0L, 123L, 1_000.0, 1_000.0));
        assertEquals(0L, BaseRepairRules.paidExpiry(0L, 1_000.0));
    }

    // --- deciding a due journal entry ---

    @Test
    void blastHoleIsRebuilt() {
        assertEquals(Outcome.RESTORE, BaseRepairRules.decide(
                "minecraft:air", "minecraft:air", "minecraft:stone_bricks", true, false, true));
    }

    @Test
    void trampledFarmlandIsRebuiltFromDirt() {
        // dirt is not replaceable, but it is exactly what the trample left behind
        assertEquals(Outcome.RESTORE, BaseRepairRules.decide(
                "minecraft:dirt", "minecraft:dirt", "minecraft:farmland", false, false, true));
    }

    @Test
    void ownerBuildingOverTheSpotWins() {
        // The owner put glass where the wall was: never overwrite a player's newer choice.
        assertEquals(Outcome.SUPERSEDED, BaseRepairRules.decide(
                "minecraft:glass", "minecraft:air", "minecraft:stone_bricks", false, false, true));
    }

    @Test
    void alreadyFixedByHandIsDropped() {
        assertEquals(Outcome.SUPERSEDED, BaseRepairRules.decide(
                "minecraft:stone_bricks", "minecraft:air", "minecraft:stone_bricks", false, false, true));
    }

    @Test
    void waterFlowingIntoTheHoleDoesNotBlockTheRepair() {
        // water/fire/grass are replaceable: the repair overwrites them
        assertEquals(Outcome.RESTORE, BaseRepairRules.decide(
                "minecraft:water", "minecraft:air", "minecraft:oak_planks", true, false, true));
    }

    @Test
    void blockedOrBrokeWaits() {
        assertEquals(Outcome.WAIT, BaseRepairRules.decide(
                "minecraft:air", "minecraft:air", "minecraft:dirt", true, true, true));
        assertEquals(Outcome.WAIT, BaseRepairRules.decide(
                "minecraft:air", "minecraft:air", "minecraft:dirt", true, false, false));
    }

    // --- modes ---

    @Test
    void modeParsingAndCycling() {
        assertEquals(Mode.FAST, Mode.parse("fast", Mode.NORMAL));
        assertEquals(Mode.NORMAL, Mode.parse(null, Mode.NORMAL));
        assertEquals(Mode.SLOW, Mode.parse("warp9", Mode.SLOW));
        assertEquals(Mode.OFF, Mode.TURBO.next());
        assertEquals(Mode.SLOW, Mode.OFF.next());
        assertEquals("Turbo", Mode.TURBO.label());
    }

    // --- which sanctuary covers a block ---

    @Test
    void coverIsInclusiveCircle() {
        assertTrue(BaseRepairRules.covers(0.5, 0.5, 128, 128.5, 0.5));
        assertFalse(BaseRepairRules.covers(0.5, 0.5, 128, 129.5, 0.5));
        assertFalse(BaseRepairRules.covers(0.5, 0.5, 128, 100.5, 100.5)); // corner of the square is outside
    }
}
