package com.k33bz.sanctuary;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native AFK tagging — the mod-level answer to VT's "afk display" pack, which fights "name
 * colors" over scoreboard-team prefixes. This touches no teams: idle players get an [AFK]
 * prefix on their TAB-LIST display name (via the getTabListDisplayName mixin) plus a quiet
 * chat notice on state change, so it composes with any team color a player carries. Activity
 * = moving or turning; checked on the mod's player interval.
 */
public final class AfkTracker {
    private AfkTracker() {
    }

    private static final class State {
        double x, y, z;
        float yaw, pitch;
        long lastActiveTick;
        boolean afk;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /** Whether this player currently shows as AFK (read by the tab-name mixin). */
    public static boolean isAfk(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s != null && s.afk;
    }

    /** Interval check: any movement or camera turn counts as activity. */
    public static void tick(ServerPlayer player, SanctuaryConfig cfg) {
        if (!cfg.afkTagEnabled) {
            return;
        }
        long now = player.level().getGameTime();
        State s = STATES.computeIfAbsent(player.getUUID(), k -> {
            State fresh = new State();
            fresh.lastActiveTick = now;
            return fresh;
        });
        boolean moved = player.getX() != s.x || player.getY() != s.y || player.getZ() != s.z
                || player.getYRot() != s.yaw || player.getXRot() != s.pitch;
        s.x = player.getX();
        s.y = player.getY();
        s.z = player.getZ();
        s.yaw = player.getYRot();
        s.pitch = player.getXRot();
        if (moved) {
            s.lastActiveTick = now;
            if (s.afk) {
                s.afk = false;
                announce(player, false);
            }
            return;
        }
        if (!s.afk && now - s.lastActiveTick >= (long) (cfg.afkMinutes * 1200.0)) {
            s.afk = true;
            announce(player, true);
        }
    }

    public static void forget(UUID id) {
        STATES.remove(id);
    }

    private static void announce(ServerPlayer player, boolean nowAfk) {
        var server = player.level().getServer();
        server.getPlayerList().broadcastSystemMessage(Component.literal(
                        "* " + player.getName().getString() + (nowAfk ? " is now AFK" : " is back"))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        // Re-send this player's tab entry so the [AFK] prefix (from the mixin) shows/clears.
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                List.of(player)));
    }
}
