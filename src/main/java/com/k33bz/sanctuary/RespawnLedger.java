package com.k33bz.sanctuary;

/**
 * Pure, game-independent update rule for the repeat-death escalation ledger.
 * No Minecraft types referenced here, so it can be unit-tested without a running game.
 *
 * <p>The time-decay itself lives in {@link SurvivalLogic#decayedEscalation} (already tested); this
 * class is the remaining ledger step — apply the milestone reset, then accumulate this death's
 * surcharge for the next one. {@link RespawnChoice} keeps the player/stat plumbing and calls this,
 * so the extracted logic returns exactly what the inline code did.
 */
public final class RespawnLedger {
    private RespawnLedger() {
    }

    /** Outcome of a ledger update: what to price this death with, and what to store for the next. */
    public static final class Update {
        /** Escalation this death is priced with (post-decay, post-milestone-reset). */
        public final double priced;
        /** Escalation to persist for the next death ({@code priced + perDeath}). */
        public final double nextStored;

        public Update(double priced, double nextStored) {
            this.priced = priced;
            this.nextStored = nextStored;
        }
    }

    /**
     * Decay {@code prevEscalation} by {@code minutesPlayed}, reset it to 0 if a new milestone was
     * reached ({@code milestonesNow > prevMilestones}), price this death with the result, then bump
     * the stored value by {@code perDeath} for next time. Mirrors {@code RespawnChoice.onDeath}:
     * the RETURNED (priced) escalation is charged now; {@code nextStored} is what persists.
     */
    public static Update update(double prevEscalation, double minutesPlayed, double decayPer10Min,
                                int milestonesNow, int prevMilestones, double perDeath) {
        double e = SurvivalLogic.decayedEscalation(prevEscalation, minutesPlayed, decayPer10Min);
        if (milestonesNow > prevMilestones) {
            e = 0.0; // a new milestone cleanses the death toll
        }
        return new Update(e, e + perDeath);
    }
}
