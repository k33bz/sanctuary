package com.k33bz.sanctuary.anchor;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import com.k33bz.sanctuary.Sanctuary;

import java.util.UUID;

/**
 * Optional Flan integration: an ACTIVE sanctuary anchor carries a claim around the crystal so the
 * anchor and its surroundings are grief-protected; dormant/broken anchors lose it.
 *
 * <p>0.8.12.0 — three fixes, all found by reading Flan's {@code Claim.canInteract}:
 * <ol>
 *   <li><b>The owner has rights in their own core.</b> Flan lets only the owner, group members
 *       and ops act inside a claim, and the anchor claim is an ownerless ADMIN claim, so a non-op
 *       player who paid for a sanctuary could not build, break or open chests in its core. The
 *       anchor's owner is now a member of the claim's {@code flanAnchorOwnerGroup} (Co-Owner),
 *       which also lets them trust friends through Flan's own groups. 0.8.12.0 transferred the
 *       claim to the owner instead; 0.8.12.1 keeps it an admin claim because an owned claim is
 *       charged against the owner's claim blocks. {@code flanClaimOwnedByAnchorOwner=false}
 *       restores the old no-rights behaviour.</li>
 *   <li><b>Honest logging.</b> Flan refuses a claim that overlaps any other and returns null; the
 *       old code logged "claim raised" regardless, so an unprotected base looked protected.</li>
 *   <li><b>Only delete our own claim.</b> Removal used to delete whatever admin claim covered the
 *       crystal — including an unrelated one (a spawn claim) the anchor merely sat inside. The id
 *       of the claim we create is now stored on the anchor and removal deletes only that id;
 *       legacy anchors without an id fall back to an exact-footprint match.</li>
 * </ol>
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

    /**
     * Ensure the anchor has its claim. Creates one if nothing covers the crystal; adopts a legacy
     * admin claim of exactly our footprint (stores its id, hands it to the owner). Never touches a
     * claim that isn't ours. Returns true if the anchor is covered by its own claim afterwards.
     */
    public static boolean createClaim(ServerLevel level, BlockPos pos, int radius, AnchorState.PlacedAnchor anchor) {
        try {
            io.github.flemmli97.flan.claim.ClaimStorage storage =
                    io.github.flemmli97.flan.claim.ClaimStorage.get(level);
            io.github.flemmli97.flan.claim.Claim existing = storage.getClaimAt(pos);
            if (existing != null) {
                boolean ours = anchor != null && anchor.flanClaimId != null
                        && anchor.flanClaimId.equals(String.valueOf(existing.getClaimID()));
                if (!ours && anchor != null && anchor.flanClaimId == null
                        && existing.isAdminClaim() && isExactFootprint(existing, pos, radius)) {
                    // A pre-0.8.12 anchor claim: adopt it so removal and ownership work from now on.
                    anchor.flanClaimId = String.valueOf(existing.getClaimID());
                    AnchorState.get().save();
                    ours = true;
                    Sanctuary.LOGGER.info("[sanctuary] Adopted legacy Flan claim {} for anchor at {},{}",
                            anchor.flanClaimId, pos.getX(), pos.getZ());
                }
                if (ours) {
                    assignOwner(level, existing, anchor);
                } else {
                    // Someone else's claim already covers the crystal: leave it alone (its own
                    // protection applies), but say so — the bot harness (B15) found this silent.
                    Sanctuary.LOGGER.info("[sanctuary] Anchor at {},{} sits in an existing Flan claim ({}, owner={});"
                                    + " no anchor claim created", pos.getX(), pos.getZ(), existing.getClaimID(),
                            existing.isAdminClaim() ? "admin" : existing.getOwner());
                }
                return ours;
            }
            // Floor at the bottom of the world, not Flan's defaultClaimDepth below the crystal: the
            // bot harness (B13) drained a claimed chest with a hopper and a hopper minecart parked
            // one block under the old claim floor. Flan clamps minY to the world's min height.
            io.github.flemmli97.flan.claim.Claim claim = storage.createAdminClaim(
                    new BlockPos(pos.getX() - radius, level.getMinY(), pos.getZ() - radius),
                    pos.offset(radius, 0, radius), level, false);
            if (claim == null) {
                // Flan refuses any claim that overlaps another. Say so: the core is NOT protected.
                Sanctuary.LOGGER.warn("[sanctuary] Flan refused the anchor claim at {},{} (r={}): it overlaps"
                                + " another claim. This sanctuary's core is NOT Flan-protected.",
                        pos.getX(), pos.getZ(), radius);
                return false;
            }
            if (anchor != null) {
                anchor.flanClaimId = String.valueOf(claim.getClaimID());
                AnchorState.get().save();
            }
            assignOwner(level, claim, anchor);
            Sanctuary.LOGGER.info("[sanctuary] Flan claim raised around anchor at {},{} (r={}, anchor owner={})",
                    pos.getX(), pos.getZ(), radius, anchor == null || anchor.owner == null ? "server" : anchor.owner);
            return true;
        } catch (Throwable t) {
            Sanctuary.LOGGER.warn("[sanctuary] Flan claim creation failed", t);
            return false;
        }
    }

    /**
     * Give the anchor's owner full rights in our claim (config-gated) by putting them in the claim's
     * {@code flanAnchorOwnerGroup} (Co-Owner: build, containers, and edit_perms so they can trust
     * friends themselves).
     *
     * <p>0.8.12.1: the claim stays an ADMIN claim instead of being transferred. Flan charges an owned
     * claim against its owner's claim blocks, so in 0.8.12.0 every anchor cost a 33x33 claim: two
     * anchors put the bot harness owner at -1667 blocks, unable to claim any land of their own (B15).
     * An admin claim costs nobody blocks, and ops keep access without /flan bypass (B4).
     */
    private static void assignOwner(ServerLevel level,
                                    io.github.flemmli97.flan.claim.Claim claim,
                                    AnchorState.PlacedAnchor anchor) {
        if (anchor == null || anchor.ownerId == null || Sanctuary.CONFIG == null
                || !Sanctuary.CONFIG.flanClaimOwnedByAnchorOwner || anchor.isExempt()) {
            return; // exempt = admin/creative placed: no owner to grant, like before
        }
        UUID owner;
        try {
            owner = UUID.fromString(anchor.ownerId);
        } catch (IllegalArgumentException e) {
            return;
        }
        String group = Sanctuary.CONFIG.flanAnchorOwnerGroup;
        if (claim.playersFromGroup(level.getServer(), group).stream()
                .anyMatch(p -> owner.equals(p.id()))) {
            return; // already a member; nothing to re-save
        }
        if (claim.setPlayerGroup(owner, group, true)) {
            Sanctuary.LOGGER.info("[sanctuary] Anchor owner {} added to group {} of Flan claim {}",
                    anchor.owner, group, claim.getClaimID());
        } else {
            Sanctuary.LOGGER.warn("[sanctuary] Could not add anchor owner {} to group {} of Flan claim {}"
                    + " (does the group exist?). The owner has no rights in their sanctuary core.",
                    anchor.owner, group, claim.getClaimID());
        }
    }

    /** Remove the claim THIS anchor created. Never deletes a claim that isn't ours. */
    public static void removeClaim(ServerLevel level, BlockPos pos, int radius, AnchorState.PlacedAnchor anchor) {
        try {
            io.github.flemmli97.flan.claim.ClaimStorage storage =
                    io.github.flemmli97.flan.claim.ClaimStorage.get(level);
            io.github.flemmli97.flan.claim.Claim claim = null;
            if (anchor != null && anchor.flanClaimId != null) {
                try {
                    claim = storage.getFromUUID(UUID.fromString(anchor.flanClaimId));
                } catch (IllegalArgumentException ignored) {
                    // corrupt id: fall through to the footprint check
                }
            } else {
                // Legacy anchor (no stored id): only an admin claim of exactly our footprint is ours.
                io.github.flemmli97.flan.claim.Claim at = storage.getClaimAt(pos);
                if (at != null && at.isAdminClaim() && isExactFootprint(at, pos, radius)) {
                    claim = at;
                }
            }
            if (claim == null) {
                return;
            }
            storage.deleteClaim(claim, true, io.github.flemmli97.flan.player.ClaimMode.DEFAULT, level);
            if (anchor != null) {
                anchor.flanClaimId = null;
                AnchorState.get().save();
            }
            Sanctuary.LOGGER.info("[sanctuary] Flan claim released at {},{}", pos.getX(), pos.getZ());
        } catch (Throwable t) {
            Sanctuary.LOGGER.warn("[sanctuary] Flan claim removal failed", t);
        }
    }

    /**
     * Is {@code claim} exactly the square we would have made around {@code pos}? The four corners
     * are inside and the cells one step beyond each corner are not. (Y is the crystal's own height,
     * which a 2D claim always spans.)
     */
    private static boolean isExactFootprint(io.github.flemmli97.flan.claim.Claim claim, BlockPos pos, int radius) {
        int r = radius;
        return claim.insideClaim(pos.offset(-r, 0, -r)) && claim.insideClaim(pos.offset(r, 0, r))
                && claim.insideClaim(pos.offset(-r, 0, r)) && claim.insideClaim(pos.offset(r, 0, -r))
                && !claim.insideClaim(pos.offset(-r - 1, 0, 0)) && !claim.insideClaim(pos.offset(r + 1, 0, 0))
                && !claim.insideClaim(pos.offset(0, 0, -r - 1)) && !claim.insideClaim(pos.offset(0, 0, r + 1));
    }

    /**
     * System 12 report line: does Flan block explosions / fire spread globally by default? Returns
     * a human-readable summary for {@code /sanctuary heal report}, or null if it can't be read.
     */
    public static String globalFlagsSummary(ServerLevel level) {
        try {
            StringBuilder b = new StringBuilder();
            String[] names = {"explosions", "fire_spread", "wither", "piston_border", "enderman"};
            net.minecraft.resources.Identifier[] ids = {
                    io.github.flemmli97.flan.api.permission.BuiltinPermission.EXPLOSIONS,
                    io.github.flemmli97.flan.api.permission.BuiltinPermission.FIRESPREAD,
                    io.github.flemmli97.flan.api.permission.BuiltinPermission.WITHER,
                    io.github.flemmli97.flan.api.permission.BuiltinPermission.PISTONBORDER,
                    io.github.flemmli97.flan.api.permission.BuiltinPermission.ENDERMAN};
            for (int i = 0; i < ids.length; i++) {
                io.github.flemmli97.flan.config.Config.GlobalType g =
                        io.github.flemmli97.flan.config.ConfigHandler.CONFIG.getGlobal(level, ids[i]);
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(names[i]).append('=').append(g.name());
            }
            return b.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
