package com.k33bz.sanctuary.mixin;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import com.k33bz.sanctuary.anchor.AnchorInteraction;
import com.k33bz.sanctuary.anchor.AnchorState;
import com.k33bz.sanctuary.anchor.SanctuaryCrystal;
import com.k33bz.sanctuary.siege.DoorRegistry;

/**
 * Placement hooks: a placed {@link SanctuaryCrystal} head forms a sanctuary anchor
 * (permission-gated via {@code sanctuary.anchor.create} — LuckPerms can restrict it), and
 * player-placed wooden doors are recorded for the frame-smash siege mechanic.
 */
@Mixin(BlockItem.class)
public class BlockItemPlaceMixin {

    @Inject(
            method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sanctuary$gateCrystalPlacement(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(context.getPlayer() instanceof ServerPlayer player)
                || !SanctuaryCrystal.isCrystal(context.getItemInHand())) {
            return;
        }
        if (Sanctuary.CONFIG == null || !Sanctuary.CONFIG.isScalingDimension(level)) {
            player.sendOverlayMessage(Component.literal("Sanctuary anchors can only be formed in the Overworld."));
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if (!Permissions.check(player, "sanctuary.anchor.create", true)) {
            player.sendOverlayMessage(Component.literal("You don't have permission to raise a sanctuary."));
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(
            method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN")
    )
    private void sanctuary$afterPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
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

        // A placed Sanctuary Crystal forms an anchor. Identify by the placed skull's profile —
        // the hand stack may already be consumed (count 0) by the time we're called.
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SkullBlockEntity skull
                && SanctuaryCrystal.isCrystal(skull.getOwnerProfile())) {
            ServerPlayer placer = (ServerPlayer) context.getPlayer();
            // Admins raise eternal sanctuaries; everyone else's burn fuel from a starting charge.
            boolean exempt = placer.isCreative()
                    || Permissions.check(placer, "sanctuary.anchor.admin", 2);
            long expiry = exempt || !Sanctuary.CONFIG.anchorUpkeepEnabled ? -1L
                    : level.getGameTime() + (long) (Sanctuary.CONFIG.anchorStartHours * 72000.0);
            AnchorState.get().ensureRegistered(pos, expiry,
                    placer.getGameProfile().name(), placer.getUUID().toString());
            AnchorInteraction.playConversionEffect(level.getServer(), pos);
            AnchorInteraction.spawnAnchorDisplays(level.getServer(), pos);
            return;
        }

        // Player-placed wooden doors feed the frame-smash siege mechanic.
        if (level.getBlockState(pos).is(BlockTags.WOODEN_DOORS)) {
            DoorRegistry.get().record(level, pos);
        }
    }
}
