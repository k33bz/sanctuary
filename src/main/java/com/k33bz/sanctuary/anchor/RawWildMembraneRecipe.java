package com.k33bz.sanctuary.anchor;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * A shapeless SPECIAL crafting recipe that turns the frontier's leavings into a Raw Wild Membrane:
 * exactly 1 {@link WildEssence} + 2 Phantom Membrane + 2 Sponge (dry or wet — a sponge is a sponge),
 * and nothing else in the grid.
 *
 * <p>It has to be a {@code CustomRecipe} rather than a vanilla JSON recipe because the Wild Essence
 * is a textured player head: a JSON recipe matches only by item id and would accept ANY player head.
 * {@link #matches} inspects the head's profile component, so a plain or mob head is rejected. The
 * result is computed server-side, so vanilla clients craft it without any client mod.
 */
public final class RawWildMembraneRecipe extends CustomRecipe {

    public static final RawWildMembraneRecipe INSTANCE = new RawWildMembraneRecipe();
    public static RecipeSerializer<RawWildMembraneRecipe> serializer;

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return matches(input);
    }

    /** Pure grid predicate (no Level needed) — exposed for unit tests. */
    public static boolean matches(CraftingInput input) {
        int essence = 0;
        int membrane = 0;
        int sponge = 0;
        for (ItemStack stack : input.items()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (WildEssence.isWildEssence(stack)) {
                essence += stack.getCount();
            } else if (stack.is(Items.PHANTOM_MEMBRANE)) {
                membrane += stack.getCount();
            } else if (stack.is(Items.SPONGE) || stack.is(Items.WET_SPONGE)) {
                sponge += stack.getCount();
            } else {
                return false; // any foreign item (incl. a plain/mob player head) disqualifies
            }
        }
        return essence == 1 && membrane == 2 && sponge == 2;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return WildMembrane.createRaw();
    }

    @Override
    public RecipeSerializer<RawWildMembraneRecipe> getSerializer() {
        return serializer;
    }
}
