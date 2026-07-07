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
                || !(context.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        // Yard-region place-deny (0.8.3.3): placing lava/TNT/cobble inside a consecrated yard is the
        // same grief vector as breaking its floor, so deny ALL block placement inside the protected
        // region. Region-based (no permission bypass), gated by graveyardYardProtect (same key as
        // the break-side protection). Runs BEFORE the crystal gate so it also blocks anchor crystals
        // being planted inside someone's cemetery.
        if (Sanctuary.CONFIG != null && Sanctuary.CONFIG.gravesEnabled
                && com.k33bz.sanctuary.grave.Graves.isProtectedYardRegion(
                        level.dimension().identifier().toString(), context.getClickedPos())) {
            deny(player, context, "You cannot build in a consecrated graveyard.");
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if (!SanctuaryCrystal.isCrystal(context.getItemInHand())) {
            return;
        }
        if (Sanctuary.CONFIG == null || !Sanctuary.CONFIG.isScalingDimension(level)) {
            deny(player, context, "Sanctuary anchors can only be formed in the Overworld.");
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if (!Permissions.check(player, "sanctuary.anchor.create", true)) {
            deny(player, context, "You don't have permission to raise a sanctuary.");
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        // Anchor cap: binding more sanctuaries takes Warden kills (creative bypasses).
        if (!player.isCreative()) {
            String uuid = player.getUUID().toString();
            int cap = com.k33bz.sanctuary.anchor.PlayerProgress.capOf(uuid, Sanctuary.CONFIG.anchorCapBase);
            int owned = AnchorState.get().countOwnedBy(uuid);
            if (owned >= cap) {
                int req = com.k33bz.sanctuary.anchor.PlayerProgress
                        .requiredTierForNextRaise(uuid, Sanctuary.CONFIG.anchorCapBase);
                String need = cap >= Sanctuary.CONFIG.anchorCapMax
                        ? "an admin's blessing"
                        : "slaying " + (req <= 0 ? "a Warden" : "a "
                                + com.k33bz.sanctuary.MobDifficulty.tierName(req) + "+ Warden");
                deny(player, context, String.format(java.util.Locale.ROOT,
                        "Anchor limit reached (%d/%d) — raising it takes %s", owned, cap, need));
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }
        }
        // Anchor spacing: survival placements keep their distance from existing sanctuaries
        // (config or placed). Creative bypasses, so admins can build monuments anywhere.
        if (!player.isCreative()) {
            BlockPos pos = context.getClickedPos();
            double px = pos.getX() + 0.5;
            double pz = pos.getZ() + 0.5;
            double nearest = AnchorState.get().nearestAnchorDistance(px, pz);
            if (Sanctuary.CONFIG.anchors != null) {
                for (com.k33bz.sanctuary.SanctuaryConfig.Anchor a : Sanctuary.CONFIG.anchors) {
                    nearest = Math.min(nearest, Math.hypot(px - a.x, pz - a.z));
                }
            }
            if (nearest < Sanctuary.CONFIG.anchorMinSpacing) {
                // Actionbar is a single line — keep it short: "distance / required".
                deny(player, context, String.format(java.util.Locale.ROOT,
                        "Too close to another sanctuary (%.0f / %.0f blocks)",
                        nearest, Sanctuary.CONFIG.anchorMinSpacing));
                cir.setReturnValue(InteractionResult.FAIL);
            }
        }
    }

    /**
     * Deny placement + resync the client: it has already predicted the place (ghost block,
     * decremented stack), and since the server changes nothing it would never correct itself —
     * the crystal LOOKS consumed until a relog. Push the inventory and block state back.
     */
    private static void deny(ServerPlayer player, BlockPlaceContext context, String message) {
        player.sendOverlayMessage(Component.literal(message));
        player.containerMenu.sendAllDataToRemote();
        if (context.getLevel() instanceof ServerLevel level) {
            BlockPos pos = context.getClickedPos();
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level, pos));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(level,
                    pos.relative(context.getClickedFace().getOpposite())));
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
            // Eternal sanctuaries are a deliberate admin act: creative-mode placement, or an
            // explicitly granted permission node. Plain op in survival gets a normal fueled
            // anchor (server owners play survival as ops — that shouldn't skip upkeep).
            boolean exempt = placer.isCreative()
                    || Permissions.check(placer, "sanctuary.anchor.admin", false);
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

        // A skull crowning an iron effigy inside a fenced pen consecrates a graveyard.
        var placed = level.getBlockState(pos);
        if (placed.is(net.minecraft.world.level.block.Blocks.SKELETON_SKULL)
                || placed.is(net.minecraft.world.level.block.Blocks.WITHER_SKELETON_SKULL)) {
            com.k33bz.sanctuary.grave.GraveyardRitual.tryForm(level, pos,
                    (ServerPlayer) context.getPlayer(), Sanctuary.CONFIG);
        }
        // (The old placed-altar Sanctuary ritual — beacon+conduit+dragon egg+sponges — was retired
        // in 0.8.0; the crystal is now made through the Wild Membrane crafting chain instead.)
    }
}
