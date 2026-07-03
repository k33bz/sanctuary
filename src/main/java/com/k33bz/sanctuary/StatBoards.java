package com.k33bz.sanctuary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Stat boards — wall-mounted holographic leaderboards. Each board is a fixed-billboard
 * {@code text_display} facing where its creator stood, rendering the top entries of ANY
 * scoreboard objective — Sanctuary's own economy stats or anything the bundled Vanilla Tweaks
 * stat packs produce. Refreshed on an interval via {@code data merge entity}; boards persist in
 * {@code config/sanctuary_boards.json} and re-render after restarts. Vanilla clients see
 * everything: it's just a display entity.
 */
public final class StatBoards {
    private StatBoards() {
    }

    private static final String BOARD_TAG = "sanctuary_board";
    private static final int REFRESH_TICKS = 100; // 5s
    private static final int TOP_N = 10;

    /** Sanctuary's own objectives: {name, display}. Created if absent at server start. */
    private static final String[][] OBJECTIVES = {
            {"sanct_hatched", "Bloodline Hatches"},
            {"sanct_gen_best", "Best Bloodline Generation"},
            {"sanct_toll", "Respawn Toll (levels)"},
    };

    public static final class Board {
        String id;
        String dim;
        double x, y, z;
        float yaw;
        String objective;
        String title;
    }

    private static List<Board> boards;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static int tickCounter = 0;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_boards.json");
    }

    private static List<Board> boards() {
        if (boards == null) {
            try {
                if (Files.exists(path())) {
                    boards = GSON.fromJson(Files.readString(path()),
                            new TypeToken<List<Board>>() { }.getType());
                }
            } catch (Exception e) {
                Sanctuary.LOGGER.warn("[sanctuary] could not read stat boards", e);
            }
            if (boards == null) {
                boards = new ArrayList<>();
            }
        }
        return boards;
    }

    private static void save() {
        try {
            Files.writeString(path(), GSON.toJson(boards()));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] could not save stat boards", e);
        }
    }

    /** Create Sanctuary's objectives if missing (dummy criteria — the mod writes them). */
    public static void ensureObjectives(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        for (String[] def : OBJECTIVES) {
            if (sb.getObjective(def[0]) == null) {
                sb.addObjective(def[0], ObjectiveCriteria.DUMMY, Component.literal(def[1]),
                        ObjectiveCriteria.RenderType.INTEGER, true, null);
            }
        }
    }

    /** Add to a player's sanctuary stat (no-op if the objective is missing). */
    public static void addScore(ServerPlayer player, String objective, int amount) {
        Scoreboard sb = player.level().getServer().getScoreboard();
        Objective obj = sb.getObjective(objective);
        if (obj != null) {
            sb.getOrCreatePlayerScore(player, obj).add(amount);
        }
    }

    /** Raise a player's sanctuary stat to at least {@code value}. */
    public static void raiseScore(ServerPlayer player, String objective, int value) {
        Scoreboard sb = player.level().getServer().getScoreboard();
        Objective obj = sb.getObjective(objective);
        if (obj != null) {
            var score = sb.getOrCreatePlayerScore(player, obj);
            if (score.get() < value) {
                score.set(value);
            }
        }
    }

    /** Place a new board ~2.5 blocks ahead of the player, facing back at them. */
    public static int create(ServerPlayer player, String objective, String title) {
        Scoreboard sb = player.level().getServer().getScoreboard();
        if (sb.getObjective(objective) == null) {
            player.sendSystemMessage(Component.literal(
                    "No objective named '" + objective + "'. (Enable a VT stats pack or use a sanct_* one.)"));
            return 0;
        }
        var look = player.getLookAngle();
        double bx = player.getX() + look.x * 2.5;
        double by = player.getEyeY() + 0.4;
        double bz = player.getZ() + look.z * 2.5;
        float yaw = player.getYRot() + 180.0f;

        Board board = new Board();
        board.id = Long.toHexString(((ServerLevel) player.level()).getGameTime()) + "x" + boards().size();
        board.dim = player.level().dimension().identifier().toString();
        board.x = bx;
        board.y = by;
        board.z = bz;
        board.yaw = yaw;
        board.objective = objective;
        board.title = title == null || title.isBlank() ? objective : title;
        boards().add(board);
        save();

        run(player.level().getServer(), String.format(Locale.ROOT,
                "execute in %s run summon minecraft:text_display %.3f %.3f %.3f "
                        + "{Tags:[\"%s\",\"%s\"],Rotation:[%.1ff,0f],billboard:\"fixed\","
                        + "line_width:220,see_through:0b,text:{text:\"%s\",color:\"gold\",bold:1b}}",
                board.dim, bx, by, bz, BOARD_TAG, tagOf(board), yaw, escape(board.title)));
        render(player.level().getServer(), board);
        player.sendSystemMessage(Component.literal("Stat board placed (objective: " + objective + ")."));
        return 1;
    }

    /** Remove the nearest board within 8 blocks. */
    public static int remove(ServerPlayer player) {
        Board best = null;
        double bestSq = 8 * 8;
        String dim = player.level().dimension().identifier().toString();
        for (Board b : boards()) {
            if (!b.dim.equals(dim)) {
                continue;
            }
            double d = player.distanceToSqr(b.x, b.y, b.z);
            if (d < bestSq) {
                bestSq = d;
                best = b;
            }
        }
        if (best == null) {
            player.sendSystemMessage(Component.literal("No stat board within 8 blocks."));
            return 0;
        }
        run(player.level().getServer(), String.format(Locale.ROOT,
                "execute in %s run kill @e[type=minecraft:text_display,tag=%s]", best.dim, tagOf(best)));
        boards().remove(best);
        save();
        player.sendSystemMessage(Component.literal("Stat board removed."));
        return 1;
    }

    /** Interval refresh — called every server tick, renders every {@code REFRESH_TICKS}. */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < REFRESH_TICKS) {
            return;
        }
        tickCounter = 0;
        for (Board b : boards()) {
            render(server, b);
        }
    }

    private static void render(MinecraftServer server, Board board) {
        ServerLevel level = server.getLevel(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(board.dim)));
        if (level == null) {
            return;
        }
        Scoreboard sb = server.getScoreboard();
        Objective obj = sb.getObjective(board.objective);
        if (obj == null) {
            return;
        }
        List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(obj));
        entries.removeIf(e -> e.owner().startsWith("#"));
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
        StringBuilder body = new StringBuilder();
        int rank = 1;
        for (PlayerScoreEntry e : entries) {
            if (rank > TOP_N) {
                break;
            }
            body.append(String.format(Locale.ROOT, "\\n%d. %s  %d", rank++, escape(e.owner()), e.value()));
        }
        if (entries.isEmpty()) {
            body.append("\\n(no scores yet)");
        }
        String text = String.format(Locale.ROOT,
                "{text:\"%s\",color:\"gold\",bold:1b,extra:[{text:\"%s\",color:\"white\",bold:0b}]}",
                escape(board.title), body);
        run(server, String.format(Locale.ROOT,
                "execute in %s run data merge entity @e[type=minecraft:text_display,tag=%s,limit=1] {text:%s}",
                board.dim, tagOf(board), text));
    }

    private static String tagOf(Board board) {
        return BOARD_TAG + "_" + board.id;
    }

    private static String escape(String s) {
        return s.replaceAll("[^ -~]", "").replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
