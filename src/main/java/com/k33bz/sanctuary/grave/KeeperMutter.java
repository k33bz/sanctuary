package com.k33bz.sanctuary.grave;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure, game-independent selection logic for the Gravekeeper's ambient <b>mutterings</b> (0.8.5) —
 * atmospheric flavor lines he says quietly to himself as he patrols. No Minecraft types here, so it
 * is unit-testable; {@link Gravekeeper} does the game-coupled part (per-keeper cadence, the proximity
 * gate against {@code serverLevel.players()}, and delivering the chosen line only to nearby players).
 *
 * <p>The backbone is a pool of somber/cryptic static lines (indexed translation keys
 * {@code sanctuary.gravekeeper.mutter.0..N}, resolved by the caller from {@code en_us.json}). On top
 * of that, lower-weight CONTEXTUAL lines are sprinkled in when their condition holds: night/day lines,
 * a "many graves" line, and — the payoff — <b>grave-memory</b> lines drawn from the death data the
 * epitaph system stored on the grave. As a grave's visible headstone weathers and fuzzes to a generic
 * epitaph, the keeper still remembers the TRUE cause + day (the raw {@code deathCause}/{@code deathDay}
 * fields on the grave store, which survive the render-time fuzzing) and can mutter it — the one who
 * still knows. A grave with NO captured cause (legacy/predates capture) yields a poignant "lost memory"
 * line instead.
 *
 * <p>Selection is a pure function of the {@link Context}, the pool size, the {@code lastIndexKey}
 * (no-immediate-repeat, per keeper) and the injected {@link Rng} draws — so weighting, contextual
 * gating, templating, and the daysAgo math are all deterministically testable.
 */
public final class KeeperMutter {
    private KeeperMutter() {
    }

    // --- config defaults (mirrored in SanctuaryConfig) ---

    /** Default proximity radius (blocks): a player must be this close for a mutter to fire. */
    public static final double DEFAULT_RADIUS = 14.0;
    /** Default minimum jittered interval between mutters, in ticks (~40s). */
    public static final int DEFAULT_INTERVAL_MIN = 800;
    /** Default maximum jittered interval between mutters, in ticks (~90s). */
    public static final int DEFAULT_INTERVAL_MAX = 1800;
    /** Grave count in a yard at/above which the "so very many" line becomes eligible. */
    public static final int MANY_GRAVES_THRESHOLD = 8;

    /**
     * The kind of chosen mutter, so the caller knows how to resolve it: a {@link #STATIC} pool line
     * (translation key {@code sanctuary.gravekeeper.mutter.<index>}) or a {@link #CONTEXTUAL} line
     * (its own translation key, already templated in {@link Line#text}).
     */
    public enum Kind { STATIC, CONTEXTUAL }

    /** A chosen mutter: for STATIC, {@code index} into the pool; for CONTEXTUAL, the rendered text. */
    public static final class Line {
        public final Kind kind;
        /** Pool index for a STATIC line (else -1). Doubles as the no-immediate-repeat key. */
        public final int index;
        /** For CONTEXTUAL, the fully-templated line text; for STATIC, the translation KEY. */
        public final String text;
        /** The translation KEY the caller should resolve (STATIC) / already-final text (CONTEXTUAL). */
        public final String key;

        Line(Kind kind, int index, String key, String text) {
            this.kind = kind;
            this.index = index;
            this.key = key;
            this.text = text;
        }

        static Line statik(int index) {
            return new Line(Kind.STATIC, index,
                    "sanctuary.gravekeeper.mutter." + index, null);
        }

        static Line contextual(String key, String text) {
            return new Line(Kind.CONTEXTUAL, -1, key, text);
        }
    }

    /** A nearby grave's memory, as the keeper knows it (raw store fields, NOT the fuzzed headstone). */
    public static final class GraveMemory {
        public final String name;       // ownerName
        public final GraveEpitaph.Cause cause; // parsed cause (GENERIC if unknown)
        public final String killerName; // for MOB deaths, else null
        public final long deathDay;     // in-game day of death (0 = legacy/unknown)
        public final boolean hasCause;  // false = no structured cause captured → lost-memory

        public GraveMemory(String name, GraveEpitaph.Cause cause, String killerName,
                           long deathDay, boolean hasCause) {
            this.name = name;
            this.cause = cause;
            this.killerName = killerName;
            this.deathDay = deathDay;
            this.hasCause = hasCause;
        }
    }

    /** Everything the pure selector needs about the world at this tick. */
    public static final class Context {
        public final int poolSize;      // number of static lines available (keys 0..poolSize-1)
        public final boolean night;     // is it night in the level
        public final int graveCount;    // graves resting in this yard
        public final long currentDay;   // in-game day now (for daysAgo math)
        /** The grave the keeper is lingering beside / nearest to, or null if none is near. */
        public final GraveMemory nearGrave;

        public Context(int poolSize, boolean night, int graveCount, long currentDay,
                       GraveMemory nearGrave) {
            this.poolSize = poolSize;
            this.night = night;
            this.graveCount = graveCount;
            this.currentDay = currentDay;
            this.nearGrave = nearGrave;
        }
    }

    /** Injected randomness so selection is deterministic under test. */
    public interface Rng {
        /** Uniform double in [0,1). */
        double nextDouble();

        /** Uniform int in [0,bound). */
        int nextInt(int bound);
    }

    // --- proximity + cadence (pure predicates the caller reuses) ---

    /**
     * Whether {@code (px,pz)} lies within {@code radius} of {@code (kx,kz)} horizontally. The caller
     * uses this as the proximity gate — a mutter fires ONLY if at least one player passes it, and the
     * line is delivered ONLY to the players that do. Removing this gate (always-true) is the red proof
     * for test (a): the keeper would then mutter with nobody near.
     */
    public static boolean withinRadius(double kx, double kz, double px, double pz, double radius) {
        double dx = px - kx;
        double dz = pz - kz;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** A fresh jittered interval in {@code [min, max]} ticks; {@code max} is treated as {@code >= min}. */
    public static int rollInterval(int min, int max, Rng rng) {
        int lo = Math.max(1, min);
        int hi = Math.max(lo, max);
        int span = hi - lo;
        return lo + (span <= 0 ? 0 : rng.nextInt(span + 1));
    }

    // --- cause → phrase (reused/extended from the epitaph vocabulary) ---

    /**
     * Map a captured death to the keeper's spoken phrase for it ({@code {cause}} in the templates) —
     * e.g. a skeleton → "a skeleton's arrow", fall → "the long fall", lava/fire → "the burning",
     * creeper → "the blast", drowning → "the water", void → "the void", zombie → "cold hands".
     * MOB deaths phrase by the specific killer name where we have a flavor for it, else a generic
     * "{killer}'s hand". Returns {@code null} when there is no usable cause (the lost-memory path).
     */
    public static String causePhrase(GraveEpitaph.Cause cause, String killerName) {
        if (cause == null) {
            return null;
        }
        switch (cause) {
            case FALL:
                return "the long fall";
            case BURN:
                return "the burning";
            case DROWN:
                return "the water";
            case WITHER:
                return "the withering";
            case VOID:
                return "the void";
            case MOB:
                return mobPhrase(killerName);
            case GENERIC:
            default:
                return null; // no known method → lost memory
        }
    }

    /** Flavor phrases for specific mob killers, falling back to "{killer}'s hand" then null. */
    private static String mobPhrase(String killerName) {
        if (killerName == null || killerName.isBlank()) {
            return null;
        }
        String k = killerName.trim().toLowerCase(Locale.ROOT);
        // Known flavors — extend as the bestiary grows.
        if (k.contains("skeleton") || k.contains("stray") || k.contains("bogged")) {
            return "a skeleton's arrow";
        }
        if (k.contains("creeper")) {
            return "the blast";
        }
        if (k.contains("zombie") || k.contains("husk") || k.contains("drowned")) {
            return "cold hands";
        }
        if (k.contains("spider")) {
            return "the spider's fangs";
        }
        if (k.contains("witch")) {
            return "the witch's brew";
        }
        if (k.contains("warden")) {
            return "the deep dark";
        }
        if (k.contains("wither")) {
            return "the withering";
        }
        if (k.contains("blaze") || k.contains("ghast")) {
            return "the burning";
        }
        if (k.contains("enderman")) {
            return "the ender's rage";
        }
        if (k.contains("piglin") || k.contains("pillager") || k.contains("vindicator")
                || k.contains("illager") || k.contains("ravager")) {
            return "a brute's blade";
        }
        // A named foe we have no set flavor for still reads: "poor X… a phantom's hand."
        return "a " + k + "'s hand";
    }

    // --- the grave-memory templates ---

    /** Template ids for grave-memory / lost-memory lines (translation keys mirror these in en_us). */
    public enum GraveTemplate {
        // Has a known cause + day:
        STONE_WONT_SAY,   // "poor {name}… {cause}. and the stone won't say it anymore."
        TAKEN_BY_DAY,     // "{name}… taken by {cause}. day {day}, it was."
        MARKER_FORGOTTEN, // "the marker's forgotten, but I haven't — {name}, {cause}."
        DAYS_LAIN,        // "{daysAgo} days {name}'s lain here… {cause}, it was."
        I_WAS_THERE,      // "aye, {name}… {cause}. I was there."
        // Plain name (from the base spec) — used when we have a name but no cause phrase:
        POOR_NAME,        // "poor {name}… you rest easy now."
        STILL_TEND,       // "{name}… I still tend your plot."
        NOT_FORGOTTEN,    // "gone, but not forgotten — {name}."
        // Lost memory — the poignant edge case (no cause captured / already forgotten):
        HOW_DID_YOU_GO,   // "how did you go, {name}? …even I forget now."
        NAME_ALL_LEFT;    // "so long ago… the name's all that's left."

        /** Whether this template needs a {cause} phrase (so it's only eligible when one exists). */
        public boolean needsCause() {
            return this == STONE_WONT_SAY || this == TAKEN_BY_DAY || this == MARKER_FORGOTTEN
                    || this == DAYS_LAIN || this == I_WAS_THERE;
        }
    }

    /** In-game days since the death (never negative); 0 when the death day is unknown/legacy. */
    public static long daysAgo(long currentDay, long deathDay) {
        if (deathDay <= 0L) {
            return 0L;
        }
        return Math.max(0L, currentDay - deathDay);
    }

    /**
     * Render a grave-memory line. The routing is the whole payoff:
     * <ul>
     *   <li><b>Has a usable cause phrase</b> (the keeper still knows the method) → mostly a cause-bearing
     *       template revealing what the weathered stone no longer says, occasionally a gentle plain-name
     *       line for variety.</li>
     *   <li><b>No usable cause</b> (legacy grave / cause never captured / already fuzzed to generic) →
     *       the poignant <b>lost-memory</b> line — the keeper forgets too. A name is templated in when
     *       we have one; a nameless grave uses the name-free lost line.</li>
     * </ul>
     * {@code null} memory is tolerated (→ lost memory). Pure given the memory + the RNG draws.
     */
    public static Line graveLine(GraveMemory mem, long currentDay, Rng rng) {
        boolean haveName = mem != null && mem.name != null && !mem.name.isBlank();
        String cause = mem == null ? null
                : (mem.hasCause ? causePhrase(mem.cause, mem.killerName) : null);

        // No usable cause → lost memory (the keeper forgets), regardless of whether a name survives.
        if (cause == null) {
            return lostMemory(haveName ? mem.name : null, rng);
        }

        // We know the cause. Mostly reveal it (the payoff); occasionally a gentle plain-name line.
        // First draw decides reveal-vs-plain; a plain line needs a name to template.
        boolean plain = haveName && rng.nextInt(4) == 0; // ~25% gentle plain-name variety
        GraveTemplate[] pool = plain
                ? new GraveTemplate[] {
                        GraveTemplate.POOR_NAME, GraveTemplate.STILL_TEND, GraveTemplate.NOT_FORGOTTEN}
                : new GraveTemplate[] {
                        GraveTemplate.STONE_WONT_SAY, GraveTemplate.TAKEN_BY_DAY,
                        GraveTemplate.MARKER_FORGOTTEN, GraveTemplate.DAYS_LAIN, GraveTemplate.I_WAS_THERE};
        GraveTemplate t = pool[rng.nextInt(pool.length)];
        // A cause-bearing template with no name still reads ("poor someone… the blast."); fill() guards.
        return Line.contextual("sanctuary.gravekeeper.mutter.grave." + t.name().toLowerCase(Locale.ROOT),
                fill(t, mem, plain ? null : cause, currentDay));
    }

    private static Line lostMemory(String name, Rng rng) {
        boolean withName = name != null && !name.isBlank();
        GraveTemplate t;
        if (withName) {
            t = rng.nextInt(2) == 0 ? GraveTemplate.HOW_DID_YOU_GO : GraveTemplate.NAME_ALL_LEFT;
        } else {
            t = GraveTemplate.NAME_ALL_LEFT; // no name → the one line that needs none
        }
        GraveMemory m = new GraveMemory(name, GraveEpitaph.Cause.GENERIC, null, 0L, false);
        return Line.contextual("sanctuary.gravekeeper.mutter.grave." + t.name().toLowerCase(Locale.ROOT),
                fill(t, m, null, 0L));
    }

    /** Fill a template's placeholders. {@code cause} may be null for name-only/lost templates. */
    static String fill(GraveTemplate t, GraveMemory mem, String cause, long currentDay) {
        String name = mem == null || mem.name == null ? "someone" : mem.name;
        long day = mem == null ? 0L : mem.deathDay;
        long ago = mem == null ? 0L : daysAgo(currentDay, mem.deathDay);
        switch (t) {
            case STONE_WONT_SAY:
                return "poor " + name + "… " + cause + ". and the stone won't say it anymore.";
            case TAKEN_BY_DAY:
                return name + "… taken by " + cause + ". day " + day + ", it was.";
            case MARKER_FORGOTTEN:
                return "the marker's forgotten, but I haven't — " + name + ", " + cause + ".";
            case DAYS_LAIN:
                return ago + " days " + name + "'s lain here… " + cause + ", it was.";
            case I_WAS_THERE:
                return "aye, " + name + "… " + cause + ". I was there.";
            case POOR_NAME:
                return "poor " + name + "… you rest easy now.";
            case STILL_TEND:
                return name + "… I still tend your plot.";
            case NOT_FORGOTTEN:
                return "gone, but not forgotten — " + name + ".";
            case HOW_DID_YOU_GO:
                return "how did you go, " + name + "? …even I forget now.";
            case NAME_ALL_LEFT:
            default:
                return "so long ago… the name's all that's left.";
        }
    }

    // --- contextual (non-grave) lines: night / day / many-graves ---

    /** Night contextual lines (translation keys mirror the index). */
    static final String[] NIGHT_LINES = {
            "the veil thins after dark…",
            "they whisper louder at night.",
            "something walks that shouldn't…",
    };
    /** Day contextual lines. */
    static final String[] DAY_LINES = {
            "sunlight won't warm the buried.",
    };
    /** Many-graves contextual line. */
    static final String MANY_GRAVES_LINE = "so very many now…";

    // --- the top-level selector ---

    /**
     * Choose ONE mutter for this tick. Weighting: the static somber pool is the backbone (majority),
     * with contextual lines sprinkled in at a lower rate. When the keeper is beside a grave that HAS
     * captured death data, grave-MEMORY lines are FAVORED (the payoff). No-immediate-repeat is enforced
     * for STATIC lines against {@code lastStaticIndex} (-1 = none yet).
     *
     * <p>Roll order (all thresholds are the "eligible" gates; the pool is always the fallback):
     * <ol>
     *   <li>If a grave is near AND has a usable cause → high chance ({@code graveMemoryWeight}) of a
     *       grave-memory line. If near but only a name / lost memory → a smaller chance.</li>
     *   <li>Else a smaller chance ({@code contextualWeight}) of a night/day/many-graves line when one
     *       is eligible.</li>
     *   <li>Else a static pool line (no immediate repeat).</li>
     * </ol>
     *
     * @param lastStaticIndex the pool index chosen last time for this keeper (-1 if none), so we never
     *                        pick the same static line twice in a row
     */
    public static Line select(Context ctx, int lastStaticIndex,
                              double graveMemoryWeight, double contextualWeight, Rng rng) {
        // (1) Grave memory — favored when a grave with real death data is near.
        if (ctx.nearGrave != null) {
            boolean usable = ctx.nearGrave.hasCause
                    && causePhrase(ctx.nearGrave.cause, ctx.nearGrave.killerName) != null;
            double chance = usable ? graveMemoryWeight : (graveMemoryWeight * 0.5);
            if (rng.nextDouble() < chance) {
                return graveLine(ctx.nearGrave, ctx.currentDay, rng);
            }
        }

        // (2) Contextual night/day/many-graves — a minority sprinkle.
        List<Line> eligible = contextualEligible(ctx);
        if (!eligible.isEmpty() && rng.nextDouble() < contextualWeight) {
            return eligible.get(rng.nextInt(eligible.size()));
        }

        // (3) Backbone: a static pool line, never repeating the immediately-previous one.
        return staticLine(ctx.poolSize, lastStaticIndex, rng);
    }

    /** The eligible contextual (non-grave) lines given the world state. */
    static List<Line> contextualEligible(Context ctx) {
        List<Line> out = new ArrayList<>();
        if (ctx.night) {
            for (int i = 0; i < NIGHT_LINES.length; i++) {
                out.add(Line.contextual("sanctuary.gravekeeper.mutter.night." + i, NIGHT_LINES[i]));
            }
        } else {
            for (int i = 0; i < DAY_LINES.length; i++) {
                out.add(Line.contextual("sanctuary.gravekeeper.mutter.day." + i, DAY_LINES[i]));
            }
        }
        if (ctx.graveCount >= MANY_GRAVES_THRESHOLD) {
            out.add(Line.contextual("sanctuary.gravekeeper.mutter.manygraves", MANY_GRAVES_LINE));
        }
        return out;
    }

    /**
     * Pick a static pool line, avoiding {@code lastIndex} (no immediate repeat). With a pool of one
     * (or none), repeat-avoidance is impossible and the single line is returned. Draws in
     * {@code [0, poolSize-1)} and shifts past {@code lastIndex} to distribute uniformly over the
     * remaining lines.
     */
    static Line staticLine(int poolSize, int lastIndex, Rng rng) {
        if (poolSize <= 0) {
            return Line.statik(0); // degenerate (no keys) — caller resolves to blank/skip
        }
        if (poolSize == 1 || lastIndex < 0 || lastIndex >= poolSize) {
            return Line.statik(rng.nextInt(poolSize));
        }
        int idx = rng.nextInt(poolSize - 1);
        if (idx >= lastIndex) {
            idx++; // skip the last-used index → uniform over the other poolSize-1 lines
        }
        return Line.statik(idx);
    }
}
