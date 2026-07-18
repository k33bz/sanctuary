package com.k33bz.sanctuary.rift;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

/**
 * Seals the gathering world ({@code sanctuary:resource_world}) against vanilla Nether / End portals.
 *
 * <p>The gathering world is a TEMPORARY dimension that is wiped on the weekly reset. A working Nether portal
 * (→ the persistent Nether) or a stronghold End portal (→ the End) would let a player carry items and progress
 * OUT of the world that is meant to be discarded — defeating the whole point of a reset resource dimension.
 * This closes both, defence-in-depth:
 * <ul>
 *   <li><b>Prevention (primary):</b> a synchronous {@link UseBlockCallback} refuses to light an obsidian frame
 *       with flint &amp; steel / a fire charge, and refuses to seat an eye of ender in an end-portal frame,
 *       while the interaction happens inside the gathering world. The portal simply never forms.</li>
 *   <li><b>Sink hardening (backstop):</b> driven from {@link Rifts#tick}, any {@code nether_portal} /
 *       {@code end_portal} block that still materialises near a player in the gathering world (dispenser fire,
 *       flowing lava, an operator build, a portal left over from before this shipped) is cleared to air.</li>
 * </ul>
 *
 * <p>Rift travel itself is a command teleport, not a portal block, so it is untouched. Portal blocks are matched
 * by registry id (not a {@code Blocks} field) so this keeps compiling across the version churn that already
 * removed sibling block symbols (see {@link RiftPortals}). Gated by {@link SanctuaryConfig#sealResourcePortals}.
 */
public final class RiftSeal {
    private RiftSeal() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(RiftSeal::onUseBlock);
    }

    /** True only when the seal is on AND {@code level} is the gathering world. */
    private static boolean sealing(Level level) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        return cfg != null && cfg.riftsEnabled && cfg.sealResourcePortals
                && level.dimension().identifier().toString().equals(cfg.riftDimension);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        // Fires once per hand; check the item in the hand that fired so an off-hand igniter is caught too.
        if (level.isClientSide() || !sealing(level)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        String frame = id(level.getBlockState(hit.getBlockPos()));
        // Nether portal: refuse to light an obsidian frame.
        if ((held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE))
                && (frame.equals("minecraft:obsidian") || frame.equals("minecraft:crying_obsidian"))) {
            deny(player, "The gathering world smothers the flame — no gate will light here.");
            return InteractionResult.FAIL;
        }
        // End portal: refuse to seat an eye of ender in a portal frame.
        if (held.is(Items.ENDER_EYE) && frame.equals("minecraft:end_portal_frame")) {
            deny(player, "The gathering world swallows the eye — no gate will open here.");
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    /**
     * Backstop sweep: clear any Nether/End portal block that appeared in the small box around a player standing
     * in the gathering world. Called for every player each rift tick; self-gates on dimension + config, so it is
     * a single dimension-string compare for anyone outside the gathering world.
     */
    public static void sweep(ServerLevel level, ServerPlayer player) {
        if (!sealing(level)) {
            return;
        }
        BlockPos feet = player.blockPosition();
        boolean cleared = false;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 3, 2))) {
            String bid = id(level.getBlockState(p));
            if (bid.equals("minecraft:nether_portal") || bid.equals("minecraft:end_portal")) {
                level.setBlock(p.immutable(), Blocks.AIR.defaultBlockState(), 3);
                cleared = true;
            }
        }
        if (cleared) {
            deny(player, "The gathering world unravels the gate — it will not link.");
        }
    }

    private static String id(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    private static void deny(Player player, String msg) {
        player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }
}
