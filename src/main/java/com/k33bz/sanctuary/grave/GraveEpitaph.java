package com.k33bz.sanctuary.grave;

import java.util.Locale;

/**
 * Pure, game-independent rendering of a headstone's line-2 epitaph, blurring BOTH cause and time as
 * a grave ages. No Minecraft types here, so it is unit-testable. The caller ({@link Graves}) does
 * the game-coupled part — mapping the {@code DamageSource} to a {@link Cause} + killer name and
 * reading the in-game death day at capture — then this class renders the fuzzy string by REAL age.
 *
 * <p>Tiers (config-tunable day thresholds; defaults in {@code SanctuaryConfig}):
 * <ul>
 *   <li>age &lt; exactDays — exact cause + the in-game day: "Slain by a skeleton &middot; Day 16"</li>
 *   <li>age &lt; vagueDays — cause, time blurred: "Slain by a skeleton, some weeks past"</li>
 *   <li>age &lt; genericDays — cause collapses to a generic place: "Fell in the wilds, long ago"</li>
 *   <li>beyond — "Lost to time"</li>
 * </ul>
 */
public final class GraveEpitaph {
    private GraveEpitaph() {
    }

    /** Death-cause category captured at death. {@link #MOB} pairs with a killer name. */
    public enum Cause {
        MOB, FALL, BURN, DROWN, WITHER, VOID, GENERIC;

        /** Parse a stored cause id (case-insensitive), defaulting to GENERIC for null/unknown. */
        public static Cause parse(String id) {
            if (id == null) {
                return GENERIC;
            }
            try {
                return valueOf(id.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return GENERIC;
            }
        }
    }

    /**
     * Render the line-2 epitaph. {@code killerName} is used only for {@link Cause#MOB} (e.g.
     * "skeleton"); {@code deathDay} is the in-game day the death happened. {@code ageDays} is the
     * REAL days since death. Thresholds are ascending real-day cutoffs.
     */
    public static String epitaph(Cause cause, String killerName, long deathDay, double ageDays,
                                 double exactDays, double vagueDays, double genericDays) {
        if (ageDays >= genericDays) {
            return "Lost to time";
        }
        if (ageDays >= vagueDays) {
            // Cause collapses to a generic place; time is "long ago".
            return genericClause(cause) + ", long ago";
        }
        if (ageDays >= exactDays) {
            // Exact cause, blurred time.
            return causeClause(cause, killerName) + ", some weeks past";
        }
        // Fresh: exact cause + the exact in-game day.
        return causeClause(cause, killerName) + " · Day " + deathDay;
    }

    /** The exact cause clause (with the killer's name for a mob death). */
    static String causeClause(Cause cause, String killerName) {
        return switch (cause) {
            case MOB -> "Slain by " + article(killerName) + safeName(killerName);
            case FALL -> "Fell from a height";
            case BURN -> "Burned away";
            case DROWN -> "Drowned in the deep";
            case WITHER -> "Withered away";
            case VOID -> "Lost to the void";
            case GENERIC -> "Taken by the dark";
        };
    }

    /** The collapsed, generic clause used once a death is "long ago" — cause blurs to a place. */
    static String genericClause(Cause cause) {
        return switch (cause) {
            case VOID -> "Lost to the void";        // the void stays the void even long ago
            case WITHER -> "Withered in the wilds";
            default -> "Fell in the wilds";
        };
    }

    private static String safeName(String killerName) {
        return (killerName == null || killerName.isBlank()) ? "a foe" : killerName.trim();
    }

    /** "a"/"an" for a mob name (crude vowel check); "a foe" already carries its own article. */
    private static String article(String killerName) {
        String n = safeName(killerName);
        if (n.equals("a foe")) {
            return "";
        }
        char c = Character.toLowerCase(n.charAt(0));
        boolean vowel = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
        return vowel ? "an " : "a ";
    }
}
