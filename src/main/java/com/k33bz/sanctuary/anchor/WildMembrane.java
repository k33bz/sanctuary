package com.k33bz.sanctuary.anchor;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DamageResistant;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/**
 * Wild Membrane, raw and cooked — two textured player heads in the {@link WildEssence} idiom
 * (fixed-UUID, profile-name identity that survives the item round-trip and can't be anvil-forged).
 * They are the middle links of the crafted-sanctuary chain:
 *
 * <pre>
 *   Wild Essence + 2 Phantom Membrane + 2 Sponge  --(craft)-->  Wild Membrane (Raw)
 *   Wild Membrane (Raw)  --(temper in a LAVA cauldron)-->        Wild Membrane
 *   Wild Membrane + Conduit + Dragon Egg + 4 Bottle o' Enchanting  --(craft)-->  Sanctuary Crystal
 * </pre>
 *
 * <p>The RAW membrane is fire-resistant (the vanilla {@code minecraft:damage_resistant} component,
 * keyed to the {@code is_fire} damage tag exactly like netherite gear) so its item entity can sit
 * in lava long enough to be tempered instead of burning up. Because the fire-resistant component is
 * built from a tag it needs a registry lookup; {@link #primeFireResistance(HolderLookup.Provider)}
 * caches that at server start and every runtime {@link #createRaw()} attaches it.
 */
public final class WildMembrane {
    private WildMembrane() {
    }

    // --- raw ---

    /** Profile name marking a Raw Wild Membrane head. Anvil-proof (display names can be forged). */
    public static final String RAW_PROFILE_NAME = "WildMembraneRaw";
    private static final UUID RAW_PROFILE_UUID = UUID.fromString("5a9c7d31-4be2-4f6a-9b7e-c0ffee000003");
    /** minecraft-heads "Raw Meat / flesh" head — a fitting fleshy, uncured look. */
    public static final String RAW_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjE1ZTgyMjg3ZWY3NzViMzVkZjVkNmFlMjE4NmQxYmMzNjA2ODFkNzc1ZTk2Y2MzMWY1YTU3NDcwZDkxYjYzYiJ9fX0=";

    // --- cooked ---

    /** Profile name marking a (tempered) Wild Membrane head. */
    public static final String PROFILE_NAME = "WildMembrane";
    private static final UUID PROFILE_UUID = UUID.fromString("5a9c7d31-4be2-4f6a-9b7e-c0ffee000004");
    /** minecraft-heads "Cured Leather / tempered hide" head — a cured, hardened look. */
    public static final String TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmYzNDU4YmU5NjcyNmY2Zjc4YjljYjRkNzRjOTljOTRlNGY0YTQ2NmYyODUyNjcwODc1YWQ0ZmZjMzY3OTIwMSJ9fX0=";

    /**
     * The fire-resistance component value, cached at server start. Built from the {@code is_fire}
     * damage-type tag, so it needs a registry lookup that only exists once the server is up.
     */
    private static volatile DamageResistant fireResistance;

    /** Cache the fire-resistant component from a registry provider (call once at server start). */
    public static void primeFireResistance(HolderLookup.Provider registries) {
        if (registries == null) {
            return;
        }
        try {
            fireResistance = new DamageResistant(
                    registries.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypeTags.IS_FIRE));
        } catch (RuntimeException ignored) {
            // Leave it null; createRaw() falls back to no component (still identified by profile).
        }
    }

    /** Build one Raw Wild Membrane item (fire-resistant when the registry cache is primed). */
    public static ItemStack createRaw() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        applyProfile(stack, RAW_PROFILE_UUID, RAW_PROFILE_NAME, RAW_TEXTURE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Wild Membrane (Raw)")
                .withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false).withBold(true)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Fleshy and untempered.").withStyle(ChatFormatting.GRAY),
                Component.literal("Temper it in a cauldron of lava.").withStyle(ChatFormatting.DARK_GRAY))));
        DamageResistant fr = fireResistance;
        if (fr != null) {
            stack.set(DataComponents.DAMAGE_RESISTANT, fr);
        }
        return stack;
    }

    /** Build one (tempered) Wild Membrane item. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        applyProfile(stack, PROFILE_UUID, PROFILE_NAME, TEXTURE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Wild Membrane")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withItalic(false).withBold(true)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Tempered in lava, tough as hide.").withStyle(ChatFormatting.GRAY),
                Component.literal("A reagent for the Sanctuary Crystal.").withStyle(ChatFormatting.DARK_GRAY))));
        return stack;
    }

    private static void applyProfile(ItemStack stack, UUID uuid, String name, String texture) {
        com.mojang.authlib.properties.PropertyMap properties = new com.mojang.authlib.properties.PropertyMap(
                com.google.common.collect.ImmutableMultimap.of("textures", new Property("textures", texture)));
        GameProfile profile = new GameProfile(uuid, name, properties);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
    }

    // --- identity ---

    /** Whether this profile is a Raw Wild Membrane. */
    public static boolean isRaw(ResolvableProfile profile) {
        return profile != null && profile.name().map(RAW_PROFILE_NAME::equals).orElse(false);
    }

    /** Whether this stack is a Raw Wild Membrane head. */
    public static boolean isRaw(ItemStack stack) {
        return stack.is(Items.PLAYER_HEAD) && isRaw(stack.get(DataComponents.PROFILE));
    }

    /** Whether this profile is a (cooked) Wild Membrane. */
    public static boolean isCooked(ResolvableProfile profile) {
        return profile != null && profile.name().map(PROFILE_NAME::equals).orElse(false);
    }

    /** Whether this stack is a (cooked) Wild Membrane head. */
    public static boolean isCooked(ItemStack stack) {
        return stack.is(Items.PLAYER_HEAD) && isCooked(stack.get(DataComponents.PROFILE));
    }
}
