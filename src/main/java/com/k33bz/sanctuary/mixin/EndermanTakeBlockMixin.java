package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

/**
 * Endermen clone, they don't steal. The take-block goal normally removes the block from the
 * world before the enderman carries it off; with the toggle on, the removal is skipped while
 * the carry still happens — the enderman wanders away with a copy and the world keeps its
 * grass. (It may plant the clone somewhere later; a stray mundane block is the worst case.)
 */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")
public class EndermanTakeBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean sanctuary$cloneDontSteal(Level level, BlockPos pos, boolean moving) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg != null && cfg.endermanCloneNotSteal) {
            return true; // pretend the removal succeeded; the world keeps the block
        }
        return level.removeBlock(pos, moving);
    }
}
