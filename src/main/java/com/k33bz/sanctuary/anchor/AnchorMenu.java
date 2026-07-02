package com.k33bz.sanctuary.anchor;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.ScreenProperty;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.List;
import java.util.Locale;

/**
 * Furnace-style anchor menu (opened by an empty-hand click on the crystal): drop fuel on the
 * input slot to feed the sanctuary, the flame gauge shows the banked charge against the cap, and
 * the locked output crystal reads the anchor's owner + remaining time. Server-driven (sgui), so
 * vanilla clients render it natively.
 */
public class AnchorMenu extends SimpleGui {
    private final AnchorState.PlacedAnchor anchor;
    private final BlockPos pos;

    public static void open(ServerPlayer player, BlockPos pos, AnchorState.PlacedAnchor anchor) {
        new AnchorMenu(player, pos, anchor).open();
    }

    private AnchorMenu(ServerPlayer player, BlockPos pos, AnchorState.PlacedAnchor anchor) {
        super(MenuType.FURNACE, player, false);
        this.anchor = anchor;
        this.pos = pos;
        this.setTitle(Component.literal("Sanctuary Anchor"));
        refresh();
    }

    @Override
    public void onOpen() {
        super.onOpen();
        refresh(); // properties (flame gauge) only reach the client once the screen exists
    }

    private int tickCounter = 0;

    @Override
    public void onTick() {
        super.onTick();
        if (++tickCounter >= 20) {
            tickCounter = 0;
            refresh(); // live countdown: the timer, clock, and flame update every second
        }
    }

    private void refresh() {
        long now = player.level().getGameTime();
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        boolean dormant = !anchor.isExempt() && !anchor.isActive(now);

        // Input (top): where fuel goes. Click it with fuel on your cursor.
        if (anchor.isExempt()) {
            this.setSlot(0, new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("Eternal").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .setLore(List.of(Component.literal("This sanctuary needs no fuel.")
                            .withStyle(ChatFormatting.GRAY))));
        } else {
            GuiElementBuilder input = new GuiElementBuilder(dormant ? Items.DRAGON_EGG : Items.EMERALD)
                    .setName(Component.literal(dormant ? "Insert a dragon egg" : "Insert fuel")
                            .withStyle(dormant ? ChatFormatting.DARK_RED : ChatFormatting.GREEN))
                    .setCallback((index, type, action, gui) -> deposit(type));
            if (dormant) {
                input.setLore(List.of(
                        Component.literal("The flame is out — only a dragon egg").withStyle(ChatFormatting.GRAY),
                        Component.literal("can rekindle it (+7 days).").withStyle(ChatFormatting.GRAY),
                        Component.literal("Click with the egg on your cursor.").withStyle(ChatFormatting.DARK_GRAY)));
            } else {
                input.setLore(List.of(
                        Component.literal("Emerald: +" + fmtHours(cfg == null ? 2.5 : cfg.anchorHoursPerEmerald))
                                .withStyle(ChatFormatting.GRAY),
                        Component.literal("Emerald block: +" + fmtHours(cfg == null ? 24 : cfg.anchorHoursPerEmeraldBlock)
                                + " (best rate)").withStyle(ChatFormatting.GRAY),
                        Component.literal("Dragon egg: +" + fmtHours(cfg == null ? 168 : cfg.anchorHoursPerEgg))
                                .withStyle(ChatFormatting.GRAY),
                        Component.literal("Left-click: whole cursor stack — Right-click: one")
                                .withStyle(ChatFormatting.DARK_GRAY)));
            }
            this.setSlot(0, input);
        }

        // Fuel gauge (bottom): the banked charge in plain numbers.
        double cap = cfg == null ? 1536.0 : cfg.anchorMaxFuelHours;
        double hours = anchor.isExempt() ? Double.MAX_VALUE : anchor.hoursLeft(now);
        this.setSlot(1, new GuiElementBuilder(Items.CLOCK)
                .setName(Component.literal("Charge").withStyle(ChatFormatting.GOLD))
                .setLore(List.of(anchor.isExempt()
                        ? Component.literal("∞ — eternal").withStyle(ChatFormatting.LIGHT_PURPLE)
                        : Component.literal(String.format(Locale.ROOT, "%.1f h banked (cap %.0f h)", hours, cap))
                                .withStyle(ChatFormatting.GRAY))));

        // Output (right, locked): the crystal's full status — owner, UUID, live countdown.
        String who = anchor.owner == null ? "server" : anchor.owner;
        String[] t = AnchorUpkeep.remaining(anchor, now);
        ItemStack crystal = SanctuaryCrystal.create();
        crystal.set(DataComponents.CUSTOM_NAME, Component.literal("Sanctuary Anchor")
                .withStyle(s -> s.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false).withBold(true)));
        java.util.List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.literal("Owner: " + who).withStyle(ChatFormatting.GRAY));
        if (anchor.id != null) {
            lore.add(Component.literal("id: " + anchor.id.substring(0, 8)).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!anchor.isExempt()) {
            lore.add(Component.literal(countdown(anchor.hoursLeft(now)))
                    .withStyle(ChatFormatting.valueOf(t[1])));
        }
        lore.add(Component.literal("(" + t[0] + ")").withStyle(ChatFormatting.valueOf(t[1])));
        this.setSlot(2, GuiElementBuilder.from(crystal).setLore(lore));

        // Flame gauge = charge fraction of the cap (a full flame is a fully banked sanctuary).
        int frac = anchor.isExempt() ? 1000
                : (int) Math.round(1000.0 * Math.max(0.0, Math.min(1.0, hours / Math.max(1.0, cap))));
        this.sendProperty(ScreenProperty.MAX_FUEL_BURN_TIME, 1000);
        this.sendProperty(ScreenProperty.FIRE_LEVEL, frac);
    }

    /** Feed from the player's cursor stack: left = all of it, right = one item. */
    private void deposit(ClickType type) {
        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor.isEmpty()) {
            return;
        }
        int count = type.isRight ? 1 : cursor.getCount();
        AnchorUpkeep.feed(player, pos, anchor, cursor, count);
        refresh();
    }

    /** Precise live countdown, e.g. "6d 14h 22m 08s". */
    private static String countdown(double hours) {
        long total = Math.max(0L, (long) (hours * 3600.0));
        long d = total / 86400, h = (total % 86400) / 3600, m = (total % 3600) / 60, sec = total % 60;
        return d > 0
                ? String.format(Locale.ROOT, "%dd %dh %02dm %02ds", d, h, m, sec)
                : String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, sec);
    }

    private static String fmtHours(double h) {
        return h >= 24 ? String.format(Locale.ROOT, "%.0f days", h / 24.0)
                : String.format(Locale.ROOT, "%.0f h", h);
    }
}
