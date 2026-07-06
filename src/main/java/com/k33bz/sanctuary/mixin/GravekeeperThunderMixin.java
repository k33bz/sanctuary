package com.k33bz.sanctuary.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.k33bz.sanctuary.grave.Gravekeeper;

/**
 * The Gravekeeper is a lightning-proof cleric. Vanilla {@code Villager.thunderHit} converts a
 * struck villager into a WITCH (it does NOT check invulnerability), which silently destroys a
 * keeper — it loses its {@code sanctuary_gravekeeper} tag and vanishes. This is the most likely
 * cause of the keeper going missing on a stormy server. Cancel the thunder hit entirely for a
 * keeper-tagged villager: no witch conversion, no fire, no damage. (Its own smite bolts are
 * visual-only and never call this, so this only guards against NATURAL weather lightning.)
 */
@Mixin(Villager.class)
public class GravekeeperThunderMixin {

    @Inject(method = "thunderHit", at = @At("HEAD"), cancellable = true)
    private void sanctuary$keeperIsLightningProof(ServerLevel level, LightningBolt bolt, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (self.entityTags().contains(Gravekeeper.KEEPER_TAG)) {
            ci.cancel(); // a keeper never converts, burns, or takes lightning damage
        }
    }
}
