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
        // Age is measured from the epoch (a persisted reset point), not from tick 0 — so ops can
        // reset the pressure without touching the world's actual clock.
        double days = Math.max(0L, gameTimeTicks - p.epochTick()) / 24000.0;
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

    /**
     * Levels retained on death: {@code floor(level * min(max, base + perMilestone*reached))}.
     * The fraction GROWS with level (via milestones), so veterans lose proportionally less — a
     * death at level 100 keeps 50%, at level 1000 keeps 65% (defaults). Vanilla would zero it.
     */
    public static int deathKeptLevels(int level, int[] milestones, double base, double perMilestone, double max) {
        if (level <= 0) {
            return 0;
        }
        double frac = Math.min(max, base + perMilestone * milestonesReached(level, milestones));
        return (int) Math.floor(level * Math.max(0.0, frac));
    }

    // --- System 7: spawn-based wild-mob difficulty ---

    /** Mob power multiplier from distance beyond the nearest safe anchor: {@code min(max, 1 + perBlock*beyond)}. */
    public static double mobPowerMultiplier(double blocksBeyondSafe, double perBlock, double max) {
        return mobPowerMultiplier(blocksBeyondSafe, perBlock, max, 1.0);
    }

    /**
     * Mob power multiplier with a curve exponent: {@code min(max, 1 + (perBlock*beyond)^exponent)}.
     * Exponent 1 = linear (+1x per 1/perBlock blocks); above 1 the deep wildlands ramp superlinearly
     * (e.g. exponent 1.5 at 4000 blocks: 1 + 4^1.5 = 9x instead of 5x). Exponent is clamped to a
     * sane floor so a config typo can't invert the curve.
     */
    public static double mobPowerMultiplier(double blocksBeyondSafe, double perBlock, double max, double exponent) {
        double base = Math.max(0.0, blocksBeyondSafe) * Math.max(0.0, perBlock);
        double e = Math.max(0.1, exponent);
        if (e != 1.0 && base > 0.0) {
            base = Math.pow(base, e);
        }
        return Math.min(max, 1.0 + base);
    }

    /**
     * Exact inverse of the damage curve (below the cap): recover blocks-beyond-safe from a mob's
     * baked damage bonus ({@code = damageMultiplier - 1}). Used to un-scale XP on sanctuary revert
     * and to derive rabid-animal damage, so it must mirror {@link #mobPowerMultiplier} exactly.
     */
    public static double beyondFromDamageBonus(double damageBonus, double perBlock, double exponent) {
        if (perBlock <= 0.0 || damageBonus <= 0.0) {
            return 0.0;
        }
        double e = Math.max(0.1, exponent);
        double base = e != 1.0 ? Math.pow(damageBonus, 1.0 / e) : damageBonus;
        return base / perBlock;
    }

    /**
     * Fuzzy zone edges: jitter a spawn's effective distance by a gaussian sample scaled by
     * {@code fuzz} (a fraction of the distance; 0.12 → σ = 12%). The sample is clamped to ±3σ so an
     * outlier can't teleport the curve, and the result never goes below 0 (inside a sanctuary stays
     * absolutely safe). Near a tier boundary this gives the "might roll a tier up or down" feel:
     * at the default σ=12%, a spawn 10% shy of Savage range still comes out Savage ~18% of the time
     * (it needs a +0.93σ roll), and one sitting exactly on the line rolls up or down 50/50.
     */
    public static double fuzzedBeyond(double blocksBeyondSafe, double gaussianSample, double fuzz) {
        if (blocksBeyondSafe <= 0.0 || fuzz <= 0.0) {
            return Math.max(0.0, blocksBeyondSafe);
        }
        double g = Math.max(-3.0, Math.min(3.0, gaussianSample));
        return Math.max(0.0, blocksBeyondSafe * (1.0 + g * fuzz));
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

        /** Game time the age pressure is measured from (see {@code /sanctuary danger reset}). */
        default long epochTick() {
            return 0L;
        }
    }
}
