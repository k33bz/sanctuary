package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.k33bz.sanctuary.anchor.BaseRepair;

import java.util.List;

/**
 * System 12: explosions inside an active sanctuary are journaled for rebuilding instead of
 * dropping loot. Hooks the HEAD of {@code interactWithBlocks} — deliberately NOT
 * {@code calculateExplodedPositions} (where {@link CreeperExplosionMixin} lives): Flan filters the
 * block list later, just before {@code hurtEntities}, so by the time {@code interactWithBlocks}
 * runs the list holds only blocks that are really going to break. Journaling earlier would
 * remove blocks Flan was about to protect.
 */
@Mixin(ServerExplosion.class)
public class ExplosionRepairMixin {

    @Shadow @Final private ServerLevel level;

    @ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
    private List<BlockPos> sanctuary$journalSanctuaryBlocks(List<BlockPos> blocks) {
        return BaseRepair.interceptExplosion(level, (Explosion) (Object) this, blocks);
    }
}
