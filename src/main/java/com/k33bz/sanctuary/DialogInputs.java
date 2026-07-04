package com.k33bz.sanctuary;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonPrimitive;
import net.minecraft.network.chat.Component;
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
}
