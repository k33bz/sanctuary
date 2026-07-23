package com.k33bz.sanctuary;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Sleep-progress broadcast. Vanilla skips the night once enough OVERWORLD players are in bed
 * (governed by {@code players_sleeping_percentage}), but says nothing to the rest of the server —
 * so players don't know how many more sleepers are needed. This polls the overworld each tick and,
 * whenever the set of sleepers CHANGES, broadcasts "<names> are resting (N/R) — X more needed" to
 * the overworld players only (the only dimension whose sleep advances the day; the resource world
 * never darkens). Vanilla still performs the actual skip; this only informs. Config-gated.
 */
public final class SleepTracker {
    private SleepTracker() {
    }

    /** Sorted names of players sleeping at the last broadcast, for change-detection. */
    private static List<String> lastSleeping = List.of();

    public static void tick(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.sleepBroadcastEnabled) {
            return;
        }
        ServerLevel overworld = server.overworld();
        List<ServerPlayer> players = overworld.players();

        int active = 0;
        List<String> sleeping = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (p.isSpectator()) {
                continue; // vanilla's sleep math ignores spectators
            }
            active++;
            if (p.isSleeping()) {
                sleeping.add(p.getName().getString());
            }
        }
        sleeping.sort(String.CASE_INSENSITIVE_ORDER);

        if (sleeping.equals(lastSleeping)) {
            return; // nothing changed since the last broadcast — stay quiet
        }
        lastSleeping = sleeping;
        if (sleeping.isEmpty()) {
            return; // everyone woke (or the night passed) — nothing to announce
        }

        int pct = overworld.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        int required = SleepProgress.required(active, pct);
        Component msg = Component.literal(SleepProgress.message(sleeping, sleeping.size(), required))
                .withStyle(ChatFormatting.AQUA);
        for (ServerPlayer p : players) {
            p.sendSystemMessage(msg);
        }
    }
}
