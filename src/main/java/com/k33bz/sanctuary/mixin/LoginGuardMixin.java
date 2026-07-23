package com.k33bz.sanctuary.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.guard.LoginGuardConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Reserved-name / allowed-IP guard. gmc101 runs {@code online-mode=false}, so a player's identity
 * is only its name — anyone connecting as a protected name (owner, bots) would be handed that
 * player's offline UUID, inventory and session. This vetoes such a login unless the source IP is in
 * an allowed network; unprotected names pass through untouched (the open playtest is unaffected).
 *
 * <p>Hooks vanilla's {@code PlayerList.canPlayerLogin}, which returns a non-null {@link Component}
 * to reject a login with that message — the same seam vanilla whitelist/ban checks use, so this
 * composes with them instead of racing them.
 */
@Mixin(PlayerList.class)
public class LoginGuardMixin {

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void sanctuary$guardReservedNames(SocketAddress socketAddress, GameProfile gameProfile,
                                              CallbackInfoReturnable<Component> cir) {
        LoginGuardConfig cfg = LoginGuardConfig.get();
        if (!cfg.enabled || gameProfile == null) {
            return;
        }
        String name = gameProfile.getName();
        if (!cfg.isProtected(name)) {
            return; // not a protected name — leave the open playtest alone
        }
        InetAddress addr = (socketAddress instanceof InetSocketAddress isa) ? isa.getAddress() : null;
        if (cfg.isAllowedAddress(addr)) {
            return; // protected name from an authorized network — the real bot / owner
        }
        Sanctuary.LOGGER.warn("login-guard: rejected reserved name '{}' from {}",
                name, addr != null ? addr.getHostAddress() : socketAddress);
        cir.setReturnValue(Component.literal(cfg.kickMessage));
    }
}
