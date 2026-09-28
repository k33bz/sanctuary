package com.k33bz.sanctuary.anchor;

import java.util.List;
import java.util.Locale;

/**
 * System 12 — base auto-repair: pure, game-independent rules. No Minecraft types here, so the
 * whole policy (what counts as cheap, how long a block waits, what it costs, which repair speed
 * the owner picked) is unit-tested in {@code BaseRepairRulesTest} without a running game.
 * {@link BaseRepair} does the world plumbing and calls into this class for every decision.
 *
 * <p>The shape of the mechanic: damage that is NOT the owner's doing (explosions, mobs breaking
 * blocks, trampled farmland, fire) inside an ACTIVE sanctuary is journaled instead of dropped, and
 * the sanctuary rebuilds it later, paying for each block out of its fuel bank. Cheap blocks come
 * back fast and nearly free; precious ones wait longer and cost more. The owner picks a speed
 * ({@link Mode}) that trades fuel for time.
 */
public final class BaseRepairRules {
    private BaseRepairRules() {
    }

    /** Game ticks per real second. */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * Owner-selectable repair speed. {@code delayScale} multiplies the per-tier wait,
     * {@code costScale} multiplies the per-tier fuel cost. OFF journals damage but never rebuilds
     * (the queue is kept, so switching back on resumes where it left off).
     */
    public enum Mode {
        OFF(0.0, 0.0),
        SLOW(2.0, 0.75),
        NORMAL(1.0, 1.0),
        FAST(0.5, 1.5),
        TURBO(0.1, 3.0);

        public final double delayScale;
        public final double costScale;

        Mode(double delayScale, double costScale) {
            this.delayScale = delayScale;
            this.costScale = costScale;
        }

        /** Parse a stored/typed mode name; anything unknown (incl. null) falls back to {@code fallback}. */
        public static Mode parse(String s, Mode fallback) {
            if (s == null) {
                return fallback;
            }
            try {
                return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }

        /** The next speed in the cycle the dialog button steps through (TURBO wraps to OFF). */
        public Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        /** Title-case label for UI ("Normal", "Turbo"). */
        public String label() {
            String n = name().toLowerCase(Locale.ROOT);
            return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
    }

    /**
     * Value tier of a block: 0 = dirt-cheap (hardness up to {@code tierHardness[0]}), rising with
     * hardness, and {@code preciousIds} (diamond/netherite blocks, beacons...) are always the top
     * tier regardless of how easy they are to break. Negative hardness (bedrock-like
     * unbreakables) is also top tier: it should never be something a mob can cheaply farm.
     *
     * @param hardness     the block's destroy time (vanilla hardness)
     * @param blockId      namespaced id, e.g. {@code minecraft:diamond_block}
     * @param tierHardness ascending hardness ceilings for tiers 0..n-1; above the last is tier n
     * @param preciousIds  ids forced to the top tier
     */
    public static int tier(double hardness, String blockId, List<Double> tierHardness, List<String> preciousIds) {
        int top = tierHardness == null ? 0 : tierHardness.size();
        if (preciousIds != null && blockId != null && preciousIds.contains(blockId)) {
            return top;
        }
        if (hardness < 0) {
            return top;
        }
        for (int i = 0; i < top; i++) {
            if (hardness <= tierHardness.get(i)) {
                return i;
            }
        }
        return top;
    }

    /** Pick the value at {@code tier} from a per-tier list, clamping to its ends (empty list = 0). */
    public static double perTier(List<Double> values, int tier) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        return values.get(Math.max(0, Math.min(tier, values.size() - 1)));
    }

    /** Ticks a block of this tier waits before it is rebuilt under {@code mode}. */
    public static long delayTicks(int tier, Mode mode, List<Double> tierDelaySeconds) {
        double seconds = perTier(tierDelaySeconds, tier) * mode.delayScale;
        return Math.max(TICKS_PER_SECOND, Math.round(seconds * TICKS_PER_SECOND));
    }

    /** Fuel (hours) one rebuilt block of this tier burns under {@code mode}. */
    public static double costHours(int tier, Mode mode, List<Double> tierCostHours) {
        return Math.max(0.0, perTier(tierCostHours, tier) * mode.costScale);
    }

    /** Ticks of fuel the given cost in hours represents (fuel is stored as a game-time expiry). */
    public static long costTicks(double costHours) {
        return Math.round(costHours * AnchorFuel.TICKS_PER_HOUR);
    }

    /**
     * Can an anchor pay {@code costHours} right now? Exempt (admin, {@code expiry <= 0}) anchors
     * repair for free. A fueled anchor must keep at least {@code reserveHours} after paying, so
     * repairs never tip a sanctuary into dormancy on their own.
     */
    public static boolean canAfford(long expiry, long now, double costHours, double reserveHours) {
        if (AnchorFuel.isExempt(expiry)) {
            return true;
        }
        return AnchorFuel.hoursLeft(expiry, now) - costHours >= reserveHours;
    }

    /** The expiry after paying {@code costHours} (exempt anchors are untouched). */
    public static long paidExpiry(long expiry, double costHours) {
        if (AnchorFuel.isExempt(expiry)) {
            return expiry;
        }
        return expiry - costTicks(costHours);
    }

    /** What to do with a due journal entry once its chunk is loaded. */
    public enum Outcome {
        /** The spot still holds what the damage left behind: rebuild it. */
        RESTORE,
        /** Someone built something else there since: the journal entry is stale, drop it. */
        SUPERSEDED,
        /** Something is standing in the way (an entity) or the anchor can't pay: try again later. */
        WAIT
    }

    /**
     * Decide a due entry. {@code currentId} is what is at the spot now, {@code leftBehindId} what
     * the damage left there (air for a blast, dirt for trampled farmland), {@code originalId} what
     * was there before. A spot already back to the original (the owner fixed it by hand) is
     * superseded too: there is nothing to rebuild.
     */
    public static Outcome decide(String currentId, String leftBehindId, String originalId,
                                 boolean currentIsReplaceable, boolean blocked, boolean affordable) {
        if (currentId != null && currentId.equals(originalId)) {
            return Outcome.SUPERSEDED;
        }
        boolean untouched = (currentId != null && currentId.equals(leftBehindId)) || currentIsReplaceable;
        if (!untouched) {
            return Outcome.SUPERSEDED;
        }
        if (blocked || !affordable) {
            return Outcome.WAIT;
        }
        return Outcome.RESTORE;
    }

    /**
     * Horizontal cover test used to pick the sanctuary a block belongs to: inside the anchor's
     * radius (inclusive), measured centre to centre in the XZ plane.
     */
    public static boolean covers(double anchorX, double anchorZ, double radius, double x, double z) {
        double dx = x - anchorX;
        double dz = z - anchorZ;
        return dx * dx + dz * dz <= radius * radius;
    }
}
