package com.k33bz.sanctuary.siege;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import com.k33bz.sanctuary.Sanctuary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Registry of PLAYER-PLACED wooden doors, per dimension. Frame smashing only threatens blocks
 * around these — never world-generated structures, whose doors are never recorded. Entries are
 * pruned lazily: if a queried position no longer holds a wooden door (broken, exploded, moved),
 * it is dropped.
 */
public final class DoorRegistry {
    private static DoorRegistry instance;

    /** dimension id -> packed {@link BlockPos#asLong()} positions of the (lower) door blocks. */
    public Map<String, Set<Long>> doors = new HashMap<>();

    public static DoorRegistry get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static String dimId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    public void record(ServerLevel level, BlockPos pos) {
        if (doors.computeIfAbsent(dimId(level), k -> new HashSet<>()).add(pos.asLong())) {
            save();
        }
    }

    /**
     * Nearest tracked door within {@code range} of {@code from}, or {@code null}. Positions whose
     * block is loaded but no longer a wooden door are pruned as a side effect.
     */
    public BlockPos nearestDoorWithin(ServerLevel level, BlockPos from, double range) {
        Set<Long> set = doors.get(dimId(level));
        if (set == null || set.isEmpty()) {
            return null;
        }
        BlockPos best = null;
        double bestSq = range * range;
        boolean dirty = false;
        for (Iterator<Long> it = set.iterator(); it.hasNext(); ) {
            BlockPos pos = BlockPos.of(it.next());
            if (pos.distSqr(from) > bestSq) {
                continue;
            }
            if (level.isLoaded(pos)) {
                if (!level.getBlockState(pos).is(BlockTags.WOODEN_DOORS)) {
                    it.remove(); // door is gone — forget it
                    dirty = true;
                    continue;
                }
            }
            best = pos;
            bestSq = pos.distSqr(from);
        }
        if (dirty) {
            save();
        }
        return best;
    }

    // --- persistence (same plain-Gson style as the anchor state) ---

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_doors.json");
    }

    private static DoorRegistry load() {
        try {
            if (Files.exists(path())) {
                Map<String, Set<Long>> data = GSON.fromJson(Files.readString(path()),
                        new TypeToken<Map<String, Set<Long>>>() {
                        }.getType());
                if (data != null) {
                    DoorRegistry reg = new DoorRegistry();
                    reg.doors = data;
                    return reg;
                }
            }
        } catch (IOException | RuntimeException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to load door registry; starting empty", e);
        }
        return new DoorRegistry();
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(doors));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to save door registry", e);
        }
    }
}
