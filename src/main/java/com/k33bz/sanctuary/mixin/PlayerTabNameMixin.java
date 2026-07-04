package com.k33bz.sanctuary.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.k33bz.sanctuary.AfkTracker;

/**
 * Prefixes idle players with [AFK] in the tab list. Team/name-color styling is untouched —
 * the prefix wraps whatever display name the player already has, so it composes with the
 * bundled "name colors" pack instead of fighting it like VT's afk pack does.
 */
@Mixin(ServerPlayer.class)
public class PlayerTabNameMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void sanctuary$afkPrefix(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!AfkTracker.isAfk(self)) {
            return;
        }
        Component base = cir.getReturnValue() != null ? cir.getReturnValue() : self.getDisplayName();
        cir.setReturnValue(Component.literal("[AFK] ").withStyle(ChatFormatting.GRAY).append(base));
    }
}
