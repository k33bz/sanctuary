package com.k33bz.sanctuary.anchor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the 0.8.0 crafted-sanctuary chain: the two special-recipe {@code matches()}
 * predicates (true ONLY for the exact real inputs; a plain player head / wrong counts / extras are
 * rejected), each recipe's {@code assemble()} output identity, and the lava-cauldron cook predicate
 * (raw membrane over a LAVA cauldron cooks; over a WATER cauldron it does not).
 *
 * <p>These touch {@code ItemStack}/{@code DataComponents}/registries, so Minecraft is bootstrapped
 * once (the standard pattern for item-touching tests in the loom test environment).
 */
class WildMembraneChainTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // In a plain JUnit run the registry-load step that binds each item's components onto its
        // registry holder never fires, so a fresh ItemStack throws "Components not bound yet".
        // Drive that step ourselves: build the pending component sets against a full registry
        // lookup and apply them (exactly what the server does after a registry reload).
        var provider = net.minecraft.data.registries.VanillaRegistries.createLookup();
        for (var pending : net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(provider)) {
            pending.apply();
        }
    }

    /** A 3x3 grid from a flat list (padded to 9 with empties), as vanilla crafting builds it. */
    private static CraftingInput grid(List<ItemStack> stacks) {
        java.util.List<ItemStack> nine = new java.util.ArrayList<>(stacks);
        while (nine.size() < 9) {
            nine.add(ItemStack.EMPTY);
        }
        return CraftingInput.of(3, 3, nine);
    }

    private static ItemStack plainHead() {
        return new ItemStack(Items.PLAYER_HEAD);
    }

    // --- RawWildMembraneRecipe ---

    @Test
    void rawRecipeMatchesExactInputs() {
        CraftingInput in = grid(List.of(
                WildEssence.create(),
                new ItemStack(Items.PHANTOM_MEMBRANE, 2),
                new ItemStack(Items.SPONGE, 2)));
        assertTrue(RawWildMembraneRecipe.matches(in), "1 essence + 2 membrane + 2 sponge must match");
    }

    @Test
    void rawRecipeAcceptsWetSpongeToo() {
        CraftingInput in = grid(List.of(
                WildEssence.create(),
                new ItemStack(Items.PHANTOM_MEMBRANE, 2),
                new ItemStack(Items.SPONGE),
                new ItemStack(Items.WET_SPONGE)));
        assertTrue(RawWildMembraneRecipe.matches(in), "dry+wet sponge should still count as 2 sponges");
    }

    @Test
    void rawRecipeRejectsPlainHeadInPlaceOfEssence() {
        // Red-prove: a PLAIN player head must NOT satisfy the recipe (component matching, not id).
        CraftingInput in = grid(List.of(
                plainHead(),
                new ItemStack(Items.PHANTOM_MEMBRANE, 2),
                new ItemStack(Items.SPONGE, 2)));
        assertFalse(RawWildMembraneRecipe.matches(in), "a plain player head must be rejected");
    }

    @Test
    void rawRecipeRejectsWrongCountsAndExtras() {
        assertFalse(RawWildMembraneRecipe.matches(grid(List.of(
                WildEssence.create(),
                new ItemStack(Items.PHANTOM_MEMBRANE), // only 1 membrane
                new ItemStack(Items.SPONGE, 2)))), "wrong membrane count rejected");
        assertFalse(RawWildMembraneRecipe.matches(grid(List.of(
                WildEssence.create(),
                new ItemStack(Items.PHANTOM_MEMBRANE, 2),
                new ItemStack(Items.SPONGE, 2),
                new ItemStack(Items.DIRT)))), "a foreign item rejected");
        assertFalse(RawWildMembraneRecipe.matches(grid(List.of(
                WildEssence.create(), WildEssence.create(), // 2 essence
                new ItemStack(Items.PHANTOM_MEMBRANE, 2),
                new ItemStack(Items.SPONGE, 2)))), "too much essence rejected");
    }

    @Test
    void rawRecipeAssemblesRawMembrane() {
        ItemStack out = new RawWildMembraneRecipe().assemble(grid(List.of(
                WildEssence.create(),
                new ItemStack(Items.PHANTOM_MEMBRANE, 2),
                new ItemStack(Items.SPONGE, 2))));
        assertTrue(WildMembrane.isRaw(out), "assemble() yields a Raw Wild Membrane");
        assertFalse(WildMembrane.isCooked(out), "raw output is not the cooked membrane");
    }

    // --- SanctuaryCrystalRecipe ---

    private static List<ItemStack> crystalInputs() {
        return new java.util.ArrayList<>(List.of(
                WildMembrane.create(),               // cooked membrane
                new ItemStack(Items.CONDUIT),
                new ItemStack(Items.DRAGON_EGG),
                new ItemStack(Items.EXPERIENCE_BOTTLE, 3),
                new ItemStack(Items.OMINOUS_BOTTLE),
                new ItemStack(Items.RABBIT_FOOT),
                new ItemStack(Items.POISONOUS_POTATO)));
    }

    @Test
    void crystalRecipeMatchesFullNineItemSet() {
        assertTrue(SanctuaryCrystalRecipe.matches(grid(crystalInputs())),
                "the full 9-item set must match");
    }

    @Test
    void crystalRecipeRejectsPlainHeadInPlaceOfMembrane() {
        List<ItemStack> in = crystalInputs();
        in.set(0, plainHead()); // plain head instead of the cooked membrane
        assertFalse(SanctuaryCrystalRecipe.matches(grid(in)), "a plain player head must be rejected");
    }

    @Test
    void crystalRecipeRejectsRawMembraneInPlaceOfCooked() {
        List<ItemStack> in = crystalInputs();
        in.set(0, WildMembrane.createRaw()); // raw, not tempered
        assertFalse(SanctuaryCrystalRecipe.matches(grid(in)), "the RAW membrane must not satisfy it");
    }

    @Test
    void crystalRecipeRejectsWrongBottleCountsAndMissingReagents() {
        List<ItemStack> two = crystalInputs();
        two.set(3, new ItemStack(Items.EXPERIENCE_BOTTLE, 2)); // 2 bottles, not 3
        assertFalse(SanctuaryCrystalRecipe.matches(grid(two)), "wrong XP-bottle count rejected");

        List<ItemStack> noOminous = crystalInputs();
        noOminous.remove(4); // drop the ominous bottle
        assertFalse(SanctuaryCrystalRecipe.matches(grid(noOminous)), "missing ominous bottle rejected");

        List<ItemStack> noFoot = crystalInputs();
        noFoot.remove(5); // drop the rabbit's foot
        assertFalse(SanctuaryCrystalRecipe.matches(grid(noFoot)), "missing rabbit's foot rejected");

        List<ItemStack> noPotato = crystalInputs();
        noPotato.remove(6); // drop the poisonous potato
        assertFalse(SanctuaryCrystalRecipe.matches(grid(noPotato)), "missing poisonous potato rejected");
    }

    @Test
    void crystalRecipeAssemblesTheCrystal() {
        ItemStack out = new SanctuaryCrystalRecipe().assemble(grid(crystalInputs()));
        assertTrue(SanctuaryCrystal.isCrystal(out), "assemble() yields the Sanctuary Crystal");
    }

    // --- lava-cauldron cook predicate ---

    @Test
    void cookFiresOnlyForRawMembraneInLavaCauldron() {
        ItemStack raw = WildMembrane.createRaw();
        assertTrue(LavaCauldronCook.isLavaCauldronCook(raw, Blocks.LAVA_CAULDRON.defaultBlockState()),
                "raw membrane in a LAVA cauldron cooks");
        assertFalse(LavaCauldronCook.isLavaCauldronCook(raw, Blocks.WATER_CAULDRON.defaultBlockState()),
                "raw membrane in a WATER cauldron does NOT cook");
        assertFalse(LavaCauldronCook.isLavaCauldronCook(raw, Blocks.CAULDRON.defaultBlockState()),
                "raw membrane in an EMPTY cauldron does NOT cook");
        assertFalse(LavaCauldronCook.isLavaCauldronCook(
                        WildMembrane.create(), Blocks.LAVA_CAULDRON.defaultBlockState()),
                "an already-cooked membrane does NOT cook again");
        assertFalse(LavaCauldronCook.isLavaCauldronCook(
                        new ItemStack(Items.PLAYER_HEAD), Blocks.LAVA_CAULDRON.defaultBlockState()),
                "a plain head in a lava cauldron does NOT cook");
    }
}
