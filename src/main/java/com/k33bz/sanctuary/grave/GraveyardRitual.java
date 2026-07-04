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
 * place — free to wander among the graves, never past the fence. One graveyard per sanctuary,
 * bound to the anchor's owner. The pen must enclose at least {@code graveyardMinSize}² walkable
 * blocks per side and at most {@code graveyardMaxSize}² overall.
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
        if (Graves.store().yards.stream().anyMatch(y -> y.anchorId.equals(fAnchor))) {
            fail(player, "This sanctuary already has a graveyard.");
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

        // The effigy is spent; the keeper rises.
        for (BlockPos p : new BlockPos[]{skullPos, body, armA, armB, legs}) {
            Graves.run(level, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air",
                    p.getX(), p.getY(), p.getZ()));
        }
        Graves.run(level, String.format(Locale.ROOT,
                "particle minecraft:poof %d %d %d 0.4 0.8 0.4 0.02 40", body.getX(), body.getY(), body.getZ()));
        Graves.run(level, String.format(Locale.ROOT,
                "playsound minecraft:block.respawn_anchor.deplete block @a %d %d %d 1 0.6",
                body.getX(), body.getY(), body.getZ()));

        Graves.Yard yard = new Graves.Yard();
        yard.anchorId = anchorId;
        yard.owner = player.getUUID().toString();
        yard.dim = level.dimension().identifier().toString();
        yard.x = (minX + maxX) / 2;
        yard.y = legs.getY();
        yard.z = (minZ + maxZ) / 2;
        yard.radius = Math.max(3, (Math.min(spanX, spanZ) - 2) / 2);
        yard.bMinX = minX;
        yard.bMaxX = maxX;
        yard.bMinZ = minZ;
        yard.bMaxZ = maxZ;
        Graves.store().yards.add(yard);
        Graves.save();
        Gravekeeper.spawnKeeper(level, yard, true);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "The effigy crumbles. The Gravekeeper rises to tend %dx%d of consecrated ground.",
                        spanX, spanZ))
                .withStyle(ChatFormatting.GOLD));
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
