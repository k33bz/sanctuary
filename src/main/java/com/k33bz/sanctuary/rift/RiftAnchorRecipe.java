package com.k33bz.sanctuary.rift;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * A shapeless SPECIAL recipe filling the whole 3x3 (9 items, no free slots) into one
 * {@link RiftAnchor}: 1 Ender Pearl (the tear), 4 Obsidian (frame), 4 Amethyst Shard (resonance).
 *
 * <p>Modeled on {@code SanctuaryCrystalRecipe}: it must be a {@code CustomRecipe} because the result
 * is a textured player head, which a JSON recipe (id-only output) can't express. Ingredients are all
 * vanilla and match by id; the result is computed server-side, so vanilla clients craft it
 * unmodified. Its data json is {@code data/sanctuary/recipe/rift_anchor.json} = {@code {"type": ...}}.
 */
public final class RiftAnchorRecipe extends CustomRecipe {

    public static final RiftAnchorRecipe INSTANCE = new RiftAnchorRecipe();
    public static RecipeSerializer<RiftAnchorRecipe> serializer;

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return matches(input);
    }

    /** Pure grid predicate (no Level needed) — exposed for unit tests. */
    public static boolean matches(CraftingInput input) {
        int pearl = 0;
        int obsidian = 0;
        int amethyst = 0;
        for (ItemStack stack : input.items()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.ENDER_PEARL)) {
                pearl += stack.getCount();
            } else if (stack.is(Items.OBSIDIAN)) {
                obsidian += stack.getCount();
            } else if (stack.is(Items.AMETHYST_SHARD)) {
                amethyst += stack.getCount();
            } else {
                return false; // any foreign item disqualifies
            }
        }
        return pearl == 1 && obsidian == 4 && amethyst == 4;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return RiftAnchor.create();
    }

    @Override
    public RecipeSerializer<RiftAnchorRecipe> getSerializer() {
        return serializer;
    }
}
