package com.k33bz.sanctuary.anchor;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import com.k33bz.sanctuary.Sanctuary;

/**
 * Optional Flan integration: an ACTIVE sanctuary anchor carries an admin claim around the crystal
 * so the anchor and its surroundings are grief-protected; dormant/broken anchors lose it.
 *
 * <p>All Flan types are referenced only inside method bodies, and callers must gate on
 * {@link #available()} — so this class is safe to load when Flan isn't installed.
 */
public final class FlanIntegration {
    private FlanIntegration() {
    }

    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("flan");
    }

    /** Create the admin claim around an anchor (no-op if one already covers the crystal). */
    public static void createClaim(ServerLevel level, BlockPos pos, int radius) {
        try {
            io.github.flemmli97.flan.claim.ClaimStorage storage =
                    io.github.flemmli97.flan.claim.ClaimStorage.get(level);
            if (storage.getClaimAt(pos) != null) {
                return; // already claimed (ours from a previous cycle, or someone else's)
            }
            storage.createAdminClaim(pos.offset(-radius, 0, -radius), pos.offset(radius, 0, radius), level, false);
            Sanctuary.LOGGER.info("[sanctuary] Flan admin claim raised around anchor at {},{} (r={})",
                    pos.getX(), pos.getZ(), radius);
        } catch (Throwable t) {
            Sanctuary.LOGGER.warn("[sanctuary] Flan claim creation failed", t);
        }
    }

    /** Remove the admin claim covering an anchor, if it is an admin claim. */
    public static void removeClaim(ServerLevel level, BlockPos pos) {
        try {
            io.github.flemmli97.flan.claim.ClaimStorage storage =
                    io.github.flemmli97.flan.claim.ClaimStorage.get(level);
            io.github.flemmli97.flan.claim.Claim claim = storage.getClaimAt(pos);
            if (claim != null && claim.isAdminClaim()) {
                storage.deleteClaim(claim, true, io.github.flemmli97.flan.player.ClaimMode.DEFAULT, level);
                Sanctuary.LOGGER.info("[sanctuary] Flan admin claim released at {},{}", pos.getX(), pos.getZ());
            }
        } catch (Throwable t) {
            Sanctuary.LOGGER.warn("[sanctuary] Flan claim removal failed", t);
        }
    }
}
