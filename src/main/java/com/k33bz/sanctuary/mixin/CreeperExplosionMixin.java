package com.k33bz.sanctuary.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.siege.DoorRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Creeper mercy for the landscape, none for your front door. When enabled, a creeper's
 * explosion still hurts entities at full strength but the block list is filtered down to
 * player-placed doors and their threshold ({@code frameRadius}) blocks — the same registry the
 * frame-smash siege uses, so world-generated structures and open terrain never crater, while a
 * creeper detonating on a player's doorstep still breaches it. Other explosions (TNT, ghasts,
 * beds, crystals) are untouched.
 */
@Mixin(ServerExplosion.class)
public class CreeperExplosionMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
    private void sanctuary$creeperMercy(CallbackInfoReturnable<List<BlockPos>> cir) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.creeperTerrainProtection) {
            return;
        }
        Entity source = ((Explosion) this).getDirectSourceEntity();
        if (!(source instanceof net.minecraft.world.entity.monster.Creeper)) {
            return;
        }
        double reach = cfg.mobScaling.frameRadius + 1.5;
        List<BlockPos> kept = new ArrayList<>();
        for (BlockPos pos : cir.getReturnValue()) {
            if (DoorRegistry.get().nearestDoorWithin(level, pos, reach) != null) {
                kept.add(pos); // a player-placed door or its threshold: the breach stands
            }
        }
        cir.setReturnValue(kept);
    }
}
