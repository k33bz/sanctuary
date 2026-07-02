package com.k33bz.sanctuary.mixin;

import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamps every mob with its spawn source as an entity tag (e.g. {@code sanctuary_src_spawner}).
 * Invisible to players (tags have no client-side rendering); persists in NBT. Pure metadata —
 * consumed by the kill-metrics ledger so admins can see WHERE kills happen and what produced
 * the mobs (a spawner-heavy hotspot is a farm; that's fine — we just like knowing).
 */
@Mixin(Mob.class)
public class MobFinalizeSpawnMixin {

    @Inject(method = "finalizeSpawn", at = @At("RETURN"))
    private void sanctuary$stampSpawnSource(ServerLevelAccessor level, DifficultyInstance difficulty,
                                            EntitySpawnReason reason, SpawnGroupData data,
                                            CallbackInfoReturnable<SpawnGroupData> cir) {
        Mob self = (Mob) (Object) this;
        self.addTag("sanctuary_src_" + reason.name().toLowerCase(java.util.Locale.ROOT));
    }
}
