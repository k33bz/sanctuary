package com.k33bz.sanctuary.grave;

import java.util.List;

/**
 * Pure, game-independent math for the Gravekeeper's slow patrol drift (0.8.4). No Minecraft types,
 * so it is unit-testable; {@link Gravekeeper} applies the returned horizontal position each tick and
 * layers the {@link KeeperHover} bob on top of the returned Y-agnostic drift.
 *
 * <p>The keeper stays NoAI + NoGravity — we drive its own slow horizontal drift instead of enabling
 * vanilla villager goals (which would bed-seek / work / panic). Each tick the keeper eases toward a
 * {@link Target} at a very slow, deliberate pace ({@code speed}, a small fraction of villager walk
 * speed — default ~0.025 b/tick), the per-tick step magnitude CAPPED at {@code speed} so it can never
 * read as brisk. On reaching a target (or exceeding a max travel time) it LINGERS in place for a few
 * seconds before choosing the next target. Targets prefer grave positions (drift between graves) and
 * fall back to a random in-bounds point; every target — and the keeper's own position — is CLAMPED a
 * configurable margin inside the fence, so the keeper never paths outside the yard.
 *
 * <p>Randomness is injected ({@link Rng}) so the whole advance is a pure function of the state + the
 * random draws, making target selection, the clamp, the speed cap, and the linger timing all
 * deterministically unit-testable.
 */
public final class KeeperPatrol {
    private KeeperPatrol() {
    }

    /** Default drift speed (blocks/tick) — a slow, ghostly float, a small fraction of walk speed. */
    public static final double DEFAULT_SPEED = 0.025;

    /** Distance (blocks) within which the keeper is considered to have arrived at its target. */
    public static final double ARRIVE_EPSILON = 0.35;

    /**
     * Hard cap on ticks spent travelling to one target before we give up and linger anyway — stops a
     * keeper from creeping forever toward an unreachable/edge point at the slow speed.
     */
    public static final int MAX_TRAVEL_TICKS = 400;

    /** A horizontal patrol target (yard-local absolute X/Z; Y is owned by {@link KeeperHover}). */
    public static final class Target {
        public final double x;
        public final double z;

        public Target(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }

    /** Injected randomness so the advance is deterministic under test. */
    public interface Rng {
        /** Uniform double in [0,1). */
        double nextDouble();

        /** Uniform int in [0,bound). */
        int nextInt(int bound);
    }

    /**
     * Mutable per-keeper patrol state. Carried across ticks by {@link Gravekeeper} (keyed by entity
     * id). {@code hasTarget=false} on a fresh state forces a target pick on the first advance.
     */
    public static final class State {
        public boolean hasTarget;
        public double targetX;
        public double targetZ;
        public int lingerTicks;   // >0 = pausing/hovering in place, counting down
        public int travelTicks;   // ticks spent moving toward the current target
        /** Last travel yaw (degrees), so a lingering keeper keeps facing where it was going/looking. */
        public float yaw;
        public boolean hasYaw;
    }

    /** The result of one patrol advance: where the keeper should be, and which way to face. */
    public static final class Move {
        public final double x;
        public final double z;
        public final float yaw;
        public final boolean lingering;

        public Move(double x, double z, float yaw, boolean lingering) {
            this.x = x;
            this.z = z;
            this.yaw = yaw;
            this.lingering = lingering;
        }
    }

    /**
     * Clamp a single coordinate to lie {@code margin} blocks inside {@code [min, max]}. If the margin
     * is so large it would invert the interval (a tiny yard), the coordinate collapses to the interval
     * centre — the keeper simply sits in the middle rather than escaping. This is the bounds guarantee:
     * a target or keeper position is never left outside the fenced interval.
     */
    public static double clampInside(double v, double min, double max, double margin) {
        double lo = min + margin;
        double hi = max - margin;
        if (lo > hi) {
            return (min + max) / 2.0;
        }
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }

    /**
     * Distance (blocks) the keeper may move this tick toward {@code (tx,tz)} from {@code (x,z)},
     * as an already-capped displacement. The returned {@code [dx,dz]} magnitude is guaranteed
     * {@code <= speed} — the "slow" guarantee. When already within {@code speed} of the target it
     * snaps exactly onto it (no overshoot). {@code speed} is floored at 0.
     */
    public static double[] stepToward(double x, double z, double tx, double tz, double speed) {
        double s = Math.max(0.0, speed);
        double dx = tx - x;
        double dz = tz - z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= s || dist == 0.0) {
            return new double[] {tx, tz};
        }
        double scale = s / dist;
        return new double[] {x + dx * scale, z + dz * scale};
    }

    /** Whether {@code (x,z)} is within {@link #ARRIVE_EPSILON} of {@code (tx,tz)}. */
    public static boolean reached(double x, double z, double tx, double tz) {
        double dx = tx - x;
        double dz = tz - z;
        return dx * dx + dz * dz <= ARRIVE_EPSILON * ARRIVE_EPSILON;
    }

    /**
     * Face yaw (degrees, Minecraft convention) for a travel vector {@code (dx,dz)}; returns the given
     * {@code fallback} when the vector is ~zero (so a stationary keeper doesn't snap to due-south).
     */
    public static float faceYaw(double dx, double dz, float fallback) {
        if (dx * dx + dz * dz < 1e-6) {
            return fallback;
        }
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /**
     * Pick the next patrol target: prefer a grave position (drift between graves), else a uniform
     * random point inside the fenced bounds. Every candidate — grave or random — is CLAMPED
     * {@code margin} inside {@code [minX,maxX] x [minZ,maxZ]} so the target is always reachable
     * without leaving the yard. {@code graves} may be empty/null.
     */
    public static Target pickTarget(List<Target> graves, double minX, double maxX,
                                    double minZ, double maxZ, double margin, Rng rng) {
        if (graves != null && !graves.isEmpty()) {
            Target g = graves.get(rng.nextInt(graves.size()));
            return new Target(
                    clampInside(g.x, minX, maxX, margin),
                    clampInside(g.z, minZ, maxZ, margin));
        }
        double lo = minX + margin;
        double hi = maxX - margin;
        double x = lo > hi ? (minX + maxX) / 2.0 : lo + rng.nextDouble() * (hi - lo);
        double loZ = minZ + margin;
        double hiZ = maxZ - margin;
        double z = loZ > hiZ ? (minZ + maxZ) / 2.0 : loZ + rng.nextDouble() * (hiZ - loZ);
        return new Target(clampInside(x, minX, maxX, margin), clampInside(z, minZ, maxZ, margin));
    }

    /**
     * Advance the patrol one tick and return where the keeper should be (horizontally) + its yaw.
     * Pure given the state and the RNG draws. Behaviour:
     *
     * <ol>
     *   <li>If lingering, decrement the linger counter and HOLD position (no drift); when it hits
     *       zero, clear the target so the next tick picks a fresh one.</li>
     *   <li>Else, if there is no target (or it just cleared), pick one ({@link #pickTarget}) and reset
     *       the travel clock.</li>
     *   <li>Else, step toward the target at {@code speed} (capped), facing the travel direction; on
     *       arrival — or after {@link #MAX_TRAVEL_TICKS} — begin a linger of a random duration in
     *       {@code [lingerMin, lingerMax]}.</li>
     * </ol>
     *
     * <p>The keeper's own position is clamped inside the bounds every tick as a final safety, so even
     * a pushed/nudged keeper is eased back inside rather than escaping. {@code lingerMax} is treated
     * as {@code >= lingerMin}.
     */
    public static Move advance(State st, double x, double z,
                               List<Target> graves,
                               double minX, double maxX, double minZ, double maxZ,
                               double margin, double speed,
                               int lingerMin, int lingerMax, Rng rng) {
        // Clamp the live position first — never let the keeper sit outside the fence.
        double cx = clampInside(x, minX, maxX, margin);
        double cz = clampInside(z, minZ, maxZ, margin);
        float fallbackYaw = st.hasYaw ? st.yaw : 0.0f;

        if (st.lingerTicks > 0) {
            st.lingerTicks--;
            if (st.lingerTicks <= 0) {
                st.hasTarget = false; // linger over → pick a new target next tick
            }
            return new Move(cx, cz, fallbackYaw, true);
        }

        if (!st.hasTarget) {
            Target t = pickTarget(graves, minX, maxX, minZ, maxZ, margin, rng);
            st.targetX = t.x;
            st.targetZ = t.z;
            st.hasTarget = true;
            st.travelTicks = 0;
        }

        // Already effectively at the (possibly just-picked) target → linger immediately, facing it.
        if (reached(cx, cz, st.targetX, st.targetZ)) {
            beginLinger(st, lingerMin, lingerMax, rng);
            return new Move(cx, cz, fallbackYaw, true);
        }

        st.travelTicks++;
        double[] next = stepToward(cx, cz, st.targetX, st.targetZ, speed);
        float yaw = faceYaw(next[0] - cx, next[1] - cz, fallbackYaw);
        st.yaw = yaw;
        st.hasYaw = true;

        // Arrived this step, or travelled too long → linger.
        if (reached(next[0], next[1], st.targetX, st.targetZ) || st.travelTicks >= MAX_TRAVEL_TICKS) {
            beginLinger(st, lingerMin, lingerMax, rng);
        }
        // Final safety clamp on the produced position.
        double nx = clampInside(next[0], minX, maxX, margin);
        double nz = clampInside(next[1], minZ, maxZ, margin);
        return new Move(nx, nz, yaw, false);
    }

    /** Start a linger of a random duration in {@code [min, max]} and drop the current target. */
    private static void beginLinger(State st, int lingerMin, int lingerMax, Rng rng) {
        int min = Math.max(0, lingerMin);
        int max = Math.max(min, lingerMax);
        int span = max - min;
        st.lingerTicks = min + (span <= 0 ? 0 : rng.nextInt(span + 1));
        st.hasTarget = false;
        st.travelTicks = 0;
    }
}
