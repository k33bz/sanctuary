package com.k33bz.sanctuary.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.rift.RiftWorldgen;

/**
 * Keeps structures out of the gathering world: no ruined portals, strongholds, villages, swamp huts,
 * trail ruins, trial chambers, ancient cities. Nothing generates unless {@code riftAllowedStructures} names it.
 *
 * <p>{@code tryGenerateStructure} is the right seam because it is handed BOTH the candidate structure and
 * the dimension the chunk belongs to, so the decision is exact and strictly per-dimension: the home world
 * generates exactly what it always did. It also sits below the structure-set machinery, so datapack
 * structures (Terralith and friends) are covered without naming them.
 *
 * <p>Returning false is the same answer vanilla gives when a structure loses its placement roll, so nothing
 * downstream has to be taught about the refusal. Chunks already generated keep what they have; the weekly
 * reset regenerates them clean.
 */
@Mixin(ChunkGenerator.class)
public class StructureSuppressMixin {

    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void sanctuary$suppressStructure(StructureSet.StructureSelectionEntry entry,
                                             StructureManager structureManager,
                                             RegistryAccess registryAccess,
                                             RandomState randomState,
                                             StructureTemplateManager templateManager,
                                             long seed,
                                             ChunkAccess chunk,
                                             ChunkPos chunkPos,
                                             SectionPos sectionPos,
                                             ResourceKey<Level> dimension,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (RiftWorldgen.suppressStructure(Sanctuary.CONFIG, dimension, entry.structure())) {
            cir.setReturnValue(false);
        }
    }
}
