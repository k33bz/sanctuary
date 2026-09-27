package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.k33bz.sanctuary.anchor.BaseRepair;

/**
 * System 12: farmland trampled back to dirt by anything other than the owner is journaled and
 * re-tilled later. Farmland drying out on its own (no entity) is NOT damage and is left alone,
 * otherwise an unwatered field would loop dry → re-till → dry forever on the owner's fuel.
 */
@Mixin(FarmBlock.class)
public class FarmlandTrampleMixin {

    @Inject(method = "turnToDirt", at = @At("HEAD"))
    private static void sanctuary$journalTrample(Entity entity, BlockState state, Level level, BlockPos pos,
                                                 CallbackInfo ci) {
        if (entity != null && level instanceof ServerLevel sl) {
            BaseRepair.journal(sl, pos, state, "minecraft:dirt", "trample:"
                    + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(),
                    entity);
        }
    }
}
