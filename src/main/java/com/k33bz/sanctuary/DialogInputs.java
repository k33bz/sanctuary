package com.k33bz.sanctuary;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonPrimitive;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;

import java.util.List;
import java.util.Optional;

/**
 * Shared builders for 26.x native dialog INPUT controls (text fields, single-option pickers) and
 * the {@link CommandTemplate} action that submits their values into a permission-0 backend command
 * via {@code $(key)} substitution. The three 0.6.1 QoL dialogs (anchor rename, respawn picker,
 * gravekeeper ledger search) all funnel through here so the codec plumbing lives in one place.
 */
public final class DialogInputs {
    private DialogInputs() {
    }

    /** A single-line text input bound to {@code key}, for later {@code $(key)} substitution. */
    public static Input text(String key, String label, String initial, int maxLength, int width) {
        return new Input(key, new TextInput(width,
                Component.literal(label), true, initial == null ? "" : initial, maxLength, Optional.empty()));
    }

    /** A single-option picker bound to {@code key}; {@code initialId} pre-selects a matching entry. */
    public static Input singleOption(String key, String label, int width,
                                     List<SingleOptionInput.Entry> entries) {
        return new Input(key, new SingleOptionInput(width, entries, Component.literal(label), true));
    }

    /** One picker entry: submitted id, shown label, and whether it starts selected. */
    public static SingleOptionInput.Entry entry(String id, String display, boolean initial) {
        return new SingleOptionInput.Entry(id, Optional.of(Component.literal(display)), initial);
    }

    /**
     * A submit action that runs {@code template} with the dialog's input values substituted in
     * ({@code $(key)} placeholders). {@link ParsedTemplate} has no public constructor, so we decode
     * one through its string codec — the same path the vanilla dialog loader uses.
     */
    public static Optional<Action> command(String template) {
        ParsedTemplate parsed = ParsedTemplate.CODEC
                .parse(JsonOps.INSTANCE, new JsonPrimitive(template))
                .getOrThrow(msg -> new IllegalArgumentException("bad dialog command template: " + msg));
        return Optional.of(new CommandTemplate(parsed));
    }

    /**
     * Build a {@link MultiActionDialog} that is always encodable.
     *
     * <p>Its action list MUST be non-empty: the network codec rejects an empty one ("List must have
     * contents"), so the {@code show_dialog} packet fails to encode and the server DROPS THE PLAYER
     * mid-dialog. Every caller here builds its buttons conditionally, so an unlucky combination
     * (dying with no bed and no sanctuary; viewing an exempt anchor you can't rename) silently produced
     * a zero-action dialog and kicked the player. When nothing optional applies, the exit button becomes
     * the sole action instead — same UI, one button, and it always encodes.
     */
    public static Dialog multiAction(CommonDialogData common, List<ActionButton> buttons,
                                     ActionButton exit, int columns) {
        return buttons.isEmpty()
                ? new MultiActionDialog(common, List.of(exit), Optional.empty(), columns)
                : new MultiActionDialog(common, buttons, Optional.of(exit), columns);
    }
}
