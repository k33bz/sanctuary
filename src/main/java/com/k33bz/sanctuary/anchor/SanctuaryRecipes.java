package com.k33bz.sanctuary.anchor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeSerializer;
import com.k33bz.sanctuary.Sanctuary;

/**
 * Registers the mod's two SPECIAL crafting-recipe serializers, the mechanism vanilla uses for its
 * own component-aware recipes (map cloning, firework/banner crafting). Each serializer carries a
 * fieldless {@link MapCodec#unit} / {@link StreamCodec#unit} codec pair keyed to a singleton recipe
 * instance — the recipe's data json ({@code data/sanctuary/recipe/*.json}) is just {@code {"type": ...}}
 * with no fields, because the result is computed in {@code assemble()} rather than declared in JSON.
 *
 * <p>Registered at mod init (server side); vanilla clients need no serializer of their own since the
 * server computes every result.
 */
public final class SanctuaryRecipes {
    private SanctuaryRecipes() {
    }

    public static void register() {
        RawWildMembraneRecipe.serializer = registerSerializer("raw_wild_membrane",
                new RecipeSerializer<>(
                        MapCodec.unit(RawWildMembraneRecipe.INSTANCE),
                        StreamCodec.unit(RawWildMembraneRecipe.INSTANCE)));
        SanctuaryCrystalRecipe.serializer = registerSerializer("sanctuary_crystal",
                new RecipeSerializer<>(
                        MapCodec.unit(SanctuaryCrystalRecipe.INSTANCE),
                        StreamCodec.unit(SanctuaryCrystalRecipe.INSTANCE)));
        com.k33bz.sanctuary.rift.RiftAnchorRecipe.serializer = registerSerializer("rift_anchor",
                new RecipeSerializer<>(
                        MapCodec.unit(com.k33bz.sanctuary.rift.RiftAnchorRecipe.INSTANCE),
                        StreamCodec.unit(com.k33bz.sanctuary.rift.RiftAnchorRecipe.INSTANCE)));
        Sanctuary.LOGGER.info("[sanctuary] Registered crafted-sanctuary recipe serializers");
    }

    private static <T extends RecipeSerializer<?>> T registerSerializer(String path, T serializer) {
        return Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
                Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, path), serializer);
    }
}
