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
 *
 * <p>System 12: with the toggle OFF, a real theft inside an active sanctuary is journaled just
 * before the removal, so the sanctuary regrows the block later (endermen can only lift cheap
 * "holdable" blocks, and each theft is logged).
 */
// 26.3 renamed the outer class EnderMan -> Enderman.
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public class EndermanTakeBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean sanctuary$cloneDontSteal(Level level, BlockPos pos, boolean moving) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg != null && cfg.endermanCloneNotSteal) {
            return true; // pretend the removal succeeded; the world keeps the block
        }
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            com.k33bz.sanctuary.anchor.BaseRepair.journal(sl, pos, level.getBlockState(pos),
                    "minecraft:air", "mob:enderman", null);
        }
        return level.removeBlock(pos, moving);
    }
}
