package com.k33bz.sanctuary.siege;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Restless — insomnia's underground collection agency. Phantoms punish the sleepless under
 * open sky; miners used to be exempt. No longer: once per night, a player who is underground
 * (no sky above) and carrying enough sleep debt is visited by Restless Creakings spawned in the
 * dark nearby — mobs that only move when unwatched, which is exactly what a tired miner's
 * flickering attention cannot afford. Count scales with nights of insomnia; strength scales
 * through the ordinary distance pipeline (they're Enemies, so wildness buffs apply on load).
 * They dissolve at dawn. Sleeping resets the debt, as it does for phantoms.
 */
public final class RestlessSpawner {
    private RestlessSpawner() {
    }

    /** Tag on spawned Restless, used by the dawn sweep. */
    private static final String RESTLESS_TAG = "sanctuary_restless";

    /** uuid -> the night index (gameTime / 24000) this player was last visited. */
    private static final Map<UUID, Long> VISITED = new ConcurrentHashMap<>();

    private static long lastDawnSweepDay = -1;

    /** Interval hook (per player). Spawns at most one visit per player per night. */
    public static void tick(ServerPlayer player, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        if (!ms.enabled || !cfg.restlessEnabled || !(player.level() instanceof ServerLevel level)
                || !cfg.isScalingDimension(level)) {
            return;
        }
        long time = level.getLevelData().getDayTime();
        long day = time / 24000L;
        long hour = time % 24000L;
        if (hour < 13000L || hour > 23000L) {
            sweepAtDawn(level, day, hour);
            return;
        }
        if (VISITED.getOrDefault(player.getUUID(), -1L) == day) {
            return;
        }
        // Underground: no sky directly above. The surface sleepless belong to the phantoms.
        BlockPos pos = player.blockPosition();
        if (level.canSeeSky(pos.above())) {
            return;
        }
        int insomniaDays = (int) (player.getStats().getValue(
                Stats.CUSTOM.get(Stats.TIME_SINCE_REST)) / 24000L);
        if (insomniaDays < cfg.restlessMinInsomniaDays) {
            return;
        }
        VISITED.put(player.getUUID(), day);
        int count = Math.min(cfg.restlessMaxCount,
                1 + (insomniaDays - cfg.restlessMinInsomniaDays));
        int spawned = 0;
        for (int attempt = 0; attempt < count * 8 && spawned < count; attempt++) {
            BlockPos spot = findSpot(level, pos, player);
            if (spot == null) {
                continue;
            }
            run(level, String.format(Locale.ROOT,
                    "summon minecraft:creaking %d %d %d {Tags:[\"%s\"],PersistenceRequired:1b,"
                            + "CustomName:{text:\"Restless Creaking\",color:\"dark_purple\"},"
                            + "CustomNameVisible:0b}",
                    spot.getX(), spot.getY(), spot.getZ(), RESTLESS_TAG));
            spawned++;
        }
        if (spawned > 0) {
            run(level, String.format(Locale.ROOT,
                    "playsound minecraft:entity.creaking.activate hostile %s %.1f %.1f %.1f 1.5 0.6",
                    player.getName().getString(), player.getX(), player.getY(), player.getZ()));
            player.sendOverlayMessage(net.minecraft.network.chat.Component
                    .literal("Something restless stirs in the dark. Sleep would have spared you.")
                    .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
        }
    }

    /** A dark air pocket with a floor, 6-14 blocks away, roughly the player's depth. */
    private static BlockPos findSpot(ServerLevel level, BlockPos around, ServerPlayer player) {
        var rng = player.getRandom();
        int dx = (rng.nextInt(9) + 6) * (rng.nextBoolean() ? 1 : -1);
        int dz = (rng.nextInt(9) + 6) * (rng.nextBoolean() ? 1 : -1);
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = around.offset(dx, dy, dz);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                    && !level.getBlockState(p.below()).isAir() && !level.canSeeSky(p)) {
                return p;
            }
        }
        return null;
    }

    /** At dawn, the Restless dissolve — once per level per day. */
    private static void sweepAtDawn(ServerLevel level, long day, long hour) {
        if (hour < 0L || hour > 2000L || day == lastDawnSweepDay) {
            return;
        }
        lastDawnSweepDay = day;
        run(level, "kill @e[type=minecraft:creaking,tag=" + RESTLESS_TAG + "]");
    }

    private static void run(ServerLevel level, String command) {
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
