package com.k33bz.sanctuary.grave;

import java.util.random.RandomGenerator;

/**
 * Pure, Minecraft-free logic for grave robbing (System 10, issue #7) so it can be unit-tested.
 *
 * <p>Robbing lets a NON-owner dig up a WILD grave at night once it has sat public a while, taking
 * the buried soul (a flat XP reward — graves don't store the victim's XP, soul-retention keeps that
 * on the player) plus a fraction of the goods. Desecration is lossy and risky: each stack may
 * shatter, a yielded stack may come up damaged, and the disturbed dead may rise. All the dice live
 * here; {@link GraveRob} applies them to the world. RandomSource does not implement
 * {@link RandomGenerator} on 26.x, so callers pass a {@link java.util.Random}.
 */
public final class GraveRobLogic {
    private GraveRobLogic() {
    }

    /** Per-stack outcome when a grave is robbed. */
    public enum Fate {
        /** Lost in the desecration — the robber gets nothing for this stack. */
        SHATTER,
        /** Transfers to the robber intact. */
        YIELD_INTACT,
        /** Transfers, but damaged (only meaningful for damageable items). */
        YIELD_DAMAGED
    }

    /**
     * Whether {@code robber} may dig up this grave right now. Server-owned/graveyard graves and the
     * robber's own graves are never eligible; a wild grave must be past its public window.
     *
     * @param enabled           config: grave robbing on
     * @param isOwner           the digger owns this grave
     * @param inGraveyard       grave sits on consecrated ground (keeper's — never robbable)
     * @param looted            grave already emptied
     * @param nightOnly         config: only robbable at night
     * @param isNight           it is currently night in the overworld
     * @param elapsedHours      real hours since the death
     * @param robbableAfterHours config: hours a wild grave must age before robbing
     */
    public static boolean eligible(boolean enabled, boolean isOwner, boolean inGraveyard,
                                   boolean looted, boolean nightOnly, boolean isNight,
                                   double elapsedHours, double robbableAfterHours) {
        return enabled
                && !isOwner
                && !inGraveyard
                && !looted
                && (!nightOnly || isNight)
                && elapsedHours >= robbableAfterHours;
    }

    /**
     * Roll one stack's fate. {@code yieldFraction} is the chance it transfers at all; of those that
     * do, {@code damageFraction} is the chance it comes up damaged.
     */
    public static Fate fate(RandomGenerator rng, double yieldFraction, double damageFraction) {
        if (rng.nextDouble() >= yieldFraction) {
            return Fate.SHATTER;
        }
        return rng.nextDouble() < damageFraction ? Fate.YIELD_DAMAGED : Fate.YIELD_INTACT;
    }

    /** Whether the disturbed dead rise (and curse the robber) on this rob. */
    public static boolean wraithRises(RandomGenerator rng, double chance) {
        return rng.nextDouble() < chance;
    }

    /**
     * Damage to apply to a damaged-yield item: a random 50–90% of its max durability, clamped so it
     * never fully breaks (leaves at least 1 point of durability). {@code maxDamage} is the item's
     * durability cap; returns 0 for non-damageable items (maxDamage &lt;= 0).
     */
    public static int damageValue(RandomGenerator rng, int maxDamage) {
        if (maxDamage <= 1) {
            return 0;
        }
        int dmg = (int) (maxDamage * (0.50 + rng.nextDouble() * 0.40));
        return Math.min(dmg, maxDamage - 1);
    }
}
