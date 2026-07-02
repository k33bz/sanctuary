package com.k33bz.sanctuary.anchor;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.phys.BlockHitResult;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Protection decay for player-raised sanctuaries: an anchor burns fuel (real hours of server
 * time), extended by feeding the crystal emeralds (right-click; sneak to feed the whole stack;
 * emerald blocks are worth 9). A dry anchor goes DORMANT — it stays placed and keeps its
 * crystal, but grants no safety and loses its Flan claim until refueled. Exempt (admin) anchors
 * never decay.
 */
public final class AnchorUpkeep {
    private AnchorUpkeep() {
    }

    /** Last known active-state per anchor (keyed by packed block pos), for transition detection. */
    private static final Map<Long, Boolean> LAST_ACTIVE = new HashMap<>();

    public static void register() {
        UseBlockCallback.EVENT.register(AnchorUpkeep::onUseBlock);
    }

    // --- feeding ---

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (!(level.getBlockState(pos).getBlock() instanceof AbstractSkullBlock)) {
            return InteractionResult.PASS;
        }
        AnchorState.PlacedAnchor anchor = AnchorState.get().anchorAt(pos);
        if (anchor == null) {
            return InteractionResult.PASS;
        }
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        long now = level.getGameTime();

        ItemStack held = player.getItemInHand(hand);
        double hoursPerItem = cfg == null ? 1.0 : cfg.anchorHoursPerEmerald;
        double worth = held.is(Items.EMERALD) ? hoursPerItem
                : held.is(Items.EMERALD_BLOCK) ? hoursPerItem * 9.0 : 0.0;

        if (worth <= 0.0 || anchor.isExempt() || cfg == null || !cfg.anchorUpkeepEnabled) {
            sp.sendOverlayMessage(status(anchor, now));
            return InteractionResult.PASS;
        }

        // Feed: one item per click, the whole stack while sneaking.
        int count = player.isShiftKeyDown() ? held.getCount() : 1;
        long base = Math.max(anchor.expiry, now);
        long cap = now + (long) (cfg.anchorMaxFuelHours * 72000.0);
        long fed = Math.min(cap, base + (long) (worth * count * 72000.0));
        int accepted = (int) Math.ceil(count * Math.min(1.0, (fed - base) / Math.max(1.0, worth * count * 72000.0)));
        accepted = Math.max(1, Math.min(count, accepted));
        if (fed <= base) {
            sp.sendOverlayMessage(Component.literal("The sanctuary can hold no more power.")
                    .withStyle(ChatFormatting.YELLOW));
            return InteractionResult.SUCCESS;
        }
        anchor.expiry = fed;
        AnchorState.get().save();
        held.shrink(accepted);

        if (level instanceof ServerLevel serverLevel) {
            run(serverLevel.getServer(), String.format(Locale.ROOT,
                    "playsound minecraft:block.amethyst_block.chime block @a %.1f %.1f %.1f 1 0.7",
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            run(serverLevel.getServer(), String.format(Locale.ROOT,
                    "particle minecraft:happy_villager %.1f %.1f %.1f 0.3 0.3 0.3 0.01 8",
                    pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5));
        }
        sp.sendOverlayMessage(status(anchor, now));
        return InteractionResult.SUCCESS;
    }

    private static Component status(AnchorState.PlacedAnchor anchor, long now) {
        if (anchor.isExempt()) {
            return Component.literal("This sanctuary is eternal.").withStyle(ChatFormatting.LIGHT_PURPLE);
        }
        double hours = anchor.hoursLeft(now);
        if (hours <= 0.0) {
            return Component.literal("This sanctuary lies dormant — feed it emeralds.")
                    .withStyle(ChatFormatting.RED);
        }
        ChatFormatting color = hours < 6 ? ChatFormatting.RED : hours < 24 ? ChatFormatting.YELLOW
                : ChatFormatting.GREEN;
        return Component.literal(String.format(Locale.ROOT, "Sanctuary: %.1f hours of protection remaining.", hours))
                .withStyle(color);
    }

    // --- expiry sweep (dormancy transitions, Flan claim lifecycle, label text) ---

    private static int sweepCounter = 0;

    /** Called once per player-tick interval (~1s). Cheap: the real sweep runs every ~5s. */
    public static void tick(MinecraftServer server, SanctuaryConfig cfg) {
        ServerLevel overworld = server.overworld();
        AnchorState.NOW = overworld.getGameTime();
        if (++sweepCounter < 5) {
            return;
        }
        sweepCounter = 0;
        long now = AnchorState.NOW;
        boolean flan = cfg.flanIntegration && FlanIntegration.available();

        for (AnchorState.PlacedAnchor a : AnchorState.get().anchors) {
            BlockPos pos = BlockPos.containing(a.x, a.y, a.z);
            boolean active = a.isActive(now);
            Boolean last = LAST_ACTIVE.put(pos.asLong(), active);
            if (last != null && last == active) {
                continue; // no transition
            }
            // First sighting or a transition: sync claim + label to the current state.
            if (active) {
                if (flan) {
                    FlanIntegration.createClaim(overworld, pos, cfg.flanClaimRadius);
                }
                setLabel(server, pos, "{text:\"Sanctuary Anchor\",color:\"light_purple\",bold:1b}");
                if (last != null) {
                    Sanctuary.LOGGER.info("[sanctuary] Anchor at {},{} reawakened", pos.getX(), pos.getZ());
                }
            } else {
                if (flan) {
                    FlanIntegration.removeClaim(overworld, pos);
                }
                setLabel(server, pos, "{text:\"Sanctuary Anchor (dormant)\",color:\"gray\"}");
                if (last != null) {
                    Sanctuary.LOGGER.info("[sanctuary] Anchor at {},{} went dormant (out of fuel)",
                            pos.getX(), pos.getZ());
                }
            }
        }
    }

    /** Rewrite the floating label nearest to this anchor. */
    private static void setLabel(MinecraftServer server, BlockPos pos, String textNbt) {
        run(server, String.format(Locale.ROOT,
                "execute positioned %.1f %.1f %.1f run data merge entity "
                        + "@e[type=minecraft:text_display,tag=%s,distance=..3,limit=1] {text:%s}",
                pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, "sanctuary_anchor_display", textNbt));
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
