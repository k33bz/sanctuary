package com.k33bz.sanctuary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalLogicTest {

    private static final int[] MILES = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    private static SurvivalLogic.DangerParams danger(float diffW, double dayW, double blockW, float max) {
        return new SurvivalLogic.DangerParams() {
            public float difficultyWeight() {
                return diffW;
            }

            public double perDayWeight() {
                return dayW;
            }

            public double perBlockWeight() {
                return blockW;
            }

            public float maxMultiplier() {
                return max;
            }
        };
    }

    @Test
    void worldDangerNeverBelowOneAndRespectsCap() {
        SurvivalLogic.DangerParams p = danger(0.15f, 0.02, 0.0005, 4.0f);
        assertEquals(1.0f, SurvivalLogic.worldDangerMultiplier(0, 0, 0, p), 1e-6);
        assertTrue(SurvivalLogic.worldDangerMultiplier(3, 2_400_000L, 5000, p) <= 4.0f);
        assertTrue(SurvivalLogic.worldDangerMultiplier(3, 240_000L, 1000, p) > 1.0f);
    }

    @Test
    void regenCostRoundsUp() {
        assertEquals(2, SurvivalLogic.regenXpCost(1.0f, 2.0f));
        assertEquals(3, SurvivalLogic.regenXpCost(1.0f, 2.5f)); // ceil(2.5)
    }

    @Test
    void lethalSaveRespectsMinimumAndScales() {
        assertEquals(1, SurvivalLogic.lethalSaveLevelCost(0.5f, 0.5f, 1));   // min floor
        assertEquals(10, SurvivalLogic.lethalSaveLevelCost(20.0f, 0.5f, 1)); // 20 * 0.5
    }

    @Test
    void milestonesAndBonusHearts() {
        assertEquals(0, SurvivalLogic.milestonesReached(9, MILES));
        assertEquals(1, SurvivalLogic.milestonesReached(10, MILES));
        assertEquals(2, SurvivalLogic.milestonesReached(40, MILES));  // 10, 25
        assertEquals(4, SurvivalLogic.milestonesReached(120, MILES)); // 10, 25, 50, 100
        assertEquals(8.0, SurvivalLogic.bonusHealth(120, MILES, 2.0), 1e-9); // 4 hearts
    }

    @Test
    void shieldFractionMatchesDesignExamples() {
        // Design spec: level 15 -> 15/10 - 1 = 0.50, level 40 -> 40/25 - 1 = 0.60
        assertEquals(0.5, SurvivalLogic.shieldFraction(15, MILES), 1e-9);
        assertEquals(0.6, SurvivalLogic.shieldFraction(40, MILES), 1e-9);
        assertEquals(0.0, SurvivalLogic.shieldFraction(10, MILES), 1e-9); // resets when a milestone is crossed
        assertEquals(0.5, SurvivalLogic.shieldFraction(5, MILES), 1e-9);  // below first milestone: 5/10
    }

    @Test
    void shieldAmountClampsAndScalesWithHealth() {
        // Level 40: 60% of 20 HP = 12, under a generous cap.
        assertEquals(12.0f, SurvivalLogic.shieldAmount(40, MILES, 20.0, 2.0), 1e-4);
        // Just before level 25 the raw fraction is 1.4; a 1.0 cap clamps it -> full 20 HP shield.
        assertEquals(20.0f, SurvivalLogic.shieldAmount(24, MILES, 20.0, 1.0), 1e-4);
    }

    @Test
    void absorptionAmplifierStepsAndFloor() {
        assertEquals(-1, SurvivalLogic.absorptionAmplifier(1.9f)); // below one heart -> no shield
        assertEquals(0, SurvivalLogic.absorptionAmplifier(4.0f));  // 4 HP -> level I
        assertEquals(1, SurvivalLogic.absorptionAmplifier(8.8f));  // ~2 hearts -> level II (8 HP)
        assertEquals(3, SurvivalLogic.absorptionAmplifier(14.4f)); // -> level IV (16 HP)
    }

    @Test
    void armorCapsAtMax() {
        assertEquals(2.5, SurvivalLogic.armorForLevel(10, 0.25, 20), 1e-9);
        assertEquals(20.0, SurvivalLogic.armorForLevel(1000, 0.25, 20), 1e-9); // capped
    }

    @Test
    void shieldCooldownShrinksPerMilestoneWithFloor() {
        // base 10s, -1s per milestone, floor 1s
        assertEquals(10.0, SurvivalLogic.shieldRegenCooldownSeconds(0, 10, 1, 1), 1e-9);
        assertEquals(6.0, SurvivalLogic.shieldRegenCooldownSeconds(4, 10, 1, 1), 1e-9);  // 4 milestones
        assertEquals(1.0, SurvivalLogic.shieldRegenCooldownSeconds(20, 10, 1, 1), 1e-9); // floored
    }

    @Test
    void mobPowerScalesWithDistanceAndCaps() {
        assertEquals(1.0, SurvivalLogic.mobPowerMultiplier(0, 0.0015, 8), 1e-9);       // in safe zone
        assertEquals(2.5, SurvivalLogic.mobPowerMultiplier(1000, 0.0015, 8), 1e-9);    // 1 + 1.5
        assertEquals(8.0, SurvivalLogic.mobPowerMultiplier(100000, 0.0015, 8), 1e-9);  // capped
    }

    @Test
    void mobTierBoundaries() {
        assertEquals(0, SurvivalLogic.mobTier(0.2));
        assertEquals(1, SurvivalLogic.mobTier(1.0));
        assertEquals(2, SurvivalLogic.mobTier(2.0));
        assertEquals(3, SurvivalLogic.mobTier(4.0));
        assertEquals(4, SurvivalLogic.mobTier(6.0));
    }

    @Test
    void oxygenBonusScalesAndCaps() {
        assertEquals(500.0, SurvivalLogic.oxygenBonusForLevel(500, 1.0, 1000), 1e-9); // ~2h underwater
        assertEquals(1000.0, SurvivalLogic.oxygenBonusForLevel(5000, 1.0, 1000), 1e-9); // capped
    }

    @Test
    void curveExponentIsLinearAtOneAndSuperlinearAbove() {
        // linear default: +1x per 1000 blocks
        assertEquals(5.0, SurvivalLogic.mobPowerMultiplier(4000, 0.001, 60, 1.0), 1e-9);
        assertEquals(SurvivalLogic.mobPowerMultiplier(4000, 0.001, 60),
                SurvivalLogic.mobPowerMultiplier(4000, 0.001, 60, 1.0), 1e-9);
        // exponent 1.5: 1 + 4^1.5 = 9
        assertEquals(9.0, SurvivalLogic.mobPowerMultiplier(4000, 0.001, 60, 1.5), 1e-9);
        // still capped
        assertEquals(60.0, SurvivalLogic.mobPowerMultiplier(59000, 0.001, 60, 2.0), 1e-9);
    }

    @Test
    void beyondFromDamageBonusInvertsTheCurve() {
        for (double e : new double[]{1.0, 1.5, 2.0}) {
            double mult = SurvivalLogic.mobPowerMultiplier(3200, 0.001, 60, e);
            assertEquals(3200, SurvivalLogic.beyondFromDamageBonus(mult - 1.0, 0.001, e), 1e-6);
        }
    }

    @Test
    void fuzzedBeyondClampsAndNeverGoesNegative() {
        assertEquals(2000, SurvivalLogic.fuzzedBeyond(2000, 0.0, 0.12), 1e-9);       // no jitter at mean
        assertEquals(2240, SurvivalLogic.fuzzedBeyond(2000, 1.0, 0.12), 1e-9);       // +1 sigma = +12%
        assertEquals(2720, SurvivalLogic.fuzzedBeyond(2000, 99.0, 0.12), 1e-9);      // clamped to +3 sigma
        assertEquals(1280, SurvivalLogic.fuzzedBeyond(2000, -99.0, 0.12), 1e-9);     // clamped to -3 sigma
        assertEquals(0.0, SurvivalLogic.fuzzedBeyond(100, -99.0, 0.5), 1e-9);        // floors at 0
        assertEquals(0.0, SurvivalLogic.fuzzedBeyond(-5, 1.0, 0.12), 1e-9);          // safe zone stays safe
    }

    @Test
    void feralEggHatchOutcomeFollowsStarTable() {
        // 0★ row: 90% normal / 0% down / 9% same / 1% up
        assertEquals(-1, SurvivalLogic.feralEggHatchOutcome(3, 0, 0.89));   // normal chick
        assertEquals(3, SurvivalLogic.feralEggHatchOutcome(3, 0, 0.90));    // first same-roll
        assertEquals(3, SurvivalLogic.feralEggHatchOutcome(3, 0, 0.9899));
        assertEquals(4, SurvivalLogic.feralEggHatchOutcome(3, 0, 0.99));    // the 1% miracle
        // 1★ row: 75/5/19/1
        assertEquals(-1, SurvivalLogic.feralEggHatchOutcome(3, 1, 0.74));
        assertEquals(2, SurvivalLogic.feralEggHatchOutcome(3, 1, 0.75));    // down band starts
        assertEquals(2, SurvivalLogic.feralEggHatchOutcome(3, 1, 0.7999));
        assertEquals(3, SurvivalLogic.feralEggHatchOutcome(3, 1, 0.80));
        assertEquals(4, SurvivalLogic.feralEggHatchOutcome(3, 1, 0.99));
        // 4★ row: 33/10/47/10
        assertEquals(-1, SurvivalLogic.feralEggHatchOutcome(3, 4, 0.32));
        assertEquals(2, SurvivalLogic.feralEggHatchOutcome(3, 4, 0.40));
        assertEquals(3, SurvivalLogic.feralEggHatchOutcome(3, 4, 0.50));
        assertEquals(4, SurvivalLogic.feralEggHatchOutcome(3, 4, 0.95));
        // 5★ row: 25/10/45/20 — the 1-in-5 climb
        assertEquals(-1, SurvivalLogic.feralEggHatchOutcome(3, 5, 0.24));
        assertEquals(2, SurvivalLogic.feralEggHatchOutcome(3, 5, 0.30));
        assertEquals(3, SurvivalLogic.feralEggHatchOutcome(3, 5, 0.79));
        assertEquals(4, SurvivalLogic.feralEggHatchOutcome(3, 5, 0.80));
        // clamps: Savage can't slip below, Nightmare can't climb above
        assertEquals(2, SurvivalLogic.feralEggHatchOutcome(2, 5, 0.30));
        assertEquals(4, SurvivalLogic.feralEggHatchOutcome(4, 5, 0.90));
        // out-of-range stars clamp to the table edges
        assertEquals(4, SurvivalLogic.feralEggHatchOutcome(3, 99, 0.80));  // treated as 5★
        assertEquals(-1, SurvivalLogic.feralEggHatchOutcome(3, -1, 0.89)); // treated as 0★
        // generation → star cap
        assertEquals(0, SurvivalLogic.feralEggStars(0));
        assertEquals(3, SurvivalLogic.feralEggStars(3));
        assertEquals(5, SurvivalLogic.feralEggStars(12));
    }

    @Test
    void respawnCostScalesWithEscalationAndFloors() {
        assertEquals(50, SurvivalLogic.respawnCostLevels(1000, 0.05, 0.0, 1));   // 5% of 1000
        assertEquals(63, SurvivalLogic.respawnCostLevels(1000, 0.05, 0.25, 1));  // +25% toll, ceil
        assertEquals(150, SurvivalLogic.respawnCostLevels(1000, 0.15, 0.0, 1));  // resurrect base
        assertEquals(1, SurvivalLogic.respawnCostLevels(5, 0.05, 0.0, 1));       // floor
        assertEquals(3, SurvivalLogic.respawnCostLevels(0, 0.05, 0.0, 3));       // floor at zero level
    }

    @Test
    void escalationDecaysWithPlayTimeAndFloorsAtZero() {
        // 0.25 toll, shedding 0.01 per 10 min: 100 minutes sheds 0.10
        assertEquals(0.15, SurvivalLogic.decayedEscalation(0.25, 100, 0.01), 1e-9);
        assertEquals(0.0, SurvivalLogic.decayedEscalation(0.25, 100000, 0.01), 1e-9); // floors
        assertEquals(0.25, SurvivalLogic.decayedEscalation(0.25, 0, 0.01), 1e-9);     // no time, no decay
    }

    @Test
    void deathRetentionScalesWithLevel() {
        // level 15: 1 milestone -> 35% -> keeps 5
        assertEquals(5, SurvivalLogic.deathKeptLevels(15, MILES, 0.30, 0.05, 0.80));
        // level 100: 4 milestones -> 50% -> keeps 50
        assertEquals(50, SurvivalLogic.deathKeptLevels(100, MILES, 0.30, 0.05, 0.80));
        // level 1000: 7 milestones -> 65% -> keeps 650
        assertEquals(650, SurvivalLogic.deathKeptLevels(1000, MILES, 0.30, 0.05, 0.80));
        // cap respected
        assertEquals(4000, SurvivalLogic.deathKeptLevels(5000, MILES, 0.30, 0.20, 0.80));
        assertEquals(0, SurvivalLogic.deathKeptLevels(0, MILES, 0.30, 0.05, 0.80));
    }
}
