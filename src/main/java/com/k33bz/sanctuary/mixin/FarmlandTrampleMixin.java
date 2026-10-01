package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.k33bz.sanctuary.anchor.BaseRepair;

/**
 * System 12: farmland trampled back to dirt by anything other than the owner is journaled and
 * re-tilled later. Farmland drying out on its own (no entity) is NOT damage and is left alone,
 * otherwise an unwatered field would loop dry → re-till → dry forever on the owner's fuel.
 */
@Mixin(FarmlandBlock.class)
public class FarmlandTrampleMixin {

    // 26.3 generalised farmland: it reverts to its own base block (no longer always dirt), and the
    // static turnToDirt became the instance method turnToBaseBlock with the same parameters.
    @Shadow @Final private Block baseBlock;

    @Inject(method = "turnToBaseBlock", at = @At("HEAD"))
    private void sanctuary$journalTrample(Entity entity, BlockState state, Level level, BlockPos pos,
                                          CallbackInfo ci) {
        if (entity != null && level instanceof ServerLevel sl) {
            String becomes = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(baseBlock).toString();
            BaseRepair.journal(sl, pos, state, becomes, "trample:"
                    + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(),
                    entity);
        }
    }
}
