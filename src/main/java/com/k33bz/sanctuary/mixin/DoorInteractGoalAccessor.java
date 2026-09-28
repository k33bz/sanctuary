package com.k33bz.sanctuary.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the goal's mob so {@link BreakDoorGoalMixin} can attribute a broken door. */
@Mixin(DoorInteractGoal.class)
public interface DoorInteractGoalAccessor {
    @Accessor("mob")
    Mob sanctuary$mob();
}
