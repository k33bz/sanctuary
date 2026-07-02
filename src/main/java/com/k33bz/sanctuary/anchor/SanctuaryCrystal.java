package com.k33bz.sanctuary.anchor;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/**
 * The Sanctuary Crystal — a player head wearing the crystal skin. Placing it forms a sanctuary
 * anchor; it drops rarely from high-tier wildlands mobs. Identification is by the head's profile
 * name, which survives the item → placed-skull → drop round-trip natively (no custom block, no
 * client mod, no Polymer).
 */
public final class SanctuaryCrystal {
    private SanctuaryCrystal() {
    }

    /** Profile name marking a crystal head. Display names can be anvil-forged; this can't. */
    public static final String PROFILE_NAME = "SanctuaryCrystal";

    /** Fixed profile UUID so stacked crystals stay stackable. */
    private static final UUID PROFILE_UUID = UUID.fromString("5a9c7d31-4be2-4f6a-9b7e-c0ffee000001");

    /** minecraft-heads.com "Crystal" skin (same texture the anchor visuals use). */
    public static final String CRYSTAL_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODMyMGIzZDQzYTRlY2EzMTZiM2MwZmJlZTU0N2FjZTJjNjBlZTcyM2NiZjMxYjgzZjQ5ZjhkNDM1NDgxMTdjNSJ9fX0=";

    /** Build one Sanctuary Crystal item. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        com.mojang.authlib.properties.PropertyMap properties = new com.mojang.authlib.properties.PropertyMap(
                com.google.common.collect.ImmutableMultimap.of("textures",
                        new Property("textures", CRYSTAL_TEXTURE)));
        GameProfile profile = new GameProfile(PROFILE_UUID, PROFILE_NAME, properties);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        // CUSTOM_NAME persists through the placed skull's block entity, so the dropped head keeps it.
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Sanctuary Crystal")
                .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false).withBold(true)));
        stack.set(DataComponents.RARITY, Rarity.EPIC);
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Place it to raise a sanctuary.").withStyle(ChatFormatting.GRAY))));
        return stack;
    }

    /** Whether this profile is a crystal (works for item components and placed skull entities). */
    public static boolean isCrystal(ResolvableProfile profile) {
        return profile != null && profile.name().map(PROFILE_NAME::equals).orElse(false);
    }

    /** Whether this item stack is a crystal head. */
    public static boolean isCrystal(ItemStack stack) {
        return stack.is(Items.PLAYER_HEAD) && isCrystal(stack.get(DataComponents.PROFILE));
    }
}
