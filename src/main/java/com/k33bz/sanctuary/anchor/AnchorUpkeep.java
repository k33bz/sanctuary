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
        ItemStack held = player.getItemInHand(hand);
        if (fuelWorth(held) <= 0.0) {
            // Not fuel (empty hand, tools, whatever): open the sanctuary menu instead.
            AnchorMenu.open(sp, pos.immutable(), anchor);
            return InteractionResult.SUCCESS;
        }
        // Fuel in hand: quick top-up without the menu. One item per click, stack while sneaking.
        feed(sp, pos, anchor, held, player.isShiftKeyDown() ? held.getCount() : 1);
        return InteractionResult.SUCCESS;
    }

    /** Hours one item of this stack is worth (0 = not fuel). */
    static double fuelWorth(ItemStack stack) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (stack.is(Items.DRAGON_EGG)) {
            return cfg == null ? 168.0 : cfg.anchorHoursPerEgg;
        }
        if (stack.is(Items.EMERALD)) {
            return cfg == null ? 2.5 : cfg.anchorHoursPerEmerald;
        }
        if (stack.is(Items.EMERALD_BLOCK)) {
            return cfg == null ? 24.0 : cfg.anchorHoursPerEmeraldBlock;
        }
        return 0.0;
    }

    /**
     * Feed up to {@code count} items from {@code stack} into the anchor. Shared by the direct
     * right-click path and the furnace menu. Handles the dormant-needs-an-egg rule, the bank cap,
     * consumption, effects, and the status readout.
     */
    public static void feed(ServerPlayer player, BlockPos pos, AnchorState.PlacedAnchor anchor,
                            ItemStack stack, int count) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        double worth = fuelWorth(stack);
        if (worth <= 0.0 || anchor.isExempt() || cfg == null || !cfg.anchorUpkeepEnabled) {
            player.sendOverlayMessage(status(anchor, now));
            return;
        }
        // A dormant anchor's flame is out — emeralds can't relight it. Only a dragon egg can.
        if (!anchor.isActive(now) && !stack.is(Items.DRAGON_EGG)) {
            player.sendOverlayMessage(Component
                    .literal("The sanctuary is dormant — only a dragon egg can rekindle it.")
                    .withStyle(ChatFormatting.DARK_RED));
            return;
        }
        count = Math.max(1, Math.min(count, stack.getCount()));
        long base = Math.max(anchor.expiry, now);
        long cap = now + (long) (cfg.anchorMaxFuelHours * 72000.0);
        long fed = Math.min(cap, base + (long) (worth * count * 72000.0));
        if (fed <= base) {
            player.sendOverlayMessage(Component.literal("The sanctuary can hold no more power.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        int accepted = (int) Math.ceil(count * Math.min(1.0, (fed - base) / Math.max(1.0, worth * count * 72000.0)));
        accepted = Math.max(1, Math.min(count, accepted));
        anchor.expiry = fed;
        AnchorState.get().save();
        stack.shrink(accepted);

        run(level.getServer(), String.format(Locale.ROOT,
                "playsound minecraft:block.amethyst_block.chime block @a %.1f %.1f %.1f 1 0.7",
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        run(level.getServer(), String.format(Locale.ROOT,
                "particle minecraft:happy_villager %.1f %.1f %.1f 0.3 0.3 0.3 0.01 8",
                pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5));
        player.sendOverlayMessage(status(anchor, now));
    }

    private static Component status(AnchorState.PlacedAnchor anchor, long now) {
        String who = anchor.owner == null ? "server" : anchor.owner;
        String[] t = remaining(anchor, now);
        return Component.literal("SA [" + who + "] ")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("(" + t[0] + ")").withStyle(ChatFormatting.valueOf(t[1])));
    }

    /**
     * Fuzzy remaining time + color name. Fuzzy buckets above a day; under 24h it goes exact and
     * heats up toward expiry: yellow (12–24h) → gold (6–12h) → red (<6h).
     */
    public static String[] remaining(AnchorState.PlacedAnchor a, long now) {
        if (a.isExempt()) {
            return new String[]{"eternal", "LIGHT_PURPLE"};
        }
        double h = a.hoursLeft(now);
        if (h <= 0.0) {
            return new String[]{"dormant — needs a dragon egg", "DARK_RED"};
        }
        if (h > 24 * 365) {
            return new String[]{"> 1 year remaining", "GREEN"};
        }
        if (h > 24 * 30) {
            return new String[]{"> 30 days remaining", "GREEN"};
        }
        if (h > 24 * 7) {
            return new String[]{"> 7 days remaining", "GREEN"};
        }
        if (h > 24) {
            return new String[]{"> 1 day remaining", "GREEN"};
        }
        String txt = String.format(Locale.ROOT, "%.1fh remaining", h);
        return new String[]{txt, h < 6 ? "RED" : h < 12 ? "GOLD" : "YELLOW"};
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
            // Label always reflects owner + remaining time (fuzzy above a day, hot colors below).
            String who = a.owner == null ? "server" : a.owner;
            String[] t = remaining(a, now);
            setLabel(server, pos, String.format(Locale.ROOT,
                    "{text:\"SA [%s] \",color:\"light_purple\",bold:1b,"
                            + "extra:[{text:\"(%s)\",color:\"%s\",bold:0b}]}",
                    who, t[0], t[1].toLowerCase(Locale.ROOT)));
            // Low fuel warning: the crystal smokes through its final 24 hours, harder under 6.
            double h = a.hoursLeft(now);
            if (active && !a.isExempt() && h < 24.0) {
                run(server, String.format(Locale.ROOT,
                        "particle minecraft:campfire_cosy_smoke %.1f %.1f %.1f 0.12 0.25 0.12 0.004 %d",
                        a.x, a.y + 0.6, a.z, h < 6.0 ? 10 : 4));
            }
            if (last != null && last == active) {
                continue; // no transition
            }
            if (active) {
                AnchorInteraction.spawnSpinDisplay(server, pos); // the shell spins only while alive
                if (flan) {
                    FlanIntegration.createClaim(overworld, pos, cfg.flanClaimRadius);
                }
                if (last != null) {
                    Sanctuary.LOGGER.info("[sanctuary] Anchor at {},{} reawakened", pos.getX(), pos.getZ());
                }
            } else {
                AnchorInteraction.removeSpinDisplay(server, pos); // still crystal = dead crystal
                if (flan) {
                    FlanIntegration.removeClaim(overworld, pos);
                }
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
