package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.k33bz.sanctuary.anchor.BaseRepair;

/**
 * System 12: a zombie breaking a door removes it outright (vanilla never drops the door), so a
 * door broken inside an active sanctuary is journaled — both halves — just before the removal,
 * and rebuilt later. No drop exists, so there is nothing to duplicate.
 */
@Mixin(BreakDoorGoal.class)
public class BreakDoorGoalMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean sanctuary$journalDoor(Level level, BlockPos pos, boolean moving) {
        if (level instanceof ServerLevel sl) {
            BaseRepair.journalBeforeRemoval(sl, pos, "mob:door_breaker",
                    ((DoorInteractGoalAccessor) this).sanctuary$mob());
        }
        return level.removeBlock(pos, moving);
    }
}
