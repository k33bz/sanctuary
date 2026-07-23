package com.k33bz.sanctuary;

import java.util.List;

/**
 * Pure, Minecraft-free logic for the sleep-progress broadcast (so it can be unit-tested).
 *
 * <p>Vanilla skips the night once enough OVERWORLD players are in bed — the count is governed by the
 * {@code players_sleeping_percentage} gamerule (default 50), and the requirement is
 * {@code max(1, ceil(activePlayers * pct / 100))}, matching vanilla's {@code SleepStatus}. This
 * builds the "who's resting, how many more are needed" line that {@link SleepTracker} broadcasts.
 */
public final class SleepProgress {
    private SleepProgress() {
    }

    /** Sleepers needed to skip the night, mirroring vanilla's {@code SleepStatus.sleepersNeeded}. */
    public static int required(int activePlayers, int percentage) {
        if (activePlayers <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(activePlayers * percentage / 100.0));
    }

    /** "Alice" / "Alice, Bob" / "Alice, Bob, Carol" — a plain comma join of the sleeper names. */
    public static String joinNames(List<String> names) {
        return String.join(", ", names);
    }

    /**
     * The broadcast line. When enough are resting it announces the night passing; otherwise it says
     * how many more sleepers are needed. {@code sleeping} is the count (== names.size()).
     */
    public static String message(List<String> names, int sleeping, int required) {
        String who = joinNames(names);
        String verb = names.size() == 1 ? "is" : "are";
        if (sleeping >= required) {
            return who + " " + verb + " resting (" + sleeping + "/" + required
                    + ") — the night passes.";
        }
        int more = required - sleeping;
        return who + " " + verb + " resting (" + sleeping + "/" + required + ") — "
                + more + " more " + (more == 1 ? "sleeper" : "sleepers") + " needed to pass the night.";
    }
}
