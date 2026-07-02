package com.k33bz.sanctuary.anchor;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The sgui menu shown when a player right-clicks a beacon: power it with a dragon egg. */
public final class AnchorGui {
    private AnchorGui() {
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        boolean active = AnchorState.get().isAnchor(pos);
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_3x3, player, false);
        gui.setTitle(Component.literal(active ? "Sanctuary Anchor" : "Inert Beacon")
                .withStyle(ChatFormatting.LIGHT_PURPLE));

        for (int i = 0; i < 9; i++) {
            gui.setSlot(i, new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE).setName(Component.empty()).build());
        }

        if (active) {
            gui.setSlot(4, new GuiElementBuilder(Items.DRAGON_EGG)
                    .setName(Component.literal("Powered").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .addLoreLine(Component.literal("This beacon is a sanctuary anchor.").withStyle(ChatFormatting.GRAY))
                    .addLoreLine(Component.literal("Break it to reclaim the egg.").withStyle(ChatFormatting.DARK_GRAY))
                    .glow()
                    .build());
        } else {
            gui.setSlot(4, new GuiElementBuilder(Items.DRAGON_EGG)
                    .setName(Component.literal("Insert Dragon Egg").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .addLoreLine(Component.literal("Click with a Dragon Egg in your").withStyle(ChatFormatting.GRAY))
                    .addLoreLine(Component.literal("inventory to power the anchor.").withStyle(ChatFormatting.GRAY))
                    .setCallback((index, type, action, ignored) -> power(player, pos, gui))
                    .build());
        }
        gui.open();
    }

    private static void power(ServerPlayer player, BlockPos pos, SimpleGui gui) {
        if (AnchorState.get().isAnchor(pos)) {
            gui.close();
            return;
        }
        if (!player.getAbilities().instabuild && !consumeEgg(player)) {
            player.sendSystemMessage(Component.literal("You need a Dragon Egg to power the anchor.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        AnchorState.get().ensureRegistered(pos);
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            AnchorInteraction.spawnEggDisplay(server, pos);
            AnchorInteraction.playConversionEffect(server, pos);
        }
        gui.close();
    }

    private static boolean consumeEgg(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.DRAGON_EGG)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }
}
