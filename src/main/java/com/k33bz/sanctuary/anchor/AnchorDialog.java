package com.k33bz.sanctuary.anchor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Native-dialog anchor menu (the A/B alternative to the sgui furnace menu, toggled by
 * {@code anchorDialogMenu}). Built entirely on vanilla's 1.21.6+ server-driven Dialog system:
 * no container metaphor — the crystal's status as text, and feed BUTTONS that pull fuel from
 * the player's inventory via a permission-0 command. Zero extra dependencies.
 */
public final class AnchorDialog {
    private AnchorDialog() {
    }

    public static void open(ServerPlayer player, BlockPos pos, AnchorState.PlacedAnchor anchor) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        long now = player.level().getGameTime();
        String who = anchor.owner == null ? "server" : anchor.owner;
        String[] t = AnchorUpkeep.remaining(anchor, now);

        List<DialogBody> body = new ArrayList<>();
        body.add(new ItemBody(new ItemStackTemplate(Items.PLAYER_HEAD),
                Optional.of(new PlainMessage(Component.literal("Owner: " + who)
                        .withStyle(ChatFormatting.GRAY), 200)),
                true, false, 16, 16));
        // One combined message block: separate body elements each get their own padded row,
        // so multi-line-in-one keeps the dialog compact.
        var status = Component.literal(t[0]).withStyle(ChatFormatting.valueOf(t[1]));
        if (!anchor.isExempt()) {
            double cap = cfg == null ? 1536.0 : cfg.anchorMaxFuelHours;
            status.append(Component.literal(String.format(Locale.ROOT,
                    "\n%.1f h banked (cap %.0f h)", anchor.hoursLeft(now), cap))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (anchor.ownerId != null) {
            status.append(Component.literal("\nid: " + anchor.ownerId.substring(0, 8))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        body.add(new PlainMessage(status, 250));

        List<ActionButton> buttons = new ArrayList<>();
        if (!anchor.isExempt()) {
            boolean dormant = !anchor.isActive(now);
            if (!dormant) {
                buttons.add(feedButton("Feed 1 emerald (+" + hrs(cfg == null ? 2.5 : cfg.anchorHoursPerEmerald) + ")",
                        "emerald", 1));
                buttons.add(feedButton("Feed 1 block (+" + hrs(cfg == null ? 24 : cfg.anchorHoursPerEmeraldBlock) + ")",
                        "block", 1));
                buttons.add(feedButton("Feed 8 blocks (+" + hrs(8 * (cfg == null ? 24 : cfg.anchorHoursPerEmeraldBlock)) + ")",
                        "block", 8));
            }
            buttons.add(feedButton("Feed dragon egg (+" + hrs(cfg == null ? 168 : cfg.anchorHoursPerEgg) + ")",
                    "egg", 1));
            buttons.add(feedButton("Refresh", "status", 0));
        }

        CommonDialogData common = new CommonDialogData(
                Component.literal("Sanctuary Anchor"),
                Optional.empty(),
                true,   // closable with escape
                false,  // don't pause (server dialogs never should)
                DialogAction.CLOSE, // clicking a button closes; the feed command re-opens fresh
                body,
                List.of());
        Dialog dialog = new MultiActionDialog(common, buttons,
                Optional.of(new ActionButton(new CommonButtonData(Component.literal("Close"), 100),
                        Optional.empty())),
                2);
        player.openDialog(Holder.direct(dialog));
    }

    private static ActionButton feedButton(String label, String type, int count) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 160),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(
                        "sanctuaryfeed " + type + " " + count))));
    }

    private static String hrs(double h) {
        return h >= 24 ? String.format(Locale.ROOT, "%.0fd", h / 24.0)
                : String.format(Locale.ROOT, "%.1fh", h);
    }
}
