package com.k33bz.sanctuary.anchor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import com.k33bz.sanctuary.Sanctuary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime registry of player-placed sanctuary anchors, persisted to a small JSON file so it survives
 * restarts independently of chunk loading (a real SavedData Codec would also work, but JSON keeps this
 * self-contained and matches the mod's config style). Feeds the System 4 / System 7 distance math.
 */
public class AnchorState {

    public static class PlacedAnchor {
        public double x;
        public double z;
        public double radius;

        public PlacedAnchor() {
        }

        public PlacedAnchor(double x, double z, double radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }
    }

    public List<PlacedAnchor> anchors = new ArrayList<>();
    public double defaultRadius = 128.0;

    private static AnchorState instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static AnchorState get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_anchors.json");
    }

    /** Register an anchor at this beacon position if not already present (idempotent). */
    public void ensureRegistered(BlockPos pos) {
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        for (PlacedAnchor a : anchors) {
            if (near(a, px, pz)) {
                return; // already registered
            }
        }
        anchors.add(new PlacedAnchor(px, pz, defaultRadius));
        save();
        Sanctuary.LOGGER.info("[sanctuary] Sanctuary anchor formed at {},{} (radius {})", pos.getX(), pos.getZ(), defaultRadius);
    }

    /** Remove any anchor at this position (idempotent). */
    public void ensureUnregistered(BlockPos pos) {
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        if (anchors.removeIf(a -> near(a, px, pz))) {
            save();
            Sanctuary.LOGGER.info("[sanctuary] Sanctuary anchor removed at {},{}", pos.getX(), pos.getZ());
        }
    }

    public boolean isAnchor(BlockPos pos) {
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        for (PlacedAnchor a : anchors) {
            if (near(a, px, pz)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(PlacedAnchor a, double px, double pz) {
        return Math.abs(a.x - px) < 0.51 && Math.abs(a.z - pz) < 0.51;
    }

    /** Blocks beyond the nearest placed anchor's safe radius; {@code Double.MAX_VALUE} if none placed. */
    public double blocksBeyondSafe(double x, double z) {
        double best = Double.MAX_VALUE;
        for (PlacedAnchor a : anchors) {
            double dx = x - a.x;
            double dz = z - a.z;
            double beyond = Math.sqrt(dx * dx + dz * dz) - a.radius;
            if (beyond < best) {
                best = beyond;
            }
        }
        return best;
    }

    private static AnchorState load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                AnchorState s = GSON.fromJson(Files.readString(p), AnchorState.class);
                if (s != null) {
                    if (s.anchors == null) {
                        s.anchors = new ArrayList<>();
                    }
                    return s;
                }
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to load anchors; starting empty", e);
        }
        return new AnchorState();
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to save anchors", e);
        }
    }
}
