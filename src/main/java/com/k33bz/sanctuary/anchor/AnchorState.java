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
        /** Block Y of the crystal (0 for legacy entries). */
        public int y;
        /** Display name + UUID of the placing player (null for legacy/server anchors). */
        public String owner;
        public String ownerId;
        /** This anchor's OWN unique id — full UUID targets exactly one sanctuary. */
        public String id;
        /** Player-given display name (null = unnamed). Shown on the label and in anchor list. */
        public String name;
        /**
         * Game time when the fuel runs out. {@code <= 0} = exempt/eternal (admin anchors and
         * grandfathered legacy entries). Dormant anchors keep their entry but grant no safety.
         */
        public long expiry;

        public PlacedAnchor() {
        }

        public PlacedAnchor(double x, double z, double radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }

        public boolean isExempt() {
            return expiry <= 0L;
        }

        public boolean isActive(long now) {
            return isExempt() || expiry > now;
        }

        /** Hours of fuel remaining ({@code Double.MAX_VALUE} if exempt). */
        public double hoursLeft(long now) {
            return isExempt() ? Double.MAX_VALUE : Math.max(0.0, (expiry - now) / 72000.0);
        }
    }

    /** Server game time, refreshed by the tick loop; used to judge which anchors are active. */
    public static volatile long NOW = 0L;

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

    /** Register an anchor at this position if not already present (idempotent). */
    public void ensureRegistered(BlockPos pos) {
        ensureRegistered(pos, 0L);
    }

    /** Register an anchor with an explicit fuel expiry ({@code <= 0} = exempt/eternal). */
    public void ensureRegistered(BlockPos pos, long expiry) {
        ensureRegistered(pos, expiry, null, null);
    }

    /** Register an anchor with expiry and owner identity. */
    public void ensureRegistered(BlockPos pos, long expiry, String owner, String ownerId) {
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        for (PlacedAnchor a : anchors) {
            if (near(a, px, pz)) {
                return; // already registered
            }
        }
        PlacedAnchor a = new PlacedAnchor(px, pz, defaultRadius);
        a.id = java.util.UUID.randomUUID().toString();
        a.y = pos.getY();
        a.expiry = expiry;
        a.owner = owner;
        a.ownerId = ownerId;
        anchors.add(a);
        save();
        Sanctuary.LOGGER.info("[sanctuary] Sanctuary anchor formed at {},{} (radius {}, {})",
                pos.getX(), pos.getZ(), defaultRadius, expiry <= 0 ? "eternal" : "fueled");
    }

    /**
     * Selector match: full UUID (36 chars) = exact anchor id or owner id; a string with {@code *}
     * = case-insensitive glob over anchor id, owner id, and owner name; otherwise = id prefix
     * (anchor or owner) or exact owner name.
     */
    public static boolean matches(PlacedAnchor a, String selector) {
        String sel = selector.toLowerCase(java.util.Locale.ROOT);
        String id = a.id == null ? "" : a.id.toLowerCase(java.util.Locale.ROOT);
        String ownerId = a.ownerId == null ? "" : a.ownerId.toLowerCase(java.util.Locale.ROOT);
        String owner = a.owner == null ? "" : a.owner.toLowerCase(java.util.Locale.ROOT);
        if (sel.indexOf('*') >= 0) {
            String regex = ("\\Q" + sel.replace("*", "\\E.*\\Q") + "\\E");
            return id.matches(regex) || ownerId.matches(regex) || owner.matches(regex);
        }
        if (sel.length() == 36) {
            return id.equals(sel) || ownerId.equals(sel);
        }
        return (!id.isEmpty() && id.startsWith(sel))
                || (!ownerId.isEmpty() && ownerId.startsWith(sel))
                || owner.equals(sel);
    }

    /** How many placed anchors this player owns. */
    public int countOwnedBy(String ownerId) {
        int n = 0;
        for (PlacedAnchor a : anchors) {
            if (ownerId.equals(a.ownerId)) {
                n++;
            }
        }
        return n;
    }

    /** Center distance to the nearest placed anchor ({@code Double.MAX_VALUE} if none). */
    public double nearestAnchorDistance(double x, double z) {
        double best = Double.MAX_VALUE;
        for (PlacedAnchor a : anchors) {
            double d = Math.hypot(x - a.x, z - a.z);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    /** The placed anchor at this block position, or {@code null}. */
    public PlacedAnchor anchorAt(BlockPos pos) {
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        for (PlacedAnchor a : anchors) {
            if (near(a, px, pz)) {
                return a;
            }
        }
        return null;
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

    /** Blocks beyond the nearest ACTIVE placed anchor's safe radius; {@code Double.MAX_VALUE} if none. */
    public double blocksBeyondSafe(double x, double z) {
        double best = Double.MAX_VALUE;
        long now = NOW;
        for (PlacedAnchor a : anchors) {
            if (!a.isActive(now)) {
                continue; // dormant: grants no safety until refueled
            }
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
                    // Backfill anchor ids on legacy entries so every anchor is targetable.
                    boolean dirty = false;
                    for (PlacedAnchor a : s.anchors) {
                        if (a.id == null) {
                            a.id = java.util.UUID.randomUUID().toString();
                            dirty = true;
                        }
                    }
                    if (dirty) {
                        s.save();
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
