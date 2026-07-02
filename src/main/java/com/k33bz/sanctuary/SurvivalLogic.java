package com.k33bz.sanctuary;

/**
 * Pure, game-independent math for the XP Vitality systems.
 * No Minecraft types referenced here, so it can be unit-tested without a running game.
 */
public final class SurvivalLogic {
    private SurvivalLogic() {
    }

    /**
     * System 4 — world-danger multiplier (>= 1.0). Compounds three pressures:
     * world difficulty, time the world has been alive, and distance beyond the nearest safe anchor.
     */
    public static float worldDangerMultiplier(int difficultyId, long gameTimeTicks,
                                              double blocksBeyondSafe, DangerParams p) {
        float difficultyTerm = 1.0f + p.difficultyWeight() * Math.max(0, difficultyId);
        double days = Math.max(0L, gameTimeTicks) / 24000.0;
        float timeTerm = 1.0f + (float) (p.perDayWeight() * days);
        float distanceTerm = 1.0f + (float) (p.perBlockWeight() * Math.max(0.0, blocksBeyondSafe));
        float mult = difficultyTerm * timeTerm * distanceTerm;
        return Math.max(1.0f, Math.min(mult, p.maxMultiplier()));
    }

    /** System 1 — XP points to drain to heal {@code healAmount} health (half-heart = 1.0). */
    public static int regenXpCost(float healAmount, float xpPerHealth) {
        return (int) Math.ceil(Math.max(0.0f, healAmount) * xpPerHealth);
    }

    /**
     * System 3 — whole levels consumed to survive an otherwise-lethal hit.
     * v0.2.0 scales on the killing hit's magnitude; true overkill-beyond-remaining-HP is parked for v0.3.0.
     */
    public static int lethalSaveLevelCost(float incomingDamage, float levelsPerDamage, int minLevels) {
        int cost = (int) Math.ceil(Math.max(0.0f, incomingDamage) * levelsPerDamage);
        return Math.max(minLevels, cost);
    }

    // --- System 5: leveling-driven survivability (armor / bonus hearts / XP shield) ---

    /** Number of milestones at or below the given level. {@code milestones} must be ascending. */
    public static int milestonesReached(int level, int[] milestones) {
        int count = 0;
        for (int m : milestones) {
            if (level >= m) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /** Bonus max-health (in HP; 2.0 = one heart) from milestones passed. */
    public static double bonusHealth(int level, int[] milestones, double hpPerMilestone) {
        return milestonesReached(level, milestones) * hpPerMilestone;
    }

    /** System 2 (replacement) — vanilla armor points granted by level, capped. */
    public static double armorForLevel(int level, double perLevel, double max) {
        return Math.min(Math.max(0, level) * perLevel, max);
    }

    /**
     * System 6 — {@code OXYGEN_BONUS} attribute granted by level, capped. Vanilla makes air last
     * roughly {@code (bonus + 1) * 15s} underwater, so a high value effectively prevents drowning.
     */
    public static double oxygenBonusForLevel(int level, double perLevel, double max) {
        return Math.min(Math.max(0, level) * perLevel, max);
    }

    /**
     * Shield fraction = (level - lowerMilestone) / lowerMilestone, i.e. progress past the current
     * milestone measured against it — so level 15 → 15/10 - 1 = 0.5, level 40 → 40/25 - 1 = 0.6.
     * Below the first milestone it ramps as level / firstMilestone. Uncapped (caller clamps).
     */
    public static double shieldFraction(int level, int[] milestones) {
        if (level <= 0 || milestones.length == 0) {
            return 0.0;
        }
        int lower = 0;
        for (int m : milestones) {
            if (level >= m) {
                lower = m;
            } else {
                break;
            }
        }
        int denom = lower > 0 ? lower : milestones[0];
        if (denom <= 0) {
            return 0.0;
        }
        return Math.max(0.0, (double) (level - lower) / denom);
    }

    /** Absorption ("XP shield") HP: {@code min(shieldFraction, maxFraction) * maxHealth}. */
    public static float shieldAmount(int level, int[] milestones, double maxHealth, double maxFraction) {
        double f = Math.min(shieldFraction(level, milestones), maxFraction);
        return (float) Math.max(0.0, f * maxHealth);
    }

    /**
     * Amplifier for the vanilla Absorption effect delivering {@code targetHp} (4 HP per level).
     * Returns -1 (no shield) below one heart's worth. The effect is the only way to hold player
     * absorption — a raw {@code setAbsorptionAmount} is wiped by vanilla each tick.
     */
    public static int absorptionAmplifier(float targetHp) {
        if (targetHp < 2.0f) {
            return -1;
        }
        return Math.max(0, Math.round(targetHp / 4.0f) - 1);
    }

    /** Seconds out of combat before the shield refills: {@code max(min, base - reached*perMilestone)}. */
    public static double shieldRegenCooldownSeconds(int milestonesReached, double base,
                                                    double perMilestone, double min) {
        return Math.max(min, base - milestonesReached * perMilestone);
    }

    // --- System 7: spawn-based wild-mob difficulty ---

    /** Mob power multiplier from distance beyond the nearest safe anchor: {@code min(max, 1 + perBlock*beyond)}. */
    public static double mobPowerMultiplier(double blocksBeyondSafe, double perBlock, double max) {
        return Math.min(max, 1.0 + Math.max(0.0, blocksBeyondSafe) * Math.max(0.0, perBlock));
    }

    /** Threat tier 0–4 from a damage-modifier bonus (= damageMultiplier − 1), for names/particles. */
    public static int mobTier(double damageBonus) {
        if (damageBonus < 0.5) {
            return 0;
        }
        if (damageBonus < 1.5) {
            return 1;
        }
        if (damageBonus < 3.0) {
            return 2;
        }
        if (damageBonus < 5.0) {
            return 3;
        }
        return 4;
    }

    /** Immutable view of the danger-scaling knobs, so this class never imports the config type. */
    public interface DangerParams {
        float difficultyWeight();

        double perDayWeight();

        double perBlockWeight();

        float maxMultiplier();
    }
}
