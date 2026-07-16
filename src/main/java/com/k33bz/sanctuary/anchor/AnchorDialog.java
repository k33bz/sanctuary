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
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
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

        // One combined text block right under the title — the icon row was pushing everything
        // down, and separate body elements each get their own padded row.
        List<DialogBody> body = new ArrayList<>();
        var status = Component.literal(
                (anchor.name != null && !anchor.name.isBlank() ? "\"" + anchor.name + "\"\n" : "")
                        + "Owner: " + who).withStyle(ChatFormatting.GRAY);
        status.append(Component.literal("\n" + t[0]).withStyle(ChatFormatting.valueOf(t[1])));
        if (!anchor.isExempt()) {
            double cap = cfg == null ? 1536.0 : cfg.anchorMaxFuelHours;
            status.append(Component.literal(String.format(Locale.ROOT,
                    "\n%.1f h banked (cap %.0f h)", anchor.hoursLeft(now), cap))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (anchor.id != null) {
            status.append(Component.literal("\nid: " + anchor.id.substring(0, 8))
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
        // The owner (or a creative admin) may name the sanctuary — opens a text-input dialog.
        if (canRename(player, anchor)) {
            buttons.add(new ActionButton(
                    new CommonButtonData(Component.literal(
                            anchor.name == null || anchor.name.isBlank() ? "Name this sanctuary" : "Rename"), 160),
                    Optional.of(new StaticAction(new ClickEvent.RunCommand("sanctuaryrename")))));
        }

        CommonDialogData common = new CommonDialogData(
                Component.literal("Sanctuary Anchor"),
                Optional.empty(),
                true,   // closable with escape
                false,  // don't pause (server dialogs never should)
                DialogAction.CLOSE, // clicking a button closes; the feed command re-opens fresh
                body,
                List.of());
        // Guarded: an EXEMPT anchor viewed by someone who can't rename it yields no buttons at all,
        // which would fail to encode and disconnect them. See DialogInputs.multiAction.
        Dialog dialog = com.k33bz.sanctuary.DialogInputs.multiAction(common, buttons,
                new ActionButton(new CommonButtonData(Component.literal("Close"), 100), Optional.empty()),
                2);
        player.openDialog(Holder.direct(dialog));
    }

    private static ActionButton feedButton(String label, String type, int count) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 160),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(
                        "sanctuaryfeed " + type + " " + count))));
    }

    /** Owner (by UUID) or a creative admin may rename. Server-owned anchors need creative. */
    public static boolean canRename(ServerPlayer player, AnchorState.PlacedAnchor anchor) {
        if (player.isCreative()) {
            return true;
        }
        return anchor.ownerId != null && anchor.ownerId.equals(player.getUUID().toString());
    }

    /**
     * The rename dialog: a single text input ("Name this sanctuary") whose value is submitted to
     * the permission-0 {@code sanctuaryrename set $(name)} backend via a command template. Acts on
     * the nearest owned anchor within reach (resolved server-side by the backend command).
     */
    public static void openRename(ServerPlayer player, AnchorState.PlacedAnchor anchor) {
        List<DialogBody> body = new ArrayList<>();
        body.add(new PlainMessage(Component.literal(
                "Give this sanctuary a name (up to 24 characters).").withStyle(ChatFormatting.GRAY), 250));
        List<net.minecraft.server.dialog.Input> inputs = List.of(
                com.k33bz.sanctuary.DialogInputs.text("name", "Name",
                        anchor.name == null ? "" : anchor.name, 24, 200));
        CommonDialogData common = new CommonDialogData(
                Component.literal("Name this Sanctuary"),
                Optional.empty(),
                true,
                false,
                DialogAction.CLOSE,
                body,
                inputs);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(new ActionButton(new CommonButtonData(Component.literal("Confirm"), 160),
                // "*" sentinel: an empty name must not expand to a trailing-space command (client
                // parse fails → confirm screen). Backend strips it; blank still means "clear".
                com.k33bz.sanctuary.DialogInputs.command("sanctuaryrename set *$(name)")));
        Dialog dialog = com.k33bz.sanctuary.DialogInputs.multiAction(common, buttons,
                new ActionButton(new CommonButtonData(Component.literal("Cancel"), 100), Optional.empty()),
                1);
        player.openDialog(Holder.direct(dialog));
    }

    private static String hrs(double h) {
        return h >= 24 ? String.format(Locale.ROOT, "%.0fd", h / 24.0)
                : String.format(Locale.ROOT, "%.1fh", h);
    }
}
