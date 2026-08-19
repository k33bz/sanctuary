package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.rift.RiftWorldgen;

import java.util.Optional;

/**
 * No Nether gate ever lights in the gathering world. Build the frame all you like; it stays inert.
 *
 * <p>{@code findEmptyPortalShape} is the one method every ignition path funnels through: {@code
 * BaseFireBlock} is its only caller, and every way a fire block comes into existence (flint &amp; steel by
 * hand or from a dispenser, a fire charge, fire spreading off lava, a ghast fireball, lightning) ends up
 * there. Refusing the shape at the source means there is no ordering or edge case left for a player to
 * find, unlike gating the interactions one at a time.
 *
 * <p>This is prevention; {@link com.k33bz.sanctuary.rift.RiftSeal} still runs alongside it to explain the
 * refusal to the player, to block end portals, and to sweep up any portal block placed by other means.
 * Rift travel is a command teleport, not a portal block, so it is unaffected.
 */
@Mixin(PortalShape.class)
public class PortalIgnitionMixin {

    @Inject(method = "findEmptyPortalShape", at = @At("HEAD"), cancellable = true)
    private static void sanctuary$sealGatheringWorld(LevelAccessor level, BlockPos pos, Direction.Axis axis,
                                                     CallbackInfoReturnable<Optional<PortalShape>> cir) {
        if (RiftWorldgen.sealsIgnition(Sanctuary.CONFIG, level)) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
