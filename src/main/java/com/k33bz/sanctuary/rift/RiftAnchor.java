package com.k33bz.sanctuary.rift;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/**
 * Rift Anchor — a textured player head (identified by its profile name, exactly like
 * {@link com.k33bz.sanctuary.anchor.WildEssence} and {@code SanctuaryCrystal}, so it survives the
 * item round-trip and can't be anvil-forged). Used on the ground OUTSIDE any sanctuary it tears a
 * persistent rift to the {@code sanctuary:resource_world} gathering dimension; used inside a
 * sanctuary it does nothing (a sanctuary's peace holds space together). See {@link Rifts}.
 */
public final class RiftAnchor {
    private RiftAnchor() {
    }

    /** Profile name marking a Rift Anchor head. Anvil-proof (display names can be forged; this can't). */
    public static final String PROFILE_NAME = "RiftAnchor";

    /** Fixed profile UUID so stacked anchors stay stackable. */
    private static final UUID PROFILE_UUID = UUID.fromString("5a9c7d31-4be2-4f6a-9b7e-c0ffee000003");

    /** minecraft-heads "Ender Eye" style texture — a void-black head with a violet iris, fitting a rift. */
    public static final String ANCHOR_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzE2ZjkwODA2ZTU5ZmVhZDczZjE3ZWFmNTM2NDNjNGM4ODk4MjZmZjZlNjhkZWY4YzU0Mzk4NmNkOTQ5MTdkMSJ9fX0=";

    /** Build one Rift Anchor item. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        com.mojang.authlib.properties.PropertyMap properties = new com.mojang.authlib.properties.PropertyMap(
                com.google.common.collect.ImmutableMultimap.of("textures",
                        new Property("textures", ANCHOR_TEXTURE)));
        GameProfile profile = new GameProfile(PROFILE_UUID, PROFILE_NAME, properties);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Rift Anchor")
                .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false).withBold(true)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Use it on open ground beyond a sanctuary").withStyle(ChatFormatting.GRAY),
                Component.literal("to tear a rift to the gathering world.").withStyle(ChatFormatting.GRAY),
                Component.literal("It will not open where a sanctuary holds space.").withStyle(ChatFormatting.DARK_GRAY))));
        return stack;
    }

    /** Whether this profile is a Rift Anchor (works for item components and placed skull entities). */
    public static boolean isRiftAnchor(ResolvableProfile profile) {
        return profile != null && profile.name().map(PROFILE_NAME::equals).orElse(false);
    }

    /** Whether this item stack is a Rift Anchor head. */
    public static boolean isRiftAnchor(ItemStack stack) {
        return stack.is(Items.PLAYER_HEAD) && isRiftAnchor(stack.get(DataComponents.PROFILE));
    }
}
