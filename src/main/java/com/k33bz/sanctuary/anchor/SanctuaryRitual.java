package com.k33bz.sanctuary.anchor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.Locale;

/**
 * The crafted-sanctuary ritual — a build-it alternative to lucky Sanctuary Crystal drops, in the
 * mod's established ritual idiom (a structure + a trigger block, a validity check, consumed
 * components, a dramatic effect, a spawned result). It FEEDS the existing anchor system: the
 * result is an ordinary {@link SanctuaryCrystal}, so the crafted path and the drop path converge.
 *
 * <p><b>The recipe.</b> A CONDUIT placed on top of a BEACON, a DRAGON EGG on top of the conduit,
 * and 2 SPONGE blocks on the ground within 2 blocks of the conduit — plus, in the triggering
 * player's inventory, 1 Wild Essence and 2 phantom membranes (membranes and essence aren't
 * placeable, so they're consumed from the hand/inventory). Placing either the conduit or the
 * capstone dragon egg fires the check (whichever completes the structure). On success the beacon,
 * conduit, dragon egg, and 2 sponges are set to air, the inventory reagents are consumed, a
 * conversion flash + sound plays, and the player receives a Sanctuary Crystal. Missing a piece
 * sends an actionbar hint and does nothing (no blocks or items are touched).
 */
public final class SanctuaryRitual {
    private SanctuaryRitual() {
    }

    /**
     * Called after a conduit OR a dragon egg is placed. Resolves the conduit position (the trigger
     * block or the block below the placed dragon egg) and validates the whole structure.
     */
    public static void tryForm(ServerLevel level, BlockPos placed, ServerPlayer player, SanctuaryConfig cfg) {
        if (cfg == null || !cfg.wildEssenceEnabled) {
            return;
        }
        BlockState placedState = level.getBlockState(placed);
        BlockPos conduitPos;
        if (placedState.is(Blocks.CONDUIT)) {
            conduitPos = placed;
        } else if (placedState.is(Blocks.DRAGON_EGG) && level.getBlockState(placed.below()).is(Blocks.CONDUIT)) {
            conduitPos = placed.below();
        } else {
            return; // not a ritual trigger
        }

        // Core column: beacon below the conduit, dragon egg above it.
        boolean hasBeacon = level.getBlockState(conduitPos.below()).is(Blocks.BEACON);
        boolean hasEgg = level.getBlockState(conduitPos.above()).is(Blocks.DRAGON_EGG);

        // Two sponges (dry or wet — a sponge is a sponge) within 2 blocks of the conduit.
        int sponges = 0;
        java.util.List<BlockPos> spongePositions = new java.util.ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(conduitPos.offset(-2, -2, -2), conduitPos.offset(2, 2, 2))) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.SPONGE) || s.is(Blocks.WET_SPONGE)) {
                spongePositions.add(p.immutable());
                sponges++;
            }
        }

        // Inventory reagents: 1 Wild Essence + 2 phantom membranes.
        int essence = countEssence(player);
        int membranes = countItem(player, Items.PHANTOM_MEMBRANE);

        boolean complete = hasBeacon && hasEgg && sponges >= 2 && essence >= 1 && membranes >= 2;
        if (!complete) {
            // Only nag when the player has clearly started the structure (a conduit on a beacon),
            // so ordinary conduit/egg placement elsewhere stays silent.
            if (hasBeacon) {
                player.sendOverlayMessage(hint(hasEgg, sponges, essence, membranes)
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        // Consume the structure (setblock air) and the inventory reagents.
        setAir(level, conduitPos);              // conduit
        setAir(level, conduitPos.below());      // beacon
        setAir(level, conduitPos.above());      // dragon egg
        for (int i = 0; i < 2 && i < spongePositions.size(); i++) {
            setAir(level, spongePositions.get(i));
        }
        consumeEssence(player, 1);
        consumeItem(player, Items.PHANTOM_MEMBRANE, 2);
        player.containerMenu.sendAllDataToRemote();

        // Dramatic effect (mirrors AnchorInteraction.playConversionEffect + a ritual flourish).
        run(level, String.format(Locale.ROOT,
                "particle minecraft:flash %.2f %.2f %.2f 0 0 0 0 3 force",
                conduitPos.getX() + 0.5, conduitPos.getY() + 0.9, conduitPos.getZ() + 0.5));
        run(level, String.format(Locale.ROOT,
                "particle minecraft:end_rod %.2f %.2f %.2f 0.4 0.8 0.4 0.05 60",
                conduitPos.getX() + 0.5, conduitPos.getY() + 0.9, conduitPos.getZ() + 0.5));
        run(level, String.format(Locale.ROOT,
                "playsound minecraft:block.beacon.activate block @a %.2f %.2f %.2f 1 1.4",
                conduitPos.getX() + 0.5, conduitPos.getY() + 0.9, conduitPos.getZ() + 0.5));
        run(level, String.format(Locale.ROOT,
                "playsound minecraft:block.conduit.deactivate block @a %.2f %.2f %.2f 1 0.8",
                conduitPos.getX() + 0.5, conduitPos.getY() + 0.9, conduitPos.getZ() + 0.5));

        ItemStack crystal = SanctuaryCrystal.create();
        if (!player.getInventory().add(crystal)) {
            player.drop(crystal, false);
        }
        player.sendSystemMessage(Component.literal(
                        "The wild essence binds the components — a Sanctuary Crystal takes form.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        Sanctuary.LOGGER.info("[sanctuary] {} performed the crafted-sanctuary ritual",
                player.getGameProfile().name());
    }

    /** Actionbar hint listing what the started ritual is still missing. */
    private static net.minecraft.network.chat.MutableComponent hint(
            boolean hasEgg, int sponges, int essence, int membranes) {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (!hasEgg) {
            need.add("a dragon egg atop the conduit");
        }
        if (sponges < 2) {
            need.add((2 - sponges) + " more sponge" + (2 - sponges == 1 ? "" : "s") + " within 2 blocks");
        }
        if (essence < 1) {
            need.add("1 Wild Essence in your inventory");
        }
        if (membranes < 2) {
            need.add((2 - membranes) + " more phantom membrane" + (2 - membranes == 1 ? "" : "s"));
        }
        return Component.literal("The ritual needs: " + String.join(", ", need) + ".");
    }

    private static int countEssence(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (WildEssence.isWildEssence(st)) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.is(item)) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static void consumeEssence(ServerPlayer player, int count) {
        int left = count;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (WildEssence.isWildEssence(st)) {
                int take = Math.min(left, st.getCount());
                st.shrink(take);
                left -= take;
            }
        }
    }

    private static void consumeItem(ServerPlayer player, net.minecraft.world.item.Item item, int count) {
        int left = count;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.is(item)) {
                int take = Math.min(left, st.getCount());
                st.shrink(take);
                left -= take;
            }
        }
    }

    private static void setAir(ServerLevel level, BlockPos pos) {
        run(level, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air",
                pos.getX(), pos.getY(), pos.getZ()));
    }

    private static void run(ServerLevel level, String command) {
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(), command);
    }
}
