package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.anchor.BaseRepair;

/**
 * System 12: a MOB breaking a block ({@code Level.destroyBlock} with a mob as the breaker —
 * withers, ravagers, the siege frame-smashers) inside an active sanctuary is journaled and removed
 * without drops, then rebuilt later. Player and breaker-less breaks are untouched.
 */
@Mixin(Level.class)
public class MobDestroyBlockMixin {

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void sanctuary$journalMobBreak(BlockPos pos, boolean drop, Entity breaker, int recursionLeft,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (breaker instanceof Mob && (Object) this instanceof ServerLevel level
                && BaseRepair.interceptMobBreak(level, pos, breaker)) {
            cir.setReturnValue(true);
        }
    }
}
