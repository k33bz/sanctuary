package com.k33bz.sanctuary.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
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
 *
 * <p>The second injection is not an optimisation, it is a hang fix. Suppressing generation leaves
 * {@code findNearestMapStructure} scanning outward on the SERVER THREAD for something that can never be
 * there, which burns the whole search radius and trips the watchdog; {@code /locate}, an eye of ender, an
 * explorer map and the dolphin treasure goal all reach it. Answering "not found" up front is both correct
 * and instant.
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

    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void sanctuary$refuseStructureSearch(ServerLevel level, HolderSet<Structure> targets, BlockPos from,
                                                 int searchRadius, boolean skipKnownStructures,
                                                 CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        if (RiftWorldgen.suppressStructureSearch(Sanctuary.CONFIG, level, targets)) {
            cir.setReturnValue(null); // vanilla's own "nothing found" signal; every caller handles it.
        }
    }
}
