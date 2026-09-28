package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.k33bz.sanctuary.anchor.BaseRepair;

/**
 * System 12: blocks burned away by fire inside an active sanctuary are journaled and rebuilt.
 * {@code checkBurnOut} either leaves the block, replaces it with fire, or removes it; we note the
 * state on the way in and journal it on the way out if the fire took it. Burned blocks never drop
 * anything in vanilla, so a rebuild duplicates nothing.
 */
@Mixin(FireBlock.class)
public class FireBurnMixin {

    @Unique
    private static final ThreadLocal<BlockState> SANCTUARY$BEFORE = new ThreadLocal<>();

    @Inject(method = "checkBurnOut", at = @At("HEAD"))
    private void sanctuary$noteBefore(Level level, BlockPos pos, int chance, RandomSource random, int age,
                                      CallbackInfo ci) {
        SANCTUARY$BEFORE.set(level.getBlockState(pos));
    }

    @Inject(method = "checkBurnOut", at = @At("TAIL"))
    private void sanctuary$journalBurned(Level level, BlockPos pos, int chance, RandomSource random, int age,
                                         CallbackInfo ci) {
        BlockState before = SANCTUARY$BEFORE.get();
        SANCTUARY$BEFORE.remove();
        if (before == null || !(level instanceof ServerLevel sl)) {
            return;
        }
        BlockState after = level.getBlockState(pos);
        if (after != before && (after.isAir() || after.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)) {
            BaseRepair.journal(sl, pos, before, BaseRepair.blockId(after), "fire", null);
        }
    }
}
