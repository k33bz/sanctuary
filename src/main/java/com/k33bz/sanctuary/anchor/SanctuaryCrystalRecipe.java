package com.k33bz.sanctuary.anchor;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * The final link of the crafted-sanctuary chain: a shapeless SPECIAL recipe filling the whole 3x3
 * (9 items, no free slots) and binding them into the existing {@link SanctuaryCrystal}:
 *
 * <ul>
 *   <li>1 (tempered) {@link WildMembrane} (component-matched)</li>
 *   <li>1 Conduit, 1 Dragon Egg</li>
 *   <li>3 Bottle o' Enchanting ({@link Items#EXPERIENCE_BOTTLE})</li>
 *   <li>1 Ominous Bottle ({@link Items#OMINOUS_BOTTLE}) — any bad-omen amplifier accepted</li>
 *   <li>1 Rabbit's Foot ({@link Items#RABBIT_FOOT}), 1 Poisonous Potato ({@link Items#POISONOUS_POTATO})</li>
 * </ul>
 *
 * <p>It must be a {@code CustomRecipe}: the Wild Membrane is a textured player head, so a JSON
 * recipe (id-only matching) would accept any player head. {@link #matches} component-matches the
 * membrane; the vanilla items match by id trivially. Result computed server-side, so vanilla
 * clients craft it unmodified. The output is the unchanged Sanctuary Crystal — the crafted path and
 * the (retired) drop path once converged here; now crafting is the ONLY path to a crystal.
 */
public final class SanctuaryCrystalRecipe extends CustomRecipe {

    public static final SanctuaryCrystalRecipe INSTANCE = new SanctuaryCrystalRecipe();
    public static RecipeSerializer<SanctuaryCrystalRecipe> serializer;

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return matches(input);
    }

    /** Pure grid predicate (no Level needed) — exposed for unit tests. */
    public static boolean matches(CraftingInput input) {
        int membrane = 0;
        int conduit = 0;
        int egg = 0;
        int xpBottles = 0;
        int ominous = 0;
        int rabbitFoot = 0;
        int poisonPotato = 0;
        for (ItemStack stack : input.items()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (WildMembrane.isCooked(stack)) {
                membrane += stack.getCount();
            } else if (stack.is(Items.CONDUIT)) {
                conduit += stack.getCount();
            } else if (stack.is(Items.DRAGON_EGG)) {
                egg += stack.getCount();
            } else if (stack.is(Items.EXPERIENCE_BOTTLE)) {
                xpBottles += stack.getCount();
            } else if (stack.is(Items.OMINOUS_BOTTLE)) {
                ominous += stack.getCount(); // any bad-omen amplifier level accepted
            } else if (stack.is(Items.RABBIT_FOOT)) {
                rabbitFoot += stack.getCount();
            } else if (stack.is(Items.POISONOUS_POTATO)) {
                poisonPotato += stack.getCount();
            } else {
                return false; // any foreign item (incl. a raw membrane or a plain head) disqualifies
            }
        }
        return membrane == 1 && conduit == 1 && egg == 1 && xpBottles == 3
                && ominous == 1 && rabbitFoot == 1 && poisonPotato == 1;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        return SanctuaryCrystal.create();
    }

    @Override
    public RecipeSerializer<SanctuaryCrystalRecipe> getSerializer() {
        return serializer;
    }
}
