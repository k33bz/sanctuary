package com.k33bz.sanctuary.rift;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import com.k33bz.sanctuary.Sanctuary;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only accessor for the PRISTINE BLOCK data of a resource-world snapshot, used by {@link RiftReset}
 * to RESTORE (instead of clear+regen-from-seed) each chunk outside the preserved rift pads.
 *
 * <p><b>Block-only by design.</b> Only the {@code region/} (block) folder is read; the reset always clears
 * entities + POI to null, which rebuild from the restored blocks on load. That removes the duplicate-UUID
 * and POI-desync classes entirely and means the snapshot need only contain {@code region/}.
 *
 * <p>Opens its OWN {@link RegionFile} handles (separate from live storage), read synchronously only during
 * the reset's fully-unloaded + saves-frozen CLEAR window. The handle cache is LRU-bounded so a large world
 * can't exhaust file descriptors, and open failures are negative-cached so a torn/corrupt region file
 * doesn't thrash re-opens. A chunk that is absent, unreadable, or whose {@code DataVersion} is NEWER than
 * this server (the load path cannot down-fix it) returns {@code null} — and {@link RiftReset} then writes
 * null (= clear + regen) for it, so a partial/older/misversioned snapshot degrades safely.
 */
final class RiftSnapshot implements AutoCloseable {

    private static final int MAX_OPEN = 24; // bound open region handles (sorted CLEAR order keeps this ~1-2)

    private final Path regionDir;
    private final RegionStorageInfo info;
    private final int currentDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    // access-ordered LRU: packed regionXZ -> RegionFile. A cached null = "known absent / failed to open".
    private final LinkedHashMap<Long, RegionFile> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, RegionFile> eldest) {
            if (size() > MAX_OPEN) {
                closeQuietly(eldest.getValue());
                return true;
            }
            return false;
        }
    };

    private RiftSnapshot(Path root, ResourceKey<Level> dim) {
        this.regionDir = root.resolve("region");
        this.info = new RegionStorageInfo("snapshot", dim, "chunk");
    }

    /** Open the snapshot at {@code root} if it exists and has a {@code region/} folder; else {@code null}. */
    static RiftSnapshot openIfPresent(Path root, ResourceKey<Level> dim) {
        try {
            if (root != null && Files.isDirectory(root.resolve("region"))) {
                return new RiftSnapshot(root, dim);
            }
            Sanctuary.LOGGER.warn("[sanctuary] rift snapshot: none at {} (restore falls back to regen)", root);
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] rift snapshot: cannot open {}", root, e);
        }
        return null;
    }

    /** Pristine block-chunk NBT, or null if absent / unreadable / DataVersion newer than this server. */
    CompoundTag block(ChunkPos pos) {
        try {
            RegionFile rf = region(pos);
            if (rf == null || !rf.doesChunkExist(pos)) {
                return null;
            }
            try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                if (in == null) {
                    return null;
                }
                CompoundTag tag = NbtIo.read(in);
                int sv = tag.getIntOr("DataVersion", -1);
                if (sv < 0 || sv > currentDataVersion) {
                    Sanctuary.LOGGER.warn("[sanctuary] rift snapshot: chunk {} DataVersion {} vs current {} -- skipping (regen)",
                            pos, sv, currentDataVersion);
                    return null;
                }
                return tag;
            }
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] rift snapshot: read {} failed (regen fallback)", pos, e);
            return null;
        }
    }

    private RegionFile region(ChunkPos pos) {
        int rx = pos.getRegionX();
        int rz = pos.getRegionZ();
        long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
        if (cache.containsKey(key)) {
            return cache.get(key); // cached handle, or cached null (known-absent / failed-open)
        }
        Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
        RegionFile rf = null;
        if (Files.isRegularFile(file)) {
            try {
                rf = new RegionFile(info, file, regionDir, true);
            } catch (Exception e) {
                // negative-cache the failure so a torn/corrupt region isn't re-opened every chunk
                Sanctuary.LOGGER.warn("[sanctuary] rift snapshot: open r.{}.{} failed (regen fallback for its chunks)", rx, rz, e);
                rf = null;
            }
        }
        cache.put(key, rf);
        return rf;
    }

    @Override
    public void close() {
        for (RegionFile rf : cache.values()) {
            closeQuietly(rf);
        }
        cache.clear();
    }

    private static void closeQuietly(RegionFile rf) {
        if (rf != null) {
            try {
                rf.close();
            } catch (IOException ignored) {
                // best-effort close of read-only handles
            }
        }
    }
}
