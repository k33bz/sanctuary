package com.k33bz.sanctuary.rift;

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
import java.util.UUID;

/**
 * Persistent record of every open rift, in {@code config/sanctuary_rifts.json} (same Gson +
 * config-dir pattern as {@code AnchorState}/{@code PlayerProgress}). A rift is one air block a
 * player stands in; stepping onto it teleports to its linked rift in the paired dimension. An
 * overworld rift starts UNLINKED (freshly torn by a {@link RiftAnchor}); the resource-side return
 * rift is created already linked the first time someone crosses. Saves are synchronous — rifts are
 * created rarely (a player plants an anchor now and then), unlike the per-death grave store.
 */
public final class RiftStore {

    /** One end of a rift. Public mutable fields for trivial Gson (de)serialization. */
    public static final class Rift {
        public String id;
        public String dim;      // this end's dimension id, e.g. "minecraft:overworld"
        public int x;
        public int y;
        public int z;
        public boolean linked;  // whether the link* fields are set
        public String linkDim;  // paired end's dimension id
        public int linkX;
        public int linkY;
        public int linkZ;
        public String owner;    // creator display name (messaging / future per-player limits)
        public String ownerId;  // creator UUID string

        public boolean at(String d, int bx, int by, int bz) {
            return d.equals(dim) && bx == x && by == y && bz == z;
        }
    }

    public List<Rift> rifts = new ArrayList<>();

    // --- Phase-2 weekly-reset schedule state (see com.k33bz.sanctuary.rift.RiftReset) ---
    public long lastResetTick = -1;   // overworld game-time of the last completed reset (-1 = never)
    public int resetEpoch = 0;        // bumped once per completed reset; drives offline login-rescue
    public String resetPhase = "IDLE";// crash-resume marker; non-IDLE at boot = a reset was interrupted
    public java.util.Map<String, Integer> resetSeen = new java.util.HashMap<>(); // uuid -> epoch already handled

    private static RiftStore instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static RiftStore get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_rifts.json");
    }

    /** The rift whose stand-in block is exactly this position in this dimension, or null. */
    public Rift riftAt(String dim, BlockPos pos) {
        for (Rift r : rifts) {
            if (r.at(dim, pos.getX(), pos.getY(), pos.getZ())) {
                return r;
            }
        }
        return null;
    }

    /** True if a rift already stands in this block (so an anchor can't stack two on one spot). */
    public boolean exists(String dim, BlockPos pos) {
        return riftAt(dim, pos) != null;
    }

    /** Create and persist a new unlinked rift at this position. Returns the record. */
    public Rift create(String dim, BlockPos pos, String owner, String ownerId) {
        Rift r = new Rift();
        r.id = UUID.randomUUID().toString();
        r.dim = dim;
        r.x = pos.getX();
        r.y = pos.getY();
        r.z = pos.getZ();
        r.linked = false;
        r.owner = owner;
        r.ownerId = ownerId;
        rifts.add(r);
        save();
        return r;
    }

    /** Link two rifts to each other (both directions) and persist. */
    public void link(Rift a, Rift b) {
        a.linked = true;
        a.linkDim = b.dim;
        a.linkX = b.x;
        a.linkY = b.y;
        a.linkZ = b.z;
        b.linked = true;
        b.linkDim = a.dim;
        b.linkX = a.x;
        b.linkY = a.y;
        b.linkZ = a.z;
        save();
    }

    private static RiftStore load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                RiftStore s = GSON.fromJson(Files.readString(p), RiftStore.class);
                if (s != null) {
                    if (s.rifts == null) {
                        s.rifts = new ArrayList<>();
                    }
                    if (s.resetSeen == null) {
                        s.resetSeen = new java.util.HashMap<>();
                    }
                    if (s.resetPhase == null) {
                        s.resetPhase = "IDLE";
                    }
                    return s;
                }
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to load rifts; starting empty", e);
        }
        return new RiftStore();
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to save rifts", e);
        }
    }
}
