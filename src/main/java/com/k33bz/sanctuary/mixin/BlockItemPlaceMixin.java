package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.siege.DoorRegistry;

/**
 * Records PLAYER-PLACED wooden doors into the {@link DoorRegistry}, so frame smashing can
 * distinguish a player's front door from world-generated structures.
 */
@Mixin(BlockItem.class)
public class BlockItemPlaceMixin {

    @Inject(
            method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN")
    )
    private void sanctuary$recordPlacedDoor(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) {
            return;
        }
        if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer)) {
            return;
        }
        if (Sanctuary.CONFIG == null || !Sanctuary.CONFIG.isScalingDimension(level)) {
            return;
        }
        BlockPos pos = context.getClickedPos();
        if (level.getBlockState(pos).is(BlockTags.WOODEN_DOORS)) {
            DoorRegistry.get().record(level, pos);
        }
    }
}
