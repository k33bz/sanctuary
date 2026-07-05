package com.k33bz.sanctuary.grave;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.anchor.AnchorState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The graveyard consecration ritual — survival's answer to /sanctuarygraveyard. Inside your own
 * sanctuary, fence (or wall) off a plot with a gate, then build an iron golem body in it and
 * crown it with a skull instead of a pumpkin: skull on two stacked iron blocks, iron arms on the
 * middle block. The ritual consumes the iron and the skull, and the Gravekeeper rises in its
 * place — a still cleric standing vigil over the graves. One graveyard per sanctuary, bound to the
 * anchor's owner. The pen must enclose at least {@code graveyardMinSize}² walkable blocks per side
 * and at most {@code graveyardMaxSize}² overall.
 */
public final class GraveyardRitual {
    private GraveyardRitual() {
    }

    /** Called after any floor skull is placed (from the BlockItem placement hook). */
    public static void tryForm(ServerLevel level, BlockPos skullPos, ServerPlayer player, SanctuaryConfig cfg) {
        if (cfg == null || !cfg.gravesEnabled) {
            return;
        }
        BlockState skull = level.getBlockState(skullPos);
        if (!skull.is(Blocks.SKELETON_SKULL) && !skull.is(Blocks.WITHER_SKELETON_SKULL)) {
            return;
        }
        BlockPos body = skullPos.below();
        BlockPos legs = skullPos.below(2);
        if (!level.getBlockState(body).is(Blocks.IRON_BLOCK) || !level.getBlockState(legs).is(Blocks.IRON_BLOCK)) {
            return;
        }
        BlockPos armA;
        BlockPos armB;
        if (level.getBlockState(body.east()).is(Blocks.IRON_BLOCK)
                && level.getBlockState(body.west()).is(Blocks.IRON_BLOCK)) {
            armA = body.east();
            armB = body.west();
        } else if (level.getBlockState(body.north()).is(Blocks.IRON_BLOCK)
                && level.getBlockState(body.south()).is(Blocks.IRON_BLOCK)) {
            armA = body.north();
            armB = body.south();
        } else {
            return; // just a skull on iron, not the effigy
        }

        // Inside a sanctuary, performed by its owner, one yard per anchor.
        if (Sanctuary.blocksBeyondNearestAnchor(cfg, player.getX(), player.getZ()) > 0) {
            fail(player, "The Gravekeeper only serves consecrated ground — build inside a sanctuary.");
            return;
        }
        String anchorId = "config";
        String anchorOwner = null;
        double bestSq = Double.MAX_VALUE;
        for (AnchorState.PlacedAnchor a : AnchorState.get().anchors) {
            double dx = a.x - player.getX(), dz = a.z - player.getZ();
            if (dx * dx + dz * dz < bestSq) {
                bestSq = dx * dx + dz * dz;
                anchorId = a.id != null ? a.id : "legacy";
                anchorOwner = a.ownerId;
            }
        }
        if (anchorOwner != null && !anchorOwner.equals(player.getUUID().toString()) && !player.isCreative()) {
            fail(player, "Only this sanctuary's owner may consecrate its graveyard.");
            return;
        }
        String fAnchor = anchorId;
        Graves.Yard existing = Graves.store().yards.stream()
                .filter(y -> y.anchorId.equals(fAnchor)).findFirst().orElse(null);
        // An existing yard may be RESIZED by the SAME owner if the new pen is larger and still
        // contains every grave (validated after the flood-fill below). A different owner, or any
        // shrink/strand, is rejected.
        if (existing != null && existing.owner != null
                && !existing.owner.equals(player.getUUID().toString()) && !player.isCreative()) {
            fail(player, "This sanctuary already has a graveyard, tended by another.");
            return;
        }

        // Flood the pen from the effigy: fences/walls/gates are the boundary.
        int max = Math.max(9, cfg.graveyardMaxSize);
        int half = max / 2 + 1;
        Set<Long> visited = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[]{skullPos.getX(), skullPos.getZ()});
        visited.add(key(skullPos.getX(), skullPos.getZ()));
        int minX = skullPos.getX(), maxX = skullPos.getX(), minZ = skullPos.getZ(), maxZ = skullPos.getZ();
        while (!queue.isEmpty()) {
            long[] cell = queue.poll();
            int x = (int) cell[0], z = (int) cell[1];
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0], nz = z + d[1];
                if (Math.abs(nx - skullPos.getX()) > half || Math.abs(nz - skullPos.getZ()) > half
                        || visited.size() > max * max) {
                    fail(player, String.format(Locale.ROOT,
                            "The pen is open or too vast — fence it with a gate, at most %dx%d.", max, max));
                    return;
                }
                if (!visited.add(key(nx, nz))) {
                    continue;
                }
                if (isBoundary(level, nx, skullPos.getY(), nz)) {
                    continue; // the fence holds
                }
                minX = Math.min(minX, nx);
                maxX = Math.max(maxX, nx);
                minZ = Math.min(minZ, nz);
                maxZ = Math.max(maxZ, nz);
                queue.add(new long[]{nx, nz});
            }
        }
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        int min = Math.max(3, cfg.graveyardMinSize);
        if (spanX < min || spanZ < min) {
            fail(player, String.format(Locale.ROOT,
                    "The pen is too small — the Gravekeeper needs at least %dx%d inside the fence.", min, min));
            return;
        }

        int newRadius = Math.max(3, (Math.min(spanX, spanZ) - 2) / 2);

        // UPGRADE path (0.8.2 default keeper): the existing yard is the auto/default HOLD-ONLY one.
        // Consecrating a real graveyard relocates/upgrades it in place — the keeper moves to the new
        // consecrated ground and any keeper-held graves carry over (same anchorId, still claimable).
        if (existing != null && existing.auto) {
            spendEffigy(level, skullPos, body, armA, armB, legs);
            // Remove the old default keeper standing beside the anchor.
            Graves.run(level, "kill @e[type=minecraft:villager,tag=" + Gravekeeper.KEEPER_TAG
                    + ",x=" + existing.x + ",y=" + existing.y + ",z=" + existing.z + ",distance=..6]");
            existing.auto = false;
            existing.owner = player.getUUID().toString();
            existing.x = (minX + maxX) / 2;
            existing.z = (minZ + maxZ) / 2;
            existing.y = legs.getY();
            existing.radius = newRadius;
            existing.bMinX = minX;
            existing.bMaxX = maxX;
            existing.bMinZ = minZ;
            existing.bMaxZ = maxZ;
            Graves.save();
            Gravekeeper.spawnKeeper(level, existing);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "The wandering keeper settles into %dx%d of newly consecrated ground.",
                            spanX, spanZ))
                    .withStyle(ChatFormatting.GOLD));
            return;
        }

        // RESIZE path (part d): re-consecrating an existing yard. Accept only a genuine expansion
        // that still contains every resting grave; reject a shrink or one that would strand a grave.
        if (existing != null) {
            GraveyardBounds.Rect cur = new GraveyardBounds.Rect(
                    existing.bMinX, existing.bMaxX, existing.bMinZ, existing.bMaxZ);
            GraveyardBounds.Rect next = new GraveyardBounds.Rect(minX, maxX, minZ, maxZ);
            java.util.List<double[]> positions = new java.util.ArrayList<>();
            for (Graves.Grave g : Graves.store().graves) {
                if (g.inGraveyard && !g.heldByKeeper && existing.anchorId.equals(g.graveyardAnchor)) {
                    positions.add(new double[]{g.x, g.z});
                }
            }
            GraveyardBounds.Result verdict = GraveyardBounds.validateResize(cur, next, positions);
            if (verdict == GraveyardBounds.Result.NOT_LARGER) {
                fail(player, "The graveyard can only be expanded — this pen is no larger.");
                return;
            }
            if (verdict == GraveyardBounds.Result.STRANDS_GRAVE) {
                fail(player, "A grave would be left outside the new fence — widen it to enclose them all.");
                return;
            }
            // Spend the effigy, then resize the yard in place and re-layout its graves.
            spendEffigy(level, skullPos, body, armA, armB, legs);
            existing.x = (minX + maxX) / 2;
            existing.z = (minZ + maxZ) / 2;
            existing.radius = newRadius;
            existing.bMinX = minX;
            existing.bMaxX = maxX;
            existing.bMinZ = minZ;
            existing.bMaxZ = maxZ;
            int moved = Graves.relayoutYard(level.getServer(), existing);
            Graves.save();
            Gravekeeper.spawnKeeper(level, existing);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "The Gravekeeper's ground grows to %dx%d (%d grave%s re-laid).",
                            spanX, spanZ, moved, moved == 1 ? "" : "s"))
                    .withStyle(ChatFormatting.GOLD));
            return;
        }

        // Fresh consecration: the effigy is spent; the keeper rises.
        spendEffigy(level, skullPos, body, armA, armB, legs);

        Graves.Yard yard = new Graves.Yard();
        yard.anchorId = anchorId;
        yard.owner = player.getUUID().toString();
        yard.dim = level.dimension().identifier().toString();
        yard.x = (minX + maxX) / 2;
        yard.y = legs.getY();
        yard.z = (minZ + maxZ) / 2;
        yard.radius = newRadius;
        yard.bMinX = minX;
        yard.bMaxX = maxX;
        yard.bMinZ = minZ;
        yard.bMaxZ = maxZ;
        Graves.store().yards.add(yard);
        Graves.save();
        Gravekeeper.spawnKeeper(level, yard);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "The effigy crumbles. The Gravekeeper rises to tend %dx%d of consecrated ground.",
                        spanX, spanZ))
                .withStyle(ChatFormatting.GOLD));
    }

    /** Consume the effigy blocks with a poof + sound. */
    private static void spendEffigy(ServerLevel level, BlockPos skullPos, BlockPos body,
                                    BlockPos armA, BlockPos armB, BlockPos legs) {
        for (BlockPos p : new BlockPos[]{skullPos, body, armA, armB, legs}) {
            Graves.run(level, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air",
                    p.getX(), p.getY(), p.getZ()));
        }
        Graves.run(level, String.format(Locale.ROOT,
                "particle minecraft:poof %d %d %d 0.4 0.8 0.4 0.02 40", body.getX(), body.getY(), body.getZ()));
        Graves.run(level, String.format(Locale.ROOT,
                "playsound minecraft:block.respawn_anchor.deplete block @a %d %d %d 1 0.6",
                body.getX(), body.getY(), body.getZ()));
    }

    /** Any fence, wall, or fence gate within a few blocks of yard level counts as boundary. */
    private static boolean isBoundary(ServerLevel level, int x, int yBase, int z) {
        for (int dy = -3; dy <= 3; dy++) {
            BlockState s = level.getBlockState(new BlockPos(x, yBase + dy, z));
            if (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCE_GATES)) {
                return true;
            }
        }
        return false;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static void fail(ServerPlayer player, String message) {
        player.sendOverlayMessage(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
