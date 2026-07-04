package com.k33bz.sanctuary.anchor;

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
 * Wild Essence — the distilled ferocity of the frontier's monsters, a textured player head like
 * {@link SanctuaryCrystal}. It is a RITUAL REAGENT, not an anchor: gathered from Warden and
 * high-tier kills, it is consumed (with two phantom membranes) by the crafted-sanctuary ritual to
 * form a Sanctuary Crystal. Identification is by the head's profile name, which survives the item
 * round-trip and can't be anvil-forged — exactly like the crystal.
 */
public final class WildEssence {
    private WildEssence() {
    }

    /** Profile name marking a Wild Essence head. Anvil-proof (display names can be forged; this can't). */
    public static final String PROFILE_NAME = "WildEssence";

    /** Fixed profile UUID so stacked essences stay stackable. */
    private static final UUID PROFILE_UUID = UUID.fromString("5a9c7d31-4be2-4f6a-9b7e-c0ffee000002");

    /** minecraft-heads "Eye of Ender" head — a glowing ender eye, fitting a wild distillation. */
    public static final String ESSENCE_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGFhOGZjOGRlNjQxN2I0OGQ0OGM4MGI0NDNjZjUzMjZlM2Q5ZGE0ZGJlOWIyNWZjZDQ5NTQ5ZDk2MTY4ZmMwIn19fQ==";

    /** Build one Wild Essence item. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        com.mojang.authlib.properties.PropertyMap properties = new com.mojang.authlib.properties.PropertyMap(
                com.google.common.collect.ImmutableMultimap.of("textures",
                        new Property("textures", ESSENCE_TEXTURE)));
        GameProfile profile = new GameProfile(PROFILE_UUID, PROFILE_NAME, properties);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Wild Essence")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_GREEN).withItalic(false).withBold(true)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("The frontier's ferocity, distilled.").withStyle(ChatFormatting.GRAY),
                Component.literal("A reagent for the sanctuary ritual.").withStyle(ChatFormatting.DARK_GRAY))));
        return stack;
    }

    /** Whether this profile is a Wild Essence (works for item components and placed skull entities). */
    public static boolean isWildEssence(ResolvableProfile profile) {
        return profile != null && profile.name().map(PROFILE_NAME::equals).orElse(false);
    }

    /** Whether this item stack is a Wild Essence head. */
    public static boolean isWildEssence(ItemStack stack) {
        return stack.is(Items.PLAYER_HEAD) && isWildEssence(stack.get(DataComponents.PROFILE));
    }
}
