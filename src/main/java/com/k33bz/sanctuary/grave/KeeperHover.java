package com.k33bz.sanctuary.grave;

/**
 * Pure, game-independent math for the Gravekeeper's gentle hover-bob (0.8.3.3). No Minecraft types,
 * so it is unit-testable; {@link Gravekeeper} applies the returned Y to each stationary keeper each
 * tick.
 *
 * <p>The keeper hovers just above the grave/ground (a small base lift) and bobs slowly up and down
 * by a small sine amplitude — it hovers and bounces slightly instead of sitting on the floor or
 * floating high in the air. It is otherwise stationary (no horizontal drift, no pathing — full
 * patrol AI is deferred to 0.8.4).
 */
public final class KeeperHover {
    private KeeperHover() {
    }

    /** Base lift above the yard floor Y the keeper hovers at (blocks) — just off the ground. */
    public static final double BASE_LIFT = 0.15;

    /** Bob amplitude (blocks): the keeper rises/falls this much around the base lift. */
    public static final double AMPLITUDE = 0.12;

    /** Bob period in ticks (~4s at 20 tps) — a slow, gentle bounce. */
    public static final double PERIOD_TICKS = 80.0;

    /**
     * The keeper's absolute Y at {@code gameTime}, given the yard floor Y. Equals
     * {@code floorY + BASE_LIFT + AMPLITUDE * sin(2π * gameTime / PERIOD_TICKS)} — a slow sine bob
     * centered on the base lift, so the keeper never dips below {@code floorY + BASE_LIFT - AMPLITUDE}
     * nor rises above {@code floorY + BASE_LIFT + AMPLITUDE}. A per-keeper {@code phase} (radians)
     * desynchronizes multiple keepers so they don't bob in lockstep.
     */
    public static double hoverY(int floorY, long gameTime, double phase) {
        double theta = (2.0 * Math.PI * (gameTime % (long) PERIOD_TICKS)) / PERIOD_TICKS + phase;
        return floorY + BASE_LIFT + AMPLITUDE * Math.sin(theta);
    }
}
