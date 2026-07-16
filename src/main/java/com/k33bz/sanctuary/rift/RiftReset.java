package com.k33bz.sanctuary.rift;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.nbt.CompoundTag;
import net.fabricmc.loader.api.FabricLoader;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Phase-2 weekly reset for {@code sanctuary:resource_world}: on a schedule (or manual trigger) it
 * evacuates every player from the gathering dimension, then REGENERATES every chunk EXCEPT a small
 * preserved pad around each rift, by clearing the chunk's block/entities/POI region data through the
 * live storage layer ({@link SimpleRegionStorage#write}(pos, null) — never by deleting {@code .mca}
 * files, which would strand the cached region handle). It runs as a tick-driven state machine so the
 * work is bounded per tick and every intermediate state is crash-safe.
 *
 * <p>Safety spine (non-negotiable ordering): unload the whole dimension BEFORE touching disk; freeze
 * saves ({@code noSave}) for the window; drain dirty chunks to disk (save + synchronize) BEFORE
 * clearing; clear all three storages per chunk (block-only would orphan entities/POI); re-verify the
 * dimension is still fully unloaded on every CLEAR tick; synchronize AFTER the clear loop. A chunk is
 * only ever cleared while proven unloaded with saves frozen, so an abort or crash at any point leaves
 * a mix of fresh + old chunks — never a corrupt one. Off by default ({@code riftResetEnabled=false}).
 */
public final class RiftReset {
    private RiftReset() {
    }

    private enum State { IDLE, WARN, EVACUATE, UNLOAD, FLUSH_PRE, ENUMERATE, CLEAR, FLUSH_POST }

    private static State state = State.IDLE;
    private static boolean dryRun = false;
    private static boolean manualPending = false;
    private static boolean cancelPending = false;
    private static boolean stateFirstTick = false; // set by enter(); consumed once by the state's handler
    private static long startTick = 0;
    private static long stateEnteredTick = 0;
    private static final List<Long> savedForced = new ArrayList<>(); // forceloads dropped in UNLOAD, restored at end
    private static long warnTicks = 0;
    private static long lastWarnBucket = -1;
    private static long clearedCount = 0;
    private static int regionsScanned = 0;

    private static final Set<Long> preserved = new HashSet<>();
    private static final Set<Long> toClear = new HashSet<>();
    private static final ArrayDeque<Long> workQueue = new ArrayDeque<>();

    private static List<Path> regionFiles = null;
    private static int regionFileIdx = 0;

    // Resolved once per run; held across ticks (only one run at a time).
    private static ServerLevel rw = null;
    private static ServerChunkCache scc = null;
    private static SimpleRegionStorage blockStore = null;
    private static SimpleRegionStorage entStore = null;
    private static SimpleRegionStorage poiStore = null;
    private static PoiManager poi = null;
    private static CompletableFuture<?>[] flushFutures = null;
    private static RiftSnapshot snapshot = null; // pristine snapshot to RESTORE from; null = clear+regen

    private static long tickCounter = 0;
    private static final long RETRY_BACKOFF_TICKS = 6000; // ~5 min: retry a transiently-aborted run soon

    /** True while a reset is anywhere in flight — rift travel into/out of resource_world is refused. */
    public static boolean travelLocked() {
        return state != State.IDLE;
    }

    // ---- command entry points (gamemaster-gated in SanctuaryCommands) ----

    public static boolean requestManual(boolean dry) {
        if (state != State.IDLE) {
            return false; // overlap guard
        }
        manualPending = true;
        dryRun = dry;
        return true;
    }

    public static void requestCancel() {
        if (state != State.IDLE) {
            cancelPending = true;
        }
    }

    public static String status(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        RiftStore store = RiftStore.get();
        long now = server.overworld().getGameTime();
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(state).append(dryRun ? " (dry-run)" : "");
        if (state != State.IDLE) {
            sb.append(", preserved=").append(preserved.size())
              .append(", toClear=").append(toClear.size())
              .append(", queue=").append(workQueue.size())
              .append(", cleared=").append(clearedCount);
        }
        if (cfg != null) {
            sb.append(", enabled=").append(cfg.riftResetEnabled);
            if (store.lastResetTick >= 0) {
                long next = store.lastResetTick + cfg.riftResetIntervalTicks - now;
                sb.append(", nextReset~").append(String.format(Locale.ROOT, "%.1fh", next / 20.0 / 3600.0));
            }
            ServerLevel r = resolveLevel(server, cfg);
            if (r != null && !r.getForceLoadedChunks().isEmpty()) {
                sb.append(", WARN ").append(r.getForceLoadedChunks().size())
                  .append(" force-loaded chunks in resource_world (may block UNLOAD)");
            }
        }
        return sb.toString();
    }

    // ---- the tick dispatcher (registered UNTHROTTLED in Sanctuary.onInitialize) ----

    public static void tick(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null) {
            return;
        }
        try {
            switch (state) {
                case IDLE -> tickIdle(server, cfg);
                case WARN -> tickWarn(server, cfg);
                case EVACUATE -> tickEvacuate(server, cfg);
                case UNLOAD -> tickUnload(server, cfg);
                case FLUSH_PRE -> tickFlushPre(server, cfg);
                case ENUMERATE -> tickEnumerate(server, cfg);
                case CLEAR -> tickClear(server, cfg);
                case FLUSH_POST -> tickFlushPost(server, cfg);
            }
        } catch (Throwable t) {
            Sanctuary.LOGGER.error("[sanctuary] rift reset crashed in {} — aborting", state, t);
            abort(server, "internal error: " + t);
        }
    }

    private static void tickIdle(MinecraftServer server, SanctuaryConfig cfg) {
        cancelPending = false;
        if ((++tickCounter % 20) != 0 && !manualPending) {
            return;
        }
        RiftStore store = RiftStore.get();
        long now = server.overworld().getGameTime();
        boolean manual = manualPending;
        boolean due = cfg.riftResetEnabled && store.lastResetTick >= 0
                && now >= store.lastResetTick + (long) cfg.riftResetIntervalTicks;
        if (!manual && !due) {
            return;
        }
        manualPending = false;
        // Resolve the dimension + storages up front; fail closed if anything is missing.
        rw = resolveLevel(server, cfg);
        if (rw == null || !resolveStores()) {
            Sanctuary.LOGGER.warn("[sanctuary] rift reset: resource_world/storages unavailable — skipping");
            teardown();
            // push the schedule forward so we don't spin on a missing dimension
            if (!manual) {
                store.lastResetTick = now;
                store.save();
            }
            return;
        }
        preserved.clear();
        computePreserved(cfg, store, preserved);
        // RESTORE mode: open the pristine snapshot now (its own region handles; read-only; closed in
        // teardown). A missing snapshot leaves it null -> CLEAR falls back to clear+regen per chunk.
        if (snapshot != null) {
            snapshot.close();
            snapshot = null;
        }
        if (cfg.riftResetRestoreFromSnapshot && !dryRun) {
            snapshot = RiftSnapshot.openIfPresent(snapshotDir(cfg), rw.dimension());
        }
        toClear.clear();
        workQueue.clear();
        regionFiles = null;
        regionFileIdx = 0;
        clearedCount = 0;
        regionsScanned = 0;
        startTick = now;
        warnTicks = manual ? 0L : (long) Math.max(0, cfg.riftResetWarnSeconds) * 20L;
        lastWarnBucket = -1;
        store.resetPhase = "RUNNING";
        store.save();
        Sanctuary.LOGGER.info("[sanctuary] rift reset starting ({}{}): preserving {} pad chunks",
                manual ? "manual" : "scheduled", dryRun ? ", dry-run" : "", preserved.size());
        if (dryRun) {
            enter(State.ENUMERATE, now); // preview only: reads region headers off disk, no evac/unload/writes
        } else if (warnTicks > 0) {
            enter(State.WARN, now);
        } else {
            enter(State.EVACUATE, now);
        }
    }

    private static void tickWarn(MinecraftServer server, SanctuaryConfig cfg) {
        long now = server.overworld().getGameTime();
        if (cancelPending) {
            RiftStore.get().lastResetTick = now; // advance the schedule so a scheduled run doesn't instantly re-arm
            broadcast(server, "The gathering-world reset was cancelled.");
            finishDone(server, cfg, true /*cancelled*/);
            return;
        }
        long remain = (startTick + warnTicks - now) / 20L; // seconds
        long bucket = remain <= 10 ? remain : (remain <= 60 ? 60 : (remain <= 300 ? 300 : -2));
        if (bucket != lastWarnBucket && remain >= 0) {
            lastWarnBucket = bucket;
            broadcast(server, String.format(Locale.ROOT,
                    "The gathering world (rift resource realm) resets in ~%ds — leave to keep nothing you left out there.",
                    Math.max(1, remain)));
        }
        if (now >= startTick + warnTicks) {
            enter(State.EVACUATE, now);
        }
    }

    private static void tickEvacuate(MinecraftServer server, SanctuaryConfig cfg) {
        long now = server.overworld().getGameTime();
        boolean liveRemain = false;
        for (ServerPlayer p : new ArrayList<>(rw.players())) {
            if (p.isDeadOrDying()) {
                continue; // on the respawn screen; it leaves on respawn — don't let it block the run
            }
            liveRemain = true;
            evacuate(server, cfg, p);
        }
        if (!liveRemain) {
            enter(State.UNLOAD, now); // dead players leave on respawn; UNLOAD's size()==0 is the real gate
        } else if (now - stateEnteredTick > cfg.riftResetUnloadTimeoutTicks) {
            abort(server, "could not evacuate all live players");
        }
    }

    private static void tickUnload(MinecraftServer server, SanctuaryConfig cfg) {
        long now = server.overworld().getGameTime();
        if (firstTick()) {
            // Drop forceloads so chunks can unload. Saves stay ENABLED here: MC won't evict a dirty
            // chunk while noSave is set, so freezing saves now would hang the unload forever. Dirty
            // chunks flush + evict normally; saves are frozen at FLUSH_PRE once the dim is empty.
            savedForced.clear();
            for (long key : rw.getForceLoadedChunks()) {
                savedForced.add(key);
            }
            for (long key : savedForced) {
                rw.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            }
        }
        if (scc.chunkMap.size() == 0 && !scc.hasActiveTickets() && rw.players().isEmpty()) {
            enter(State.FLUSH_PRE, now);
        } else if (now - stateEnteredTick > cfg.riftResetUnloadTimeoutTicks) {
            abort(server, "resource_world did not fully unload (size=" + scc.chunkMap.size()
                    + ", tickets=" + scc.hasActiveTickets() + ")");
        }
    }

    private static void tickFlushPre(MinecraftServer server, SanctuaryConfig cfg) {
        long now = server.overworld().getGameTime();
        if (firstTick()) {
            // The dimension is now fully unloaded. FREEZE saves for the clear window (safe now — nothing
            // is loaded to block), then drain any last dirty state + flush all three storages BEFORE clearing.
            rw.noSave = true;
            scc.save(true);
            poi.flushAll();
            flushFutures = new CompletableFuture<?>[]{
                    blockStore.synchronize(true), entStore.synchronize(true), poiStore.synchronize(true)};
        }
        if (allDone(flushFutures)) {
            enter(State.ENUMERATE, now);
        } else if (now - stateEnteredTick > cfg.riftResetFlushTimeoutTicks) {
            abort(server, "pre-clear flush timed out");
        }
    }

    private static void tickEnumerate(MinecraftServer server, SanctuaryConfig cfg) {
        long now = server.overworld().getGameTime();
        if (regionFiles == null) {
            regionFiles = listRegionFiles(server, cfg);
            regionFileIdx = 0;
        }
        int budget = Math.max(1, cfg.riftResetRegionsPerTick);
        while (budget-- > 0 && regionFileIdx < regionFiles.size()) {
            scanRegionHeader(regionFiles.get(regionFileIdx++));
            regionsScanned++;
        }
        if (regionFileIdx >= regionFiles.size()) {
            // Order chunks by region so the snapshot reader (RESTORE mode) touches one region .mca at a
            // time — its LRU handle cache then stays at ~1 open file instead of one per region.
            List<Long> ordered = new ArrayList<>(toClear);
            ordered.sort((a, b) -> {
                int c = Integer.compare(unpackX(a) >> 5, unpackX(b) >> 5);
                return c != 0 ? c : Integer.compare(unpackZ(a) >> 5, unpackZ(b) >> 5);
            });
            workQueue.addAll(ordered);
            if (dryRun) {
                Sanctuary.LOGGER.info("[sanctuary] rift reset DRY-RUN: would clear {} chunks across {} region files "
                        + "(preserving {} pad chunks). Writing nothing.", toClear.size(), regionsScanned, preserved.size());
                finishDone(server, cfg, true /*no lastReset bump for dry-run*/);
                return;
            }
            enter(toClear.isEmpty() ? State.FLUSH_POST : State.CLEAR, now);
        }
    }

    private static void tickClear(MinecraftServer server, SanctuaryConfig cfg) {
        long now = server.overworld().getGameTime();
        // A chunk must NEVER be cleared while any chunk in the dim is loaded (resurrection race).
        if (scc.chunkMap.size() != 0) {
            abort(server, "a chunk re-loaded during CLEAR (size=" + scc.chunkMap.size() + ")");
            return;
        }
        if (cancelPending) {
            Sanctuary.LOGGER.info("[sanctuary] rift reset cancelled mid-clear after {} chunks (partial is safe)", clearedCount);
            enter(State.FLUSH_POST, now);
            return;
        }
        int budget = Math.max(1, cfg.riftResetChunksPerTick);
        while (budget-- > 0 && !workQueue.isEmpty()) {
            long key = workQueue.poll();
            ChunkPos pos = new ChunkPos(unpackX(key), unpackZ(key));
            // RESTORE writes the pristine BLOCK chunk only; entities + POI are ALWAYS cleared to null and
            // rebuild from the restored blocks on load — this removes the duplicate-UUID / POI-desync
            // classes and means the snapshot need only contain region/. A null block (no snapshot open, or
            // this chunk absent / unreadable / newer than the server) = clear + regen from seed (fallback).
            blockStore.write(pos, snapshot != null ? snapshot.block(pos) : (CompoundTag) null);
            entStore.write(pos, (CompoundTag) null);
            poiStore.write(pos, (CompoundTag) null);
            clearedCount++;
        }
        if (workQueue.isEmpty()) {
            enter(State.FLUSH_POST, now);
        }
    }

    private static void tickFlushPost(MinecraftServer server, SanctuaryConfig cfg) {
        long now = server.overworld().getGameTime();
        if (firstTick()) {
            poi.flushAll();
            flushFutures = new CompletableFuture<?>[]{
                    blockStore.synchronize(true), entStore.synchronize(true), poiStore.synchronize(true)};
        }
        if (allDone(flushFutures)) {
            finishDone(server, cfg, false);
        } else if (now - stateEnteredTick > cfg.riftResetFlushTimeoutTicks) {
            // flushed data is already consistent per-chunk; treat as done-with-warning
            Sanctuary.LOGGER.warn("[sanctuary] rift reset: post-clear flush slow; completing anyway");
            finishDone(server, cfg, false);
        }
    }

    // ---- terminal transitions ----

    private static void finishDone(MinecraftServer server, SanctuaryConfig cfg, boolean noBump) {
        RiftStore store = RiftStore.get();
        long now = server.overworld().getGameTime();
        if (rw != null) {
            rw.noSave = false;
        }
        restoreForced();
        if (!noBump) {
            store.lastResetTick = now;
            store.resetEpoch++;
            pruneSeen(store);
            broadcast(server, "The gathering world has been remade — venture back through a rift for fresh ground.");
            Sanctuary.LOGGER.info("[sanctuary] rift reset DONE: cleared {} chunks, scanned {} region files, epoch {}",
                    clearedCount, regionsScanned, store.resetEpoch);
        }
        store.resetPhase = "IDLE";
        store.save();
        teardown();
    }

    private static void abort(MinecraftServer server, String reason) {
        RiftStore store = RiftStore.get();
        long now = server.overworld().getGameTime();
        if (rw != null) {
            rw.noSave = false;
        }
        restoreForced();
        // Retry in ~RETRY_BACKOFF_TICKS rather than skipping a full interval, so a transient blocker (an
        // AFK player dead on the respawn screen, a slow flush) self-heals; a persistently-broken run just
        // re-aborts every ~5 min (logged), never spins per-tick.
        if (!dryRun) {
            SanctuaryConfig cfg = Sanctuary.CONFIG;
            long interval = cfg != null ? cfg.riftResetIntervalTicks : 0L;
            // Clamp: lastResetTick < 0 is the reserved "never reset" sentinel that tickIdle's due-check and
            // status() both gate on. On a young world (game time < interval) the backoff arithmetic goes
            // negative, which would silently disarm the retry AND every future weekly reset until restart.
            store.lastResetTick = Math.max(0L, now - interval + Math.min(interval, RETRY_BACKOFF_TICKS));
        }
        store.resetPhase = "IDLE";
        store.save();
        Sanctuary.LOGGER.warn("[sanctuary] rift reset ABORTED ({}). World left intact; {} chunks had been cleared.",
                reason, clearedCount);
        teardown();
    }

    /** Re-apply the forceloads we dropped in UNLOAD so weekly resets don't destroy players' chunk-loaders. */
    private static void restoreForced() {
        if (rw != null) {
            for (long key : savedForced) {
                rw.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), true);
            }
        }
        savedForced.clear();
    }

    private static void teardown() {
        if (snapshot != null) {
            snapshot.close();
            snapshot = null;
        }
        savedForced.clear();
        stateFirstTick = false;
        state = State.IDLE;
        dryRun = false;
        cancelPending = false;
        preserved.clear();
        toClear.clear();
        workQueue.clear();
        regionFiles = null;
        flushFutures = null;
        rw = null;
        scc = null;
        blockStore = entStore = poiStore = null;
        poi = null;
    }

    private static void enter(State s, long now) {
        state = s;
        stateEnteredTick = now;
        stateFirstTick = true;
    }

    /** True exactly once per state entry — for one-shot init that must run on the state's FIRST handler
     *  tick (game-time equality can't detect that: a state is entered on tick T but first ticks on T+1). */
    private static boolean firstTick() {
        if (stateFirstTick) {
            stateFirstTick = false;
            return true;
        }
        return false;
    }

    // ---- storage resolution (via AccessWidener; fail-closed) ----

    private static boolean resolveStores() {
        try {
            scc = rw.getChunkSource();
            blockStore = scc.chunkMap; // ChunkMap IS-A SimpleRegionStorage
            poi = scc.getPoiManager();
            poiStore = ((SectionStorage<?, ?>) poi).simpleRegionStorage;
            EntityStorage es = (EntityStorage) rw.entityManager.permanentStorage;
            entStore = es.simpleRegionStorage;
            return blockStore != null && poiStore != null && entStore != null && poi != null;
        } catch (Throwable t) {
            Sanctuary.LOGGER.error("[sanctuary] rift reset: could not resolve region storages (mapping drift?)", t);
            return false;
        }
    }

    private static ServerLevel resolveLevel(MinecraftServer server, SanctuaryConfig cfg) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(cfg.riftDimension)));
        } catch (Exception e) {
            return null;
        }
    }

    /** The pristine-snapshot directory: {@code riftSnapshotDir} if absolute, else under the server game dir. */
    private static Path snapshotDir(SanctuaryConfig cfg) {
        String s = (cfg.riftSnapshotDir == null || cfg.riftSnapshotDir.isBlank())
                ? "sanctuary_rift_snapshot" : cfg.riftSnapshotDir;
        Path p = Path.of(s);
        return p.isAbsolute() ? p : FabricLoader.getInstance().getGameDir().resolve(p);
    }

    // ---- preserved-pad math (GLOBAL chunk coords only; our own packing) ----

    static void computePreserved(SanctuaryConfig cfg, RiftStore store, Set<Long> out) {
        for (RiftStore.Rift r : store.rifts) {
            if (!cfg.riftDimension.equals(r.dim)) {
                continue;
            }
            // A return portal's footprint spans x-1..x+2, so it straddles a chunk border whenever its anchor
            // sits near one. At pad=0 (which the config and command both permit) the neighbour chunk would be
            // restored from the snapshot — burying half the frame and refilling a membrane cell with stone —
            // while the rift record survives and keeps rendering/triggering a half-buried gateway. So a portal
            // rift always keeps at least its own 3x3.
            int pad = Math.max(r.portal ? 1 : 0, cfg.riftResetPadChunks);
            int rcx = r.x >> 4; // arithmetic shift = floorDiv(16), correct for negatives
            int rcz = r.z >> 4;
            for (int cx = rcx - pad; cx <= rcx + pad; cx++) {
                for (int cz = rcz - pad; cz <= rcz + pad; cz++) {
                    out.add(pack(cx, cz));
                }
            }
        }
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }

    // ---- region-file enumeration (read-only, handle-cache-safe; never MC's RegionFile) ----

    private static List<Path> listRegionFiles(MinecraftServer server, SanctuaryConfig cfg) {
        List<Path> out = new ArrayList<>();
        try {
            Identifier id = Identifier.parse(cfg.riftDimension);
            Path dimDir = server.getWorldPath(LevelResource.ROOT)
                    .resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
            for (String sub : new String[]{"region", "entities", "poi"}) {
                Path d = dimDir.resolve(sub);
                if (!Files.isDirectory(d)) {
                    continue;
                }
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "r.*.mca")) {
                    for (Path p : ds) {
                        out.add(p);
                    }
                }
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] rift reset: failed to list region files", e);
        }
        return out;
    }

    private static void scanRegionHeader(Path file) {
        String name = file.getFileName().toString(); // r.X.Z.mca
        String[] parts = name.split("\\.");
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(parts[1]);
            regionZ = Integer.parseInt(parts[2]);
        } catch (Exception e) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(4096); // 1024 big-endian ints (default order)
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) {
                    break; // genuine EOF (a short read alone must NOT abandon the whole region)
                }
            }
            if (buf.position() < 4096) {
                return; // truncated/empty region file — nothing to enumerate
            }
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] rift reset: skipping unreadable region {}", name);
            return;
        }
        buf.flip();
        for (int i = 0; i < 1024; i++) {
            if (buf.getInt(i * 4) == 0) {
                continue; // slot empty = chunk not present
            }
            int cx = regionX * 32 + (i & 31);
            int cz = regionZ * 32 + (i >> 5);
            long key = pack(cx, cz);
            if (!preserved.contains(key)) {
                toClear.add(key);
            }
        }
    }

    // ---- evacuation + offline rescue ----

    private static void evacuate(MinecraftServer server, SanctuaryConfig cfg, ServerPlayer p) {
        p.stopRiding();
        double[] dest = overworldDestFor(server, cfg, p);
        ServerLevel over = server.overworld();
        boolean ok = p.teleportTo(over, dest[0], dest[1], dest[2], Set.<Relative>of(), p.getYRot(), p.getXRot(), false);
        if (!ok) {
            Sanctuary.LOGGER.warn("[sanctuary] rift reset: evacuation teleport failed for {}", p.getScoreboardName());
        }
    }


    /** Overworld landing for a player being evacuated: nearest linked rift's overworld side, else spawn. */
    private static double[] overworldDestFor(MinecraftServer server, SanctuaryConfig cfg, ServerPlayer p) {
        RiftStore store = RiftStore.get();
        RiftStore.Rift best = null;
        double bestD = Double.MAX_VALUE;
        for (RiftStore.Rift r : store.rifts) {
            if (!cfg.riftDimension.equals(r.dim) || !r.linked) {
                continue;
            }
            double dx = r.x - p.getX();
            double dz = r.z - p.getZ();
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        if (best != null && "minecraft:overworld".equals(best.linkDim)) {
            return new double[]{best.linkX + 0.5, best.linkY, best.linkZ + 0.5};
        }
        // No linked rift (rare — every resource rift is normally linked): drop them on the overworld
        // surface at the same x,z (safe column, no dependency on the version-specific world-spawn API).
        ServerLevel over = server.overworld();
        int sx = p.getBlockX();
        int sz = p.getBlockZ();
        over.getChunkAt(new BlockPos(sx, over.getMinY() + 1, sz));
        int sy = over.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
        if (sy <= over.getMinY() + 1) {
            sy = 128; // void/empty-column guard
        }
        return new double[]{sx + 0.5, sy, sz + 0.5};
    }

    /**
     * Login-time rescue: a player who was offline in resource_world across one or more resets and whose
     * saved spot was regenerated (not a preserved pad) is snapped to the fresh surface, so they never
     * log in inside solid new terrain or over a void. Idempotent; survives restarts (persisted in RiftStore).
     */
    public static void onPlayerJoin(MinecraftServer server, ServerPlayer p) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !(p.level() instanceof ServerLevel level)) {
            return;
        }
        if (!cfg.riftDimension.equals(level.dimension().identifier().toString())) {
            return;
        }
        // A reset is in flight: never force-load a chunk into the dimension being drained (it would
        // abort the run + strand the player). Evacuate them to the overworld instead of an in-place rescue.
        if (travelLocked()) {
            double[] d = overworldDestFor(server, cfg, p);
            p.teleportTo(server.overworld(), d[0], d[1], d[2], Set.<Relative>of(), p.getYRot(), p.getXRot(), false);
            return;
        }
        RiftStore store = RiftStore.get();
        String uuid = p.getStringUUID();
        int seen = store.resetSeen.getOrDefault(uuid, -1);
        if (seen >= store.resetEpoch) {
            return; // already accounted for this epoch
        }
        Set<Long> pad = new HashSet<>();
        computePreserved(cfg, store, pad);
        long key = pack(p.getBlockX() >> 4, p.getBlockZ() >> 4);
        if (!pad.contains(key)) {
            level.getChunkAt(p.blockPosition()); // force-generate the (now fresh) column
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getBlockX(), p.getBlockZ());
            if (y <= level.getMinY() + 1) {
                y = 128; // void-biome / empty-column guard (same as Rifts.travel) — never strand in the void
            }
            p.teleportTo(level, p.getBlockX() + 0.5, y, p.getBlockZ() + 0.5,
                    Set.<Relative>of(), p.getYRot(), p.getXRot(), false);
        }
        store.resetSeen.put(uuid, store.resetEpoch);
        pruneSeen(store);
        store.save();
    }

    private static void pruneSeen(RiftStore store) {
        store.resetSeen.entrySet().removeIf(e -> e.getValue() < store.resetEpoch - 1);
    }

    /** Boot recovery: a non-IDLE persisted phase means a crash mid-reset. Partial clears are safe. */
    public static void onServerStarted(MinecraftServer server) {
        RiftStore store = RiftStore.get();
        long now = server.overworld().getGameTime();
        if (store.lastResetTick < 0) {
            store.lastResetTick = now; // anchor the first interval to first boot
            store.save();
        }
        if (!"IDLE".equals(store.resetPhase)) {
            Sanctuary.LOGGER.warn("[sanctuary] rift reset: found interrupted reset (phase={}); "
                    + "partial clears are safe, will re-run on next schedule", store.resetPhase);
            store.resetPhase = "IDLE";
            store.save();
        }
        state = State.IDLE;
    }

    // ---- helpers ----

    private static boolean allDone(CompletableFuture<?>[] fs) {
        if (fs == null) {
            return true;
        }
        for (CompletableFuture<?> f : fs) {
            if (f != null && !f.isDone()) {
                return false;
            }
        }
        return true;
    }

    private static void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(msg).withStyle(ChatFormatting.LIGHT_PURPLE), false);
    }
}
