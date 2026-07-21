package com.k33bz.sanctuary.grave;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.StatBoards;
import com.k33bz.sanctuary.SurvivalLogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native graves + sanctuary graveyards. On death the player's inventory is sealed into a grave
 * at the death site (items live in JSON, not in-world — despawn/hopper/regen proof). Headstone
 * = block-display stone slab + the owner's shrunk head affixed on top + a two-line label (the
 * owner's NAME + an age-fuzzy epitaph, see {@link GraveEpitaph}) + an interaction hitbox: pristine
 * stone bricks while loot remains, cracked once looted. Nature reclaims the plot over time —
 * podzol → grass → a flower ({@link GraveFlora}). The grave block + its ground are break-protected;
 * the flower on top is harvestable. Real-time lifecycle:
 * after {@code graveDriftHours} the grave drifts to the nearest sanctuary's graveyard (grid
 * plots around a command-defined center); after {@code gravePublicHours} it turns red and
 * anyone may loot it. Claiming from a graveyard costs a small XP fee; the Gravekeeper NPC
 * summons remote graves for a bigger one. Never enable the bundled VT graves pack alongside
 * this — both would capture the same inventory.
 */
public final class Graves {
    private Graves() {
    }

    public static final String GRAVE_TAG = "sanctuary_grave";

    public static final class Grave {
        public String id;
        public String owner;        // uuid
        public String ownerName;
        public String dim;
        public double x, y, z;
        public long diedAtMs;
        public boolean inGraveyard;
        public String graveyardAnchor; // anchor id hosting it, if drifted
        public boolean looted;
        /** Evicted from a full yard: no headstone, claimable only via the Gravekeeper. */
        public boolean heldByKeeper;
        public List<JsonElement> items = new ArrayList<>();
        /** displays spawned flag — false when the grave moved and old displays may linger. */
        public boolean displaysFresh;
        /** Death-cause category id ({@link GraveEpitaph.Cause}); null on legacy graves = generic. */
        public String deathCause;
        /** Killer mob name for a MOB death (e.g. "skeleton"); null otherwise. */
        public String killerName;
        /** In-game day the death happened (dayTime/24000); 0 on legacy graves. */
        public long deathDay;
        /** Rendered ground stage (0 podzol, 1 grass, 2 grass+flower); -1/null = not yet rendered.
         *  GRAVEYARD graves only — a wild grave keeps its original ground and never gets a stage. */
        public Integer floraStage;
        /** Rendered epitaph age-tier (0 exact,1 vague,2 generic,3 lost); -1/null = not yet rendered. */
        public Integer epitaphTier;
        /** The ground block that was under the plot BEFORE flora replaced it (graveyard graves only),
         *  so it can be restored on loot/remove/relocate/clear. Null = never replaced. */
        public String originalGround;
    }

    public static final class Yard {
        public String anchorId;   // "config" allowed for the spawn anchor
        public String dim;
        public int x, y, z;       // center
        public int radius;
        public String keeperUuid; // gravekeeper entity uuid, if spawned
        public String owner;      // consecrating player (ritual yards)
        /** Fence bounds from the consecration flood-fill; 0/0/0/0 for command yards. */
        public int bMinX, bMaxX, bMinZ, bMaxZ;
        /**
         * Auto/default HOLD-ONLY yard (0.8.2): raised at server start when no real graveyard exists.
         * No physical grave plots are laid in the world — it is purely a reclaim/hold hub. A manual
         * consecration upgrades it in place. Legacy yards default false.
         */
        public boolean auto;
    }

    public static final class Store {
        public List<Grave> graves = new ArrayList<>();
        public List<Yard> yards = new ArrayList<>();
    }

    private static Store store;
    // Compact (not pretty): smaller file + cheaper serialize. This is a runtime store, not a
    // hand-edited config; gson still reads any previously pretty-printed file transparently.
    private static final Gson GSON = new GsonBuilder().create();
    private static final Map<String, Long> CLAIM_COOLDOWN = new ConcurrentHashMap<>();

    // --- coalesced, off-thread persistence ----------------------------------------------------
    // A mutation only flags the store dirty (O(1)); the O(n) serialize runs at most once per
    // FLUSH_INTERVAL_TICKS on the server tick, and the disk write is handed to this daemon thread.
    // Death-spam can therefore no longer stall the tick with a per-death full-store write.
    private static final java.util.concurrent.ExecutorService SAVE_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sanctuary-graves-io");
                t.setDaemon(true);
                return t;
            });
    private static volatile boolean dirty = false;
    private static boolean flushedOnce = false;
    private static long lastFlushTick = 0;
    // ~1s: coalesces any death-spam burst into a single write while keeping the on-disk store
    // near-fresh for external readers (leaderboards etc.). Under a *sustained* death rate this caps
    // writes at ~1/s, which is trivial; bursts still collapse to one serialize per window.
    private static final int FLUSH_INTERVAL_TICKS = 20;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_graves.json");
    }

    public static Store store() {
        if (store == null) {
            try {
                if (Files.exists(path())) {
                    store = GSON.fromJson(Files.readString(path()), new TypeToken<Store>() { }.getType());
                }
            } catch (Exception e) {
                Sanctuary.LOGGER.warn("[sanctuary] could not read graves store", e);
            }
            if (store == null) {
                store = new Store();
            }
        }
        return store;
    }

    /**
     * Mark the store dirty. The disk write is coalesced and performed off the tick thread (see
     * {@link #flushIfDue}); a mutation therefore costs O(1) on the hot path instead of an O(n)
     * full-store serialize+write per call, so death-spam can no longer stall the server.
     */
    public static void save() {
        dirty = true;
    }

    /**
     * Called each server tick: at most once per {@link #FLUSH_INTERVAL_TICKS}, serialize the store
     * on the (calling) server thread — a consistent snapshot, since all mutations are on this same
     * thread — and hand the bytes to a background thread for an atomic write. No disk I/O on tick.
     */
    public static void flushIfDue(long gameTime) {
        if (!dirty) {
            return;
        }
        // First dirty flush is prompt; afterwards rate-limit. (Subtraction is overflow-safe because
        // both operands are non-negative once flushedOnce is set — never seed lastFlushTick negative.)
        if (flushedOnce && gameTime - lastFlushTick < FLUSH_INTERVAL_TICKS) {
            return;
        }
        flushedOnce = true;
        lastFlushTick = gameTime;
        dirty = false;
        final String json;
        try {
            json = GSON.toJson(store());
        } catch (Exception e) {
            dirty = true;
            Sanctuary.LOGGER.warn("[sanctuary] could not serialize graves", e);
            return;
        }
        SAVE_IO.execute(() -> writeAtomic(json));
    }

    /**
     * Synchronous flush for clean shutdown: serialize on the calling thread, then drain the IO
     * queue so the shutdown snapshot is the definitive last write (no stale async write clobbers it).
     */
    public static void saveNow() {
        dirty = false;
        final String json;
        try {
            json = GSON.toJson(store());
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] could not serialize graves on shutdown", e);
            return;
        }
        SAVE_IO.execute(() -> writeAtomic(json));
        SAVE_IO.shutdown();
        try {
            SAVE_IO.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Atomic write: temp file + ATOMIC_MOVE so a crash mid-write never leaves a truncated store. */
    private static void writeAtomic(String json) {
        try {
            Path p = path();
            Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] could not write graves store", e);
        }
    }

    // --- capture ---

    /**
     * Seal the dying player's inventory into a grave. Called from the death gate AFTER the
     * lethal-save decision, BEFORE vanilla scatters drops; the inventory is cleared here so
     * nothing hits the ground. No grave for empty inventories.
     */
    public static void capture(ServerPlayer player, SanctuaryConfig cfg,
                               net.minecraft.world.damagesource.DamageSource source) {
        var inv = player.getInventory();
        var ops = RegistryOps.create(JsonOps.INSTANCE, player.level().registryAccess());
        List<JsonElement> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(items::add);
        }
        if (items.isEmpty()) {
            return;
        }
        inv.clearContent();

        Grave grave = new Grave();
        grave.id = Long.toHexString(player.level().getGameTime()) + "g" + store().graves.size();
        grave.owner = player.getUUID().toString();
        grave.ownerName = player.getName().getString();
        grave.dim = player.level().dimension().identifier().toString();
        BlockPos pos = groundedPos((ServerLevel) player.level(), player.blockPosition());
        grave.x = pos.getX() + 0.5;
        grave.y = pos.getY();
        grave.z = pos.getZ() + 0.5;
        grave.diedAtMs = System.currentTimeMillis();
        grave.deathDay = player.level().getOverworldClockTime() / 24000L;
        categorizeCause(grave, source);
        grave.items = items;   // FIX: actually store the captured items (was orphaned -> empty graves / item loss)
        enforceGraveCaps(player.level().getServer(), cfg, grave);
        store().graves.add(grave);
        save();
        spawnDisplays((ServerLevel) player.level(), grave);
        StatBoards.addScore(player, "sanct_graves", 1);
        com.k33bz.sanctuary.metrics.GraveEventLog.record("created", grave.id, grave.ownerName,
                grave.dim, grave.x, grave.y, grave.z, grave.items.size(), grave.ownerName, false);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "Your belongings rest in a grave at %d %d %d.", pos.getX(), pos.getY(), pos.getZ()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /**
     * Bound the persisted grave store against death-spam abuse (2026-07 abuse audit). While the
     * owner is at/over {@code graveMaxPerOwner} simultaneous fresh WILD graves — or the whole store
     * is at {@code graveMaxTotal} — evict their oldest fresh wild grave. Only unclaimed, un-interred,
     * non-keeper-held graves are counted and evictable, so memorialised / graveyard graves are never
     * touched. Eviction removes the grave's displays, notifies the owner, and logs an "evicted"
     * metric. The generous default cap means legitimate players never hit it; only scripted death
     * spam or extreme hoarding triggers it. Without this, every death rewrites the entire grave
     * store on the game thread (O(store size)) and the store grows without bound.
     */
    private static void enforceGraveCaps(MinecraftServer server, SanctuaryConfig cfg, Grave incoming) {
        if (cfg == null) {
            return;
        }
        if (cfg.graveMaxPerOwner > 0) {
            while (countFreshWild(g -> java.util.Objects.equals(g.owner, incoming.owner)) >= cfg.graveMaxPerOwner) {
                if (!evictOldestFreshWild(server, g -> java.util.Objects.equals(g.owner, incoming.owner))) {
                    break;
                }
            }
        }
        if (cfg.graveMaxTotal > 0) {
            while (store().graves.size() >= cfg.graveMaxTotal) {
                if (!evictOldestFreshWild(server, g -> true)) {
                    break;
                }
            }
        }
    }

    /** Count fresh (unclaimed, un-interred, not keeper-held) wild graves matching {@code match}. */
    private static long countFreshWild(java.util.function.Predicate<Grave> match) {
        long n = 0;
        for (Grave g : store().graves) {
            if (!g.looted && !g.inGraveyard && !g.heldByKeeper && match.test(g)) {
                n++;
            }
        }
        return n;
    }

    /** Evict (remove + de-render) the oldest fresh wild grave matching {@code match}; false if none. */
    private static boolean evictOldestFreshWild(MinecraftServer server, java.util.function.Predicate<Grave> match) {
        Grave victim = null;
        for (Grave g : store().graves) {
            if (!g.looted && !g.inGraveyard && !g.heldByKeeper && match.test(g)
                    && (victim == null || g.diedAtMs < victim.diedAtMs)) {
                victim = g;
            }
        }
        if (victim == null) {
            return false;
        }
        ServerLevel level = server == null ? null : levelOf(server, victim.dim);
        if (level != null) {
            killDisplays(level, victim);
        }
        store().graves.remove(victim);
        if (server != null) {
            notifyOwner(server, victim,
                    "Your oldest unclaimed grave was reclaimed by nature to make room (grave limit reached).");
        }
        com.k33bz.sanctuary.metrics.GraveEventLog.record("evicted", victim.id, victim.ownerName,
                victim.dim, victim.x, victim.y, victim.z, victim.items.size(), victim.ownerName, false);
        return true;
    }

    /**
     * Map the killing {@link net.minecraft.world.damagesource.DamageSource} to an epitaph
     * {@link GraveEpitaph.Cause} + killer name, stored on the grave. A named-mob killer wins over
     * environmental tags (a skeleton that shot you from a ledge reads "slain by a skeleton", not
     * "fell"). Null source (e.g. /kill) → generic.
     */
    private static void categorizeCause(Grave grave, net.minecraft.world.damagesource.DamageSource source) {
        if (source == null) {
            grave.deathCause = GraveEpitaph.Cause.GENERIC.name();
            return;
        }
        net.minecraft.world.entity.Entity attacker = source.getEntity();
        if (attacker instanceof net.minecraft.world.entity.LivingEntity living
                && !(attacker instanceof ServerPlayer)) {
            grave.deathCause = GraveEpitaph.Cause.MOB.name();
            // The mob's type name (e.g. "Skeleton") lower-cased for the epitaph ("a skeleton").
            grave.killerName = living.getType().getDescription().getString().toLowerCase(Locale.ROOT);
            return;
        }
        GraveEpitaph.Cause cause;
        if (source.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
            cause = GraveEpitaph.Cause.VOID;
        } else if (source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)) {
            cause = GraveEpitaph.Cause.FALL;
        } else if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
                || source.is(net.minecraft.world.damagesource.DamageTypes.LAVA)) {
            cause = GraveEpitaph.Cause.BURN;
        } else if (source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING)) {
            cause = GraveEpitaph.Cause.DROWN;
        } else if (source.is(net.minecraft.world.damagesource.DamageTypes.WITHER)
                || source.is(net.minecraft.world.damagesource.DamageTypes.WITHER_SKULL)) {
            cause = GraveEpitaph.Cause.WITHER;
        } else {
            cause = GraveEpitaph.Cause.GENERIC;
        }
        grave.deathCause = cause.name();
    }

    /** Nudge a death position to something standable (void/lava deaths land on the surface). */
    private static BlockPos groundedPos(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinY() + 2) {
            return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        }
        for (int dy = 0; dy > -8; dy--) {
            BlockPos p = pos.offset(0, dy, 0);
            if (!level.getBlockState(p.below()).isAir()) {
                return p;
            }
        }
        return pos;
    }

    // --- headstone displays ---

    /** Spawn (or respawn) the headstone display cluster for a grave at its current position. */
    public static void spawnDisplays(ServerLevel level, Grave grave) {
        killDisplays(level, grave);
        String block = grave.looted ? "minecraft:cracked_stone_bricks" : "minecraft:stone_bricks";
        String tag = GRAVE_TAG + "_" + grave.id;
        // headstone slab
        run(level, String.format(Locale.ROOT,
                "summon minecraft:block_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],"
                        + "block_state:{Name:\"%s\"},transformation:{left_rotation:[0f,0f,0f,1f],"
                        + "right_rotation:[0f,0f,0f,1f],translation:[-0.35f,0f,-0.1f],scale:[0.7f,0.9f,0.2f]}}",
                grave.x, grave.y, grave.z, GRAVE_TAG, tag, block));
        // the owner's shrunk head, affixed on top
        run(level, String.format(Locale.ROOT,
                "summon minecraft:item_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],"
                        + "item:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":\"%s\"}},"
                        + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                        + "translation:[0f,1.05f,0f],scale:[0.45f,0.45f,0.45f]}}",
                grave.x, grave.y, grave.z, GRAVE_TAG, tag, displayName(grave.ownerName)));
        // label: line 1 = the NAME alone (no "Here lies"); line 2 = the age-fuzzy epitaph for any
        // unlooted grave (a public grave keeps its epitaph — its status shows in the RED color, not
        // by hiding the epitaph). A looted stone shows only its memorial line.
        boolean isPublic = isPublic(grave);
        String color = grave.looted ? "dark_gray" : isPublic ? "red" : "gray";
        String line2 = grave.looted ? "taken by the living" : epitaphOf(grave);
        run(level, String.format(Locale.ROOT,
                "summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],billboard:\"vertical\","
                        + "text:{text:\"%s\\n\",color:\"white\",extra:[{text:\"%s\",color:\"%s\"}]}}",
                grave.x, grave.y + 1.55, grave.z, GRAVE_TAG, tag, displayName(grave.ownerName),
                escape(line2), color));
        // interaction hitbox for claiming
        run(level, String.format(Locale.ROOT,
                "summon minecraft:interaction %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],width:0.9f,height:1.4f}",
                grave.x, grave.y, grave.z, GRAVE_TAG, tag));
        grave.displaysFresh = true;
    }

    public static void killDisplays(ServerLevel level, Grave grave) {
        run(level, "kill @e[tag=" + GRAVE_TAG + "_" + grave.id + "]");
    }

    /**
     * Nature reclaims the plot (part b): set the ground block under the grave by age (podzol → grass
     * → grass+flower) and re-render the epitaph (part a) as its age-tier changes. Only touches the
     * world when a stage actually changes (returns true then, so the caller saves). Skips
     * keeper-held graves (no world marker) and unloaded chunks.
     *
     * <p><b>Flora is GRAVEYARD-ONLY.</b> A wild grave ({@code inGraveyard == false}) keeps whatever
     * block was already underneath — its headstone just sits on the untouched original ground, no
     * podzol/grass/flower and no {@code floraStage}. The epitaph still ages for every grave.
     */
    static boolean renderFloraAndEpitaph(ServerLevel level, Grave grave, SanctuaryConfig cfg) {
        if (grave.heldByKeeper || grave.looted) {
            return false;
        }
        BlockPos base = BlockPos.containing(grave.x, grave.y, grave.z);
        if (!level.isLoaded(base)) {
            return false;
        }
        boolean changed = false;
        double ageDays = GraveLifecycle.elapsedDays(System.currentTimeMillis(), grave.diedAtMs);

        // Nature only reclaims consecrated ground. Wild graves keep their original surface.
        if (GraveFlora.appliesTo(grave.inGraveyard)) {
            // Ground/flora stage: 0 podzol, 1 grass, 2 grass+flower.
            int stage = GraveFlora.hasFlower(ageDays, cfg.graveFloraGrassDays, cfg.graveFloraFlowerDays) ? 2
                    : (ageDays >= cfg.graveFloraGrassDays ? 1 : 0);
            int prev = grave.floraStage == null ? -1 : grave.floraStage;
            if (prev != stage) {
                BlockPos ground = base.below();
                // Record the original ground once, before the first replacement, so it can be
                // restored on loot/remove/relocate/clear.
                if (grave.originalGround == null) {
                    grave.originalGround = blockIdAt(level, ground);
                }
                String groundBlock = GraveFlora.groundBlock(ageDays, cfg.graveFloraGrassDays, cfg.graveFloraFlowerDays);
                run(level, String.format(Locale.ROOT, "setblock %d %d %d %s replace",
                        ground.getX(), ground.getY(), ground.getZ(), groundBlock));
                if (stage == 2) {
                    // Bloom a flower on the plot surface (deterministic per grave id so a re-render is stable).
                    java.util.Random rng = new java.util.Random(grave.id.hashCode());
                    String flower = GraveFlora.flowerBlock(rng.nextDouble(), rng.nextDouble(), cfg.graveWitherRoseChance);
                    run(level, String.format(Locale.ROOT, "setblock %d %d %d %s replace",
                            base.getX(), base.getY(), base.getZ(), flower));
                } else if (prev == 2) {
                    // Regressed below flower tier (shouldn't happen forward, but keep the surface clean).
                    run(level, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air replace",
                            base.getX(), base.getY(), base.getZ()));
                }
                grave.floraStage = stage;
                changed = true;
            }
        }

        // Re-render the label when the epitaph age-tier OR the public status changes (public shows
        // as a red color, so it needs a re-render too). Pack both into the stored marker: +10 when
        // public, so a fresh grave turning public flips the marker and re-renders.
        int tier = epitaphTierOf(grave) + (isPublic(grave) ? 10 : 0);
        int prevTier = grave.epitaphTier == null ? -1 : grave.epitaphTier;
        if (prevTier != tier) {
            spawnDisplays(level, grave); // re-renders the label with the new fuzzy epitaph + color
            grave.epitaphTier = tier;
            changed = true;
        }
        return changed;
    }

    /** The block id string ("minecraft:dirt") at a position. */
    private static String blockIdAt(ServerLevel level, BlockPos pos) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /**
     * Restore a grave plot's ground + clear its flower (graveyard graves only) when the grave leaves
     * that plot — looted, removed, relocated, or cleared. Puts back {@code originalGround} if it was
     * recorded and clears any flower on the surface. Clears the flora markers. No-op for a grave
     * that never had flora (wild, or never aged to a stage).
     */
    static void restoreGround(ServerLevel level, Grave grave) {
        if (grave.floraStage == null) {
            return;
        }
        BlockPos base = BlockPos.containing(grave.x, grave.y, grave.z);
        if (level != null && level.isLoaded(base)) {
            // Clear a flower we bloomed on the surface.
            if (grave.floraStage >= 2) {
                run(level, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air replace",
                        base.getX(), base.getY(), base.getZ()));
            }
            // Put the original ground back, if we recorded it.
            if (grave.originalGround != null && !grave.originalGround.isBlank()) {
                BlockPos ground = base.below();
                run(level, String.format(Locale.ROOT, "setblock %d %d %d %s replace",
                        ground.getX(), ground.getY(), ground.getZ(), grave.originalGround));
            }
        }
        grave.floraStage = null;
        grave.originalGround = null;
    }

    /**
     * SERVER_STARTED migration (0.8.2.1): the 0.8.2 flora bug applied podzol/grass/flower to WILD
     * graves too. Revert it: any grave with {@code inGraveyard == false} that has a {@code floraStage}
     * gets its stage cleared and its ground restored — {@code originalGround} if recorded, else a
     * best-effort restore by sampling a solid, non-podzol/grass, non-grave-plot surface block among
     * the plot's horizontal neighbours. If nothing sensible is found, the flora is left in place and
     * the coordinate is logged for manual fixing.
     */
    public static void migrateWildFlora(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.gravesEnabled || store().graves.isEmpty()) {
            return;
        }
        boolean dirty = false;
        for (Grave g : store().graves) {
            if (g.inGraveyard || g.floraStage == null) {
                continue; // graveyard graves keep their flora; nothing to migrate otherwise
            }
            ServerLevel level = levelOf(server, g.dim);
            BlockPos base = BlockPos.containing(g.x, g.y, g.z);
            // The stale-podzol chunk is likely cold on boot; synchronously load it so the ground
            // restore actually lands (getChunkAt forces a full load without a lingering ticket).
            // Without this the podzol would just sit until a player next visits.
            if (level != null) {
                level.getChunkAt(base);
            }
            if (level == null || !level.isLoaded(base)) {
                // Still can't touch it; clear the marker so it isn't re-applied, and log for manual
                // review (the podzol lingers until someone visits).
                Sanctuary.LOGGER.warn("[sanctuary] wild-flora migration: grave {} at {} {} {} in "
                                + "unloaded chunk — clearing floraStage; ground may need manual fix",
                        g.id, (int) g.x, (int) g.y, (int) g.z);
                g.floraStage = null;
                g.originalGround = null;
                dirty = true;
                continue;
            }
            BlockPos ground = base.below();
            // Clear any flower we bloomed on the surface.
            if (g.floraStage >= 2) {
                run(level, String.format(Locale.ROOT, "setblock %d %d %d minecraft:air replace",
                        base.getX(), base.getY(), base.getZ()));
            }
            String restore = g.originalGround;
            if (restore == null || restore.isBlank()) {
                restore = sampleNeighbourGround(level, ground); // legacy graves recorded nothing
            }
            if (restore != null) {
                run(level, String.format(Locale.ROOT, "setblock %d %d %d %s replace",
                        ground.getX(), ground.getY(), ground.getZ(), restore));
                Sanctuary.LOGGER.info("[sanctuary] wild-flora migration: reverted grave {} ground at "
                        + "{} {} {} -> {}", g.id, ground.getX(), ground.getY(), ground.getZ(), restore);
            } else {
                Sanctuary.LOGGER.warn("[sanctuary] wild-flora migration: grave {} at {} {} {} — no "
                                + "sensible neighbour ground to restore; podzol/grass LEFT for manual fix",
                        g.id, ground.getX(), ground.getY(), ground.getZ());
            }
            g.floraStage = null;
            g.originalGround = null;
            dirty = true;
        }
        if (dirty) {
            save();
        }
    }

    /**
     * Best-effort original-ground guess for a legacy wild grave that never recorded it: the most
     * common solid, natural surface block among the plot's 8 horizontal neighbours, excluding podzol
     * / grass (the flora we're reverting), air, and non-solid stuff. Null if none qualifies.
     */
    private static String sampleNeighbourGround(ServerLevel level, BlockPos ground) {
        java.util.Map<String, Integer> tally = new java.util.HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos p = ground.offset(dx, 0, dz);
                if (!level.isLoaded(p)) {
                    continue;
                }
                var state = level.getBlockState(p);
                if (state.isAir()) {
                    continue;
                }
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(state.getBlock()).toString();
                if (id.equals("minecraft:podzol") || id.equals("minecraft:grass_block")) {
                    continue; // that's the flora we're reverting — don't restore to it
                }
                tally.merge(id, 1, Integer::sum);
            }
        }
        return tally.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    // --- lifecycle ---

    public static boolean isPublic(Grave grave) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        double publicHours = cfg == null ? 48 : cfg.gravePublicHours;
        return GraveLifecycle.isPublic(System.currentTimeMillis(), grave.diedAtMs, grave.looted, publicHours);
    }

    /** The current age-fuzzy epitaph line for a grave (part a). */
    static String epitaphOf(Grave grave) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        double exact = cfg == null ? 7 : cfg.epitaphExactDays;
        double vague = cfg == null ? 28 : cfg.epitaphVagueDays;
        double generic = cfg == null ? 90 : cfg.epitaphGenericDays;
        double ageDays = GraveLifecycle.elapsedDays(System.currentTimeMillis(), grave.diedAtMs);
        return GraveEpitaph.epitaph(GraveEpitaph.Cause.parse(grave.deathCause), grave.killerName,
                grave.deathDay, ageDays, exact, vague, generic);
    }

    /** Which epitaph age-tier a grave is in (0 exact,1 vague,2 generic,3 lost) — for re-render gating. */
    static int epitaphTierOf(Grave grave) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        double exact = cfg == null ? 7 : cfg.epitaphExactDays;
        double vague = cfg == null ? 28 : cfg.epitaphVagueDays;
        double generic = cfg == null ? 90 : cfg.epitaphGenericDays;
        double ageDays = GraveLifecycle.elapsedDays(System.currentTimeMillis(), grave.diedAtMs);
        if (ageDays >= generic) {
            return 3;
        }
        if (ageDays >= vague) {
            return 2;
        }
        if (ageDays >= exact) {
            return 1;
        }
        return 0;
    }

    /**
     * Escape a string for embedding inside an SNBT JSON-text component string literal. Robust to ANY
     * input, not just validated usernames: control characters (which would break the single-line
     * command or the SNBT string) are dropped, and backslash/quote are escaped.
     */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20) {
                continue;
            }
            if (c == '\\' || c == '"') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Escape a PLAYER-CONTROLLED name for a visible SNBT display label. Like {@link #escape} but also
     * strips legacy section-sign (\u00a7) formatting codes so a name cannot recolour / obfuscate the
     * label. Defense-in-depth: do NOT assume upstream username validation blocks these characters.
     */
    static String displayName(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '\u00a7') {
                continue;
            }
            if (c == '\\' || c == '"') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Real-time sweep: drift due graves into their nearest graveyard when chunks allow. */
    public static void sweep(MinecraftServer server, SanctuaryConfig cfg) {
        if (!cfg.gravesEnabled || store().graves.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean dirty = false;
        // Memorial decay: looted stones crumble after graveMemorialDecayDays; loot never decays.
        if (cfg.graveMemorialDecayDays > 0) {
            var it = store().graves.iterator();
            while (it.hasNext()) {
                Grave g = it.next();
                if (!GraveLifecycle.isMemorialDecayDue(now, g.diedAtMs, g.looted, g.inGraveyard,
                        cfg.graveMemorialDecayDays)) {
                    continue; // cemeteries keep their history; only wild litter decays past the threshold
                }
                ServerLevel gl = levelOf(server, g.dim);
                if (gl != null && gl.isLoaded(BlockPos.containing(g.x, g.y, g.z))) {
                    killDisplays(gl, g);
                    run(gl, String.format(java.util.Locale.ROOT,
                            "particle minecraft:ash %.1f %.1f %.1f 0.3 0.5 0.3 0.01 20", g.x, g.y + 0.5, g.z));
                    it.remove();
                    dirty = true;
                    com.k33bz.sanctuary.metrics.GraveEventLog.record("decayed", g.id, g.ownerName,
                            g.dim, g.x, g.y, g.z, 0, null, false);
                }
            }
        }
        for (Grave grave : store().graves) {
            if (GraveLifecycle.isDriftDue(now, grave.diedAtMs, grave.looted, grave.inGraveyard,
                    grave.heldByKeeper, cfg.graveDriftHours)) {
                if (moveToYard(server, grave, null)) {
                    dirty = true;
                    notifyOwner(server, grave, "Your remains were carried to the sanctuary graveyard.");
                    com.k33bz.sanctuary.metrics.GraveEventLog.record("drifted", grave.id,
                            grave.ownerName, grave.dim, grave.x, grave.y, grave.z,
                            grave.items.size(), null, false);
                }
            } else if (!grave.looted && !grave.heldByKeeper && !grave.displaysFresh) {
                ServerLevel level = levelOf(server, grave.dim);
                if (level != null && level.isLoaded(BlockPos.containing(grave.x, grave.y, grave.z))) {
                    spawnDisplays(level, grave);
                    dirty = true;
                }
            }
            // Nature reclaims the plot + the epitaph blurs with age (parts a & b).
            if (!grave.looted && !grave.heldByKeeper) {
                ServerLevel level = levelOf(server, grave.dim);
                if (level != null && renderFloraAndEpitaph(level, grave, cfg)) {
                    dirty = true;
                }
            }
        }
        if (dirty) {
            save();
        }
        // Coarse safety net: the keeper's slow patrol (0.8.4) already clamps it inside the fence every
        // tick, but this catches a keeper flung well OUTSIDE the bounds (piston, mob push) while its
        // yard chunk is loaded only here — snap it back to center; the per-tick drift+bob resume there.
        for (Yard yard : store().yards) {
            if (yard.bMaxX <= yard.bMinX) {
                continue;
            }
            ServerLevel level = levelOf(server, yard.dim);
            if (level == null || !level.isLoaded(new BlockPos(yard.x, yard.y, yard.z))) {
                continue;
            }
            for (var keeper : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                    new net.minecraft.world.phys.AABB(yard.bMinX - 12, yard.y - 12, yard.bMinZ - 12,
                            yard.bMaxX + 13, yard.y + 13, yard.bMaxZ + 13),
                    m -> m.entityTags().contains(Gravekeeper.KEEPER_TAG))) {
                if (keeper.getX() < yard.bMinX + 0.5 || keeper.getX() > yard.bMaxX + 0.5
                        || keeper.getZ() < yard.bMinZ + 0.5 || keeper.getZ() > yard.bMaxZ + 0.5) {
                    // Snap back to center at the hover base (the per-tick bob takes over the Y).
                    keeper.teleportTo(yard.x + 0.5, yard.y + KeeperHover.BASE_LIFT, yard.z + 0.5);
                }
            }
        }
    }

    /**
     * Re-lay every grave resting in {@code yard} into fresh plots within the (resized) bounds
     * (part d). Clears each grave's in-yard flag first so {@link #nextPlot} sees empty ground, then
     * re-places them in age order. Returns the number relaid.
     */
    public static int relayoutYard(MinecraftServer server, Yard yard) {
        ServerLevel level = levelOf(server, yard.dim);
        if (level == null) {
            return 0;
        }
        List<Grave> resting = new ArrayList<>();
        for (Grave g : store().graves) {
            if (g.inGraveyard && !g.heldByKeeper && Objects.equals(g.dim, yard.dim)
                    && yard.anchorId.equals(g.graveyardAnchor)) {
                resting.add(g);
            }
        }
        resting.sort(java.util.Comparator.comparingLong(g -> g.diedAtMs));
        // Free their old plots so nextPlot() lays them out from scratch in the new bounds —
        // restore each old plot's ground + clear its flower before it's vacated.
        for (Grave g : resting) {
            killDisplays(level, g);
            restoreGround(level, g); // clears floraStage + originalGround too
            g.inGraveyard = false;
        }
        int moved = 0;
        for (Grave g : resting) {
            BlockPos plot = nextPlot(level, yard);
            if (plot == null) {
                // Out of room — restore the flag so the grave isn't lost (shouldn't happen: the
                // resize is validated to contain them all).
                g.inGraveyard = true;
                continue;
            }
            g.x = plot.getX() + 0.5;
            g.y = plot.getY();
            g.z = plot.getZ() + 0.5;
            g.inGraveyard = true;
            g.graveyardAnchor = yard.anchorId;
            g.floraStage = null;  // re-render ground/flora at the new plot
            g.epitaphTier = null;
            spawnDisplays(level, g);
            moved++;
        }
        return moved;
    }

    /** Move a grave into a graveyard's next free plot. Chunk-lazy: false if not possible yet. */
    public static boolean moveToYard(MinecraftServer server, Grave grave, Yard preferred) {
        Yard yard = preferred != null ? preferred : nearestYard(grave);
        if (yard == null) {
            return false;
        }
        ServerLevel oldLevel = levelOf(server, grave.dim);
        ServerLevel yardLevel = levelOf(server, yard.dim);
        if (oldLevel == null || yardLevel == null
                || !oldLevel.isLoaded(BlockPos.containing(grave.x, grave.y, grave.z))) {
            return false;
        }
        // A HOLD-ONLY (auto/default) yard lays no physical plots: drift takes the grave straight
        // into the keeper's hold instead, reclaimable via the keeper dialog.
        if (yard.auto) {
            killDisplays(oldLevel, grave);
            grave.heldByKeeper = true;
            grave.inGraveyard = false;
            grave.graveyardAnchor = yard.anchorId;
            grave.floraStage = null;
            grave.epitaphTier = null;
            return true;
        }
        BlockPos plot = nextPlot(yardLevel, yard);
        if (plot == null && evictForSpace(server, yard)) {
            plot = nextPlot(yardLevel, yard);
        }
        if (plot == null || !yardLevel.isLoaded(plot)) {
            return false;
        }
        killDisplays(oldLevel, grave);
        grave.dim = yard.dim;
        grave.x = plot.getX() + 0.5;
        grave.y = plot.getY();
        grave.z = plot.getZ() + 0.5;
        grave.inGraveyard = true;
        grave.graveyardAnchor = yard.anchorId;
        spawnDisplays(yardLevel, grave);
        return true;
    }

    /**
     * Cemetery plots are 1x3: a headstone with two open blocks in front, stones shoulder to
     * shoulder along each row (the block-display slabs are 0.7 wide, so neighbors never touch),
     * rows 3 apart so every stone fronts a walkable lane. Fills row by row outward from the
     * yard center. Returns null when every plot inside the radius is taken.
     */
    private static BlockPos nextPlot(ServerLevel level, Yard yard) {
        // Pre-filter the graves that could occupy a plot in THIS yard's dimension, once, instead of
        // rescanning the whole (wild + held + other-dim) grave store for every candidate cell. The
        // per-cell occupancy test below is unchanged (same Math.abs(...) < 0.6 semantics).
        List<Grave> occupants = new ArrayList<>();
        for (Grave g : store().graves) {
            if (g.inGraveyard && !g.heldByKeeper && Objects.equals(g.dim, yard.dim)) {
                occupants.add(g);
            }
        }
        int rows = Math.max(1, yard.radius / 3);
        for (int r = 0; r <= rows; r++) {
            for (int side = 0; side < (r == 0 ? 1 : 2); side++) {
                int gz = r == 0 ? 0 : (side == 0 ? r : -r);
                for (int gx = -yard.radius; gx <= yard.radius; gx++) {
                    double px = yard.x + gx + 0.5;
                    double pz = yard.z + gz * 3 + 0.5;
                    boolean taken = false;
                    for (Grave g : occupants) {
                        if (Math.abs(g.x - px) < 0.6 && Math.abs(g.z - pz) < 0.6) {
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) {
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                                (int) Math.floor(px), (int) Math.floor(pz));
                        return BlockPos.containing(px, y, pz);
                    }
                }
            }
        }
        return null;
    }

    /**
     * A full yard makes room: the oldest LOOTED stone is cleared first (a memorial, nothing
     * lost); if none, the oldest unlooted grave is taken into the Gravekeeper's hold -- off the
     * lawn, loot intact, claimable through his menu (fees and the public timer still apply).
     * Abuse of a full yard is visual, never a broken mechanic.
     */
    private static boolean evictForSpace(MinecraftServer server, Yard yard) {
        Grave victim = null;
        for (boolean lootedPass : new boolean[]{true, false}) {
            for (Grave g : store().graves) {
                if (g.inGraveyard && !g.heldByKeeper && Objects.equals(g.dim, yard.dim)
                        && yard.anchorId.equals(g.graveyardAnchor) && g.looted == lootedPass
                        && (victim == null || g.diedAtMs < victim.diedAtMs)) {
                    victim = g;
                }
            }
            if (victim != null) {
                break;
            }
        }
        if (victim == null) {
            return false;
        }
        ServerLevel level = levelOf(server, victim.dim);
        if (level != null) {
            killDisplays(level, victim);
            restoreGround(level, victim); // vacate the plot: restore its ground + clear the flower
        }
        if (victim.looted) {
            store().graves.remove(victim);
        } else {
            victim.heldByKeeper = true;
            victim.inGraveyard = false;
            victim.graveyardAnchor = yard.anchorId; // remembers which keeper holds it
            com.k33bz.sanctuary.metrics.GraveEventLog.record("held", victim.id, victim.ownerName,
                    victim.dim, victim.x, victim.y, victim.z, victim.items.size(), null, false);
            notifyOwner(server, victim, "The graveyard overflowed; the Gravekeeper holds your remains now.");
        }
        // Persist the eviction here rather than trusting the caller — this store mutation must
        // survive a restart on its own. (Hardening from CoE #2: never leave a store change
        // depending on a later save() a future caller might forget.)
        save();
        return true;
    }

    /**
     * Block-break protection (part c): whether {@code pos} is a grave's protected structure — the
     * plot GROUND block under a grave, or the grave block itself if it's a solid ground block. The
     * FLORA on top (the flower at the grave's own position) is NOT protected — it's harvestable.
     * Covers both graveyard plots and wild grave markers. Cheap: scans the grave list (small) and
     * only for positions near a grave.
     */
    /**
     * Reverse lookup: the grave whose plot occupies {@code pos} (its base {@code gy} or ground
     * {@code gy-1}), or null. Mirrors the column match in {@link #isProtectedGraveBlock}; used by
     * {@link GraveRob} to turn a break on a protected grave block into a rob.
     */
    public static Grave graveAt(String dim, BlockPos pos) {
        for (Grave g : store().graves) {
            if (g.heldByKeeper || g.dim == null || !g.dim.equals(dim)) {
                continue;
            }
            int gx = (int) Math.floor(g.x);
            int gy = (int) Math.floor(g.y);
            int gz = (int) Math.floor(g.z);
            if (pos.getX() == gx && pos.getZ() == gz && (pos.getY() == gy || pos.getY() == gy - 1)) {
                return g;
            }
        }
        return null;
    }

    public static boolean isProtectedGraveBlock(String dim, BlockPos pos) {
        for (Grave g : store().graves) {
            if (g.heldByKeeper || g.dim == null || !g.dim.equals(dim)) {
                continue;
            }
            int gx = (int) Math.floor(g.x);
            int gy = (int) Math.floor(g.y);
            int gz = (int) Math.floor(g.z);
            if (pos.getX() != gx || pos.getZ() != gz) {
                continue;
            }
            // The plot ground (one below the grave base) is always protected. The grave base itself
            // is protected UNLESS it currently holds harvestable flora (a flower on top).
            if (pos.getY() == gy - 1) {
                return true;
            }
            if (pos.getY() == gy) {
                int stage = g.floraStage == null ? -1 : g.floraStage;
                return stage != 2; // stage 2 = a flower sits here → harvestable, not protected
            }
        }
        return false;
    }

    /**
     * Yard-region grief protection (0.8.3.3): whether {@code pos} lies inside ANY consecrated yard's
     * protected volume — its XZ fence footprint across a Y band from a few blocks below the floor up
     * through the fence/headstone/airspace above. This is PURE GEOMETRY over the yard bounds
     * ({@link GraveyardProtect}); it does NOT consult the grave registry, so it protects the bare
     * yard floor, OLD/unregistered graves (e.g. legacy gravel graves), new plots, the fence, and the
     * airspace alike — closing the 0.8.3.2 gap where only registered grave plots were covered and a
     * player dug gravel straight out of a fenced yard.
     *
     * <p><b>Flora-harvest carve-out preserved:</b> a block that is currently a HARVESTABLE flower on
     * a registered grave (the intended {@code 1g2} harvest) is NOT protected — it stays breakable.
     * That is the sole registry-aware exception layered on top of the geometric region.
     *
     * <p>Gated by {@code graveyardYardProtect} (default on); the band is {@code graveyardProtectDepth}
     * below / {@code graveyardProtectHeight} above the floor. Auto/hold-only yards (no fence bounds)
     * hold no world blocks and are skipped.
     */
    public static boolean isProtectedYardRegion(String dim, BlockPos pos) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.graveyardYardProtect) {
            return false;
        }
        int depth = Math.max(0, cfg.graveyardProtectDepth);
        int height = Math.max(0, cfg.graveyardProtectHeight);
        boolean inAnyYard = false;
        for (Yard y : store().yards) {
            if (y.dim == null || !y.dim.equals(dim)) {
                continue;
            }
            boolean hasBounds = GraveyardSmite.hasBounds(y.bMinX, y.bMaxX);
            if (GraveyardProtect.inProtectedRegion(pos.getX(), pos.getY(), pos.getZ(),
                    hasBounds, y.bMinX, y.bMaxX, y.bMinZ, y.bMaxZ, y.y, depth, height)) {
                inAnyYard = true;
                break;
            }
        }
        if (!inAnyYard) {
            return false;
        }
        // Flora carve-out: a harvestable flower on a registered grave stays breakable even inside the
        // protected region (the intended harvest). isProtectedGraveBlock already returns FALSE for a
        // stage-2 flower position, so "grave column here AND not plot-protected" == harvestable flora.
        if (isHarvestableFloraHere(dim, pos)) {
            return false;
        }
        return true;
    }

    /**
     * Whether {@code pos} is exactly the position of a harvestable flower on a registered graveyard
     * grave (the {@code 1g2} harvest). True only when a grave's base block sits here AND it currently
     * holds a stage-2 flower — the one block the yard-region protection must leave breakable.
     */
    private static boolean isHarvestableFloraHere(String dim, BlockPos pos) {
        for (Grave g : store().graves) {
            if (g.heldByKeeper || g.dim == null || !g.dim.equals(dim)) {
                continue;
            }
            int gx = (int) Math.floor(g.x);
            int gy = (int) Math.floor(g.y);
            int gz = (int) Math.floor(g.z);
            if (pos.getX() == gx && pos.getZ() == gz && pos.getY() == gy) {
                int stage = g.floraStage == null ? -1 : g.floraStage;
                return stage == 2; // a flower blooms here → harvestable, not protected
            }
        }
        return false;
    }

    /**
     * Default gravekeeper (0.8.2): on server start, if NO graveyard exists yet but at least one
     * sanctuary anchor does, raise a stationary keeper at the OLDEST anchor as a HOLD-ONLY yard so
     * there is always a reclaim/hold hub. Idempotent — does nothing if a yard already exists or the
     * feature is off.
     */
    public static void ensureDefaultKeeper(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.gravesEnabled || !cfg.graveDefaultKeeper) {
            return;
        }
        if (!store().yards.isEmpty()) {
            return; // a yard (default or manual) already exists
        }
        var anchors = com.k33bz.sanctuary.anchor.AnchorState.get().anchors;
        if (anchors == null || anchors.isEmpty()) {
            return; // no placed sanctuary to host the default keeper
        }
        // The OLDEST placed anchor = first in the list (ensureRegistered appends in placement order).
        com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor oldest = anchors.get(0);
        ServerLevel level = server.overworld();
        Yard yard = new Yard();
        yard.anchorId = oldest.id != null ? oldest.id : "legacy";
        yard.owner = oldest.ownerId;
        yard.dim = level.dimension().identifier().toString();
        // Stand the keeper one block BESIDE the anchor so it doesn't sit inside the crystal head.
        yard.x = (int) Math.floor(oldest.x) + 1;
        yard.z = (int) Math.floor(oldest.z);
        yard.y = safeKeeperY(level, yard.x, yard.z, oldest.y);
        yard.radius = 0;      // hold-only: no physical plots
        yard.auto = true;
        store().yards.add(yard);
        save();
        Gravekeeper.spawnKeeper(level, yard);
        Sanctuary.LOGGER.info("[sanctuary] Default gravekeeper raised at the oldest sanctuary ({}, {})",
                yard.x, yard.z);
    }

    /** Find a standable Y near the anchor for the default keeper (one above solid ground). */
    private static int safeKeeperY(ServerLevel level, int x, int z, int anchorY) {
        int start = anchorY > level.getMinY() ? anchorY : level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        // Prefer a spot beside the anchor block so the keeper doesn't sit inside the crystal head.
        return start;
    }

    /**
     * Self-heal (0.8.3.1): for EVERY yard, ensure its keeper still exists — if none stands within a
     * generous radius of the yard center, re-raise one via {@link Gravekeeper#spawnKeeper}. A keeper
     * can never be permanently lost (a lightning-witch conversion, despawn edge, or chunk-unload
     * loss self-heals on the next server start / periodic sweep). ADOPTS an existing keeper in the
     * yard zone rather than double-spawning (spawnKeeper also kills near-center keepers before
     * re-summoning, so even a re-raise leaves exactly one). Runs on SERVER_STARTED and periodically.
     */
    /** Throttle for the periodic self-heal (~30s at 20 tps). */
    private static int keeperHealCounter = 0;
    private static final int KEEPER_HEAL_INTERVAL_TICKS = 600;

    /** Periodic wrapper: run {@link #ensureKeepers} every ~30s from the server tick. */
    public static void ensureKeepersPeriodic(MinecraftServer server) {
        if (++keeperHealCounter < KEEPER_HEAL_INTERVAL_TICKS) {
            return;
        }
        keeperHealCounter = 0;
        ensureKeepers(server, false); // periodic: only loaded yards (don't pin cold chunks)
    }

    /** Debug/SERVER_STARTED entry point: force-load cold yard chunks so lost keepers are re-raised. */
    public static void ensureKeepers(MinecraftServer server) {
        ensureKeepers(server, true);
    }

    /**
     * @param loadCold when true, a cold yard chunk is synchronously loaded (via {@code getChunkAt})
     *                 so a lost keeper is re-raised even on a boot where the chunk isn't loaded yet;
     *                 when false (periodic in-game pass), unloaded yards are skipped so we never pin
     *                 chunks loaded.
     */
    public static void ensureKeepers(MinecraftServer server, boolean loadCold) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.gravesEnabled || store().yards.isEmpty()) {
            return;
        }
        int reach = Gravekeeper.KEEPER_REACH;
        for (Yard yard : store().yards) {
            ServerLevel level = levelOf(server, yard.dim);
            BlockPos center = new BlockPos(yard.x, yard.y, yard.z);
            if (level != null && loadCold && !level.isLoaded(center)) {
                level.getChunkAt(center); // pull the cold chunk in so we can verify + re-raise
            }
            if (level == null || !level.isLoaded(center)) {
                continue; // can't verify an unloaded yard; try again next pass
            }
            // EXACTLY-ONE invariant (0.8.3.3 — SPAWN-side, not cull-after). We COUNT first and only
            // (re)spawn when the count isn't exactly one, on BOTH the forced and periodic passes —
            // a lone, healthy keeper is left completely untouched (no reset, no flicker, no log). This
            // is what stops the over-spawn: previously the forced/boot pass ALWAYS reset, which —
            // combined with the boot pass racing the first periodic pass — churned keepers, and each
            // reset used /kill (a death broadcast → the "Gravekeeper was killed" chat spam). Now
            // spawnKeeper silently discards strays before summoning one, so even when a reset IS
            // needed it collapses to exactly one with no death message.
            //
            // The count is over TAGGED VILLAGERS (a lightning-converted WITCH keeps the tag but is
            // not a keeper). We reset when: no valid keeper, more than one, OR any tagged non-keeper
            // (witch/other) lingers — spawnKeeper's tag-scoped discard purges all of them.
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    yard.x - reach, level.getMinY(), yard.z - reach,
                    yard.x + 1 + reach, level.getMaxY(), yard.z + 1 + reach);
            var tagged = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box,
                    m -> m.entityTags().contains(Gravekeeper.KEEPER_TAG));
            int found = (int) tagged.stream()
                    .filter(m -> m instanceof net.minecraft.world.entity.npc.villager.Villager)
                    .count();
            boolean reset = keeperResetNeeded(found, tagged.size());
            if (reset) {
                Gravekeeper.spawnKeeper(level, yard);
                Sanctuary.LOGGER.info("[sanctuary] Self-heal: reset the Gravekeeper to exactly one at "
                        + "yard ({}, {}, {}) (was {})", yard.x, yard.y, yard.z, found);
            }
        }
    }

    /**
     * Pure single-keeper-invariant decision (0.8.3.3): given the number of valid keeper VILLAGERS in
     * a yard's reach ({@code validKeepers}) and the total count of TAGGED entities there
     * ({@code taggedTotal}, which includes lightning-converted witches that kept the tag), decide
     * whether the self-heal must (re)spawn to restore exactly one keeper.
     *
     * <p>Reset is needed when the valid keeper count isn't exactly one (zero = lost, ≥2 = over-spawn)
     * OR any tagged non-keeper lingers (a witch/other to be purged). A single healthy keeper with no
     * strays ({@code validKeepers == 1 && taggedTotal == 1}) is left UNTOUCHED — no respawn, no
     * flicker, no death broadcast. Extracted for Correction-of-Error unit tests of the invariant.
     */
    public static boolean keeperResetNeeded(int validKeepers, int taggedTotal) {
        return validKeepers != 1 || taggedTotal != validKeepers;
    }

    /**
     * Read-only admin diagnostics (0.8.3.3): one line per consecrated yard — its bounds, center,
     * anchor, resting-grave count, and the LIVE keeper population (valid keepers + any lingering
     * tagged strays) with each keeper's block position — followed by a world-total summary. Backs
     * {@code /sanctuarygrave list}; also the go-to tool for diagnosing keeper over-spawn.
     */
    public static List<String> describeYardsAndKeepers(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        List<Yard> yards = store().yards;
        if (yards.isEmpty()) {
            lines.add("No consecrated yards.");
        }
        int reach = Gravekeeper.KEEPER_REACH;
        for (Yard y : yards) {
            boolean hasBounds = GraveyardSmite.hasBounds(y.bMinX, y.bMaxX);
            String bounds = hasBounds
                    ? String.format(Locale.ROOT, "bounds [%d..%d]x[%d..%d]", y.bMinX, y.bMaxX, y.bMinZ, y.bMaxZ)
                    : (y.auto ? "hold-only (no bounds)" : "r=" + y.radius);
            int graveCount = 0;
            for (Grave g : store().graves) {
                if (!g.heldByKeeper && g.inGraveyard && y.anchorId.equals(g.graveyardAnchor)) {
                    graveCount++;
                }
            }
            int held = 0;
            for (Grave g : store().graves) {
                if (g.heldByKeeper && y.anchorId.equals(g.graveyardAnchor)) {
                    held++;
                }
            }
            // Live keeper population, if the yard chunk is loaded.
            String keeperInfo;
            ServerLevel level = levelOf(server, y.dim);
            BlockPos center = new BlockPos(y.x, y.y, y.z);
            if (level == null || !level.isLoaded(center)) {
                keeperInfo = "keepers: (chunk unloaded)";
            } else {
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        y.x - reach, level.getMinY(), y.z - reach,
                        y.x + 1 + reach, level.getMaxY(), y.z + 1 + reach);
                var tagged = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, box,
                        m -> m.entityTags().contains(Gravekeeper.KEEPER_TAG));
                long valid = tagged.stream()
                        .filter(m -> m instanceof net.minecraft.world.entity.npc.villager.Villager)
                        .count();
                StringBuilder pos = new StringBuilder();
                for (var k : tagged) {
                    if (pos.length() > 0) {
                        pos.append(", ");
                    }
                    pos.append(String.format(Locale.ROOT, "%s@(%d,%d,%d)",
                            k instanceof net.minecraft.world.entity.npc.villager.Villager ? "keeper" : "STRAY",
                            (int) Math.floor(k.getX()), (int) Math.floor(k.getY()), (int) Math.floor(k.getZ())));
                }
                keeperInfo = String.format(Locale.ROOT, "keepers: %d valid / %d tagged%s",
                        valid, tagged.size(), pos.length() > 0 ? " [" + pos + "]" : "");
            }
            lines.add(String.format(Locale.ROOT,
                    "Yard anchor=%s dim=%s center=(%d,%d,%d) %s — graves=%d (held=%d) — %s",
                    y.anchorId, y.dim, y.x, y.y, y.z, bounds, graveCount, held, keeperInfo));
        }
        lines.add(String.format(Locale.ROOT, "Total: %d yards, %d world graves.",
                yards.size(), store().graves.size()));
        return lines;
    }

    /**
     * Grave search (0.8.3.3): the UI-facing entry backing the Gravekeeper "Search" button and
     * {@code /sanctuarygrave search}. With a non-blank {@code needle}, returns graves whose owner
     * name contains it (case-insensitive); blank lists every grave. Each line carries the id, owner,
     * dimension, position, and where it rests (wild / yard / keeper-held / looted). Text results, so
     * the button can never resolve to an "unrecognized command".
     */
    public static List<String> searchGraves(String needle) {
        String n = needle == null ? "" : needle.trim().toLowerCase(Locale.ROOT);
        boolean filtering = !n.isEmpty();
        List<String> lines = new ArrayList<>();
        for (Grave g : store().graves) {
            if (filtering && (g.ownerName == null
                    || !g.ownerName.toLowerCase(Locale.ROOT).contains(n))) {
                continue;
            }
            String where = g.looted ? "looted"
                    : g.heldByKeeper ? "keeper-held"
                    : g.inGraveyard ? "yard:" + g.graveyardAnchor
                    : "wild";
            lines.add(String.format(Locale.ROOT, "%s — %s — %s (%d,%d,%d) [%s] items=%d",
                    g.id, g.ownerName == null ? "?" : g.ownerName, g.dim,
                    (int) Math.floor(g.x), (int) Math.floor(g.y), (int) Math.floor(g.z),
                    where, g.items.size()));
        }
        return lines;
    }

    public static Yard nearestYard(Grave grave) {
        Yard best = null;
        double bestSq = Double.MAX_VALUE;
        for (Yard yard : store().yards) {
            if (!Objects.equals(yard.dim, grave.dim)) {
                continue;
            }
            double dx = yard.x - grave.x, dz = yard.z - grave.z;
            double d = dx * dx + dz * dz;
            if (d < bestSq) {
                bestSq = d;
                best = yard;
            }
        }
        return best;
    }

    public static Yard yardNear(ServerPlayer player, double range) {
        String dim = player.level().dimension().identifier().toString();
        for (Yard yard : store().yards) {
            if (Objects.equals(yard.dim, dim)
                    && player.distanceToSqr(yard.x + 0.5, yard.y + 0.5, yard.z + 0.5) < range * range) {
                return yard;
            }
        }
        return null;
    }

    // --- claiming ---

    /** A right-click landed on a grave's interaction hitbox (matched by tag). */
    public static void tryClaim(ServerPlayer player, Grave grave, SanctuaryConfig cfg) {
        long now = System.currentTimeMillis();
        Long last = CLAIM_COOLDOWN.get(player.getUUID() + grave.id);
        if (last != null && now - last < 1000) {
            return;
        }
        CLAIM_COOLDOWN.put(player.getUUID() + grave.id, now);
        if (grave.looted) {
            player.sendSystemMessage(Component.literal("Only memories remain here.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        boolean owner = grave.owner.equals(player.getUUID().toString());
        if (!owner && !isPublic(grave)) {
            player.sendSystemMessage(Component.literal("This grave does not know you. Yet.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        // the gravekeeper's fee: owners claiming from a graveyard pay a small toll
        if (owner && grave.inGraveyard && cfg.graveClaimFeeFraction > 0) {
            int fee = SurvivalLogic.respawnCostLevels(player.experienceLevel,
                    cfg.graveClaimFeeFraction, 0, 0);
            if (player.experienceLevel < fee) {
                player.sendSystemMessage(Component.literal(
                                "The gravekeeper asks " + fee + " levels you do not have.")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (fee > 0) {
                player.giveExperienceLevels(-fee);
                StatBoards.addScore(player, "sanct_toll", fee);
            }
        }
        ServerLevel level = (ServerLevel) player.level();
        int restored = restoreItems(player, grave);
        restoreGround(level, grave); // put the plot ground back + clear its flower when looted
        grave.looted = true;
        spawnDisplays(level, grave); // headstone cracks
        save();
        if (!owner) {
            StatBoards.addScore(player, "sanct_robbed", 1);
        }
        com.k33bz.sanctuary.metrics.GraveEventLog.record("claimed", grave.id, grave.ownerName,
                grave.dim, grave.x, grave.y, grave.z, restored, player.getName().getString(), !owner);
        player.sendSystemMessage(Component.literal(owner
                        ? "Your belongings return to you (" + restored + " stacks)."
                        : "You rob the grave of " + grave.ownerName + " (" + restored + " stacks).")
                .withStyle(owner ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED));
        if (!owner) {
            notifyOwner(level.getServer(), grave, "Your unclaimed grave was looted by " + player.getName().getString() + ".");
        }
    }

    /** Pour a grave's items into a player (overflow drops at their feet). Returns stack count. */
    public static int restoreItems(ServerPlayer player, Grave grave) {
        ServerLevel level = (ServerLevel) player.level();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        int restored = 0;
        for (JsonElement el : grave.items) {
            var parsed = ItemStack.CODEC.parse(ops, el).result();
            if (parsed.isEmpty()) {
                continue;
            }
            ItemStack stack = parsed.get();
            if (!player.getInventory().add(stack)) {
                level.addFreshEntity(new ItemEntity(level,
                        player.getX(), player.getY() + 0.4, player.getZ(), stack));
            }
            restored++;
        }
        grave.items.clear();
        return restored;
    }

    /** Owner (with fee) or, once public, anyone claims a keeper-held grave. */
    public static int claimHeld(ServerPlayer player, String graveId, SanctuaryConfig cfg) {
        Grave grave = byId(graveId);
        Yard yard = yardNear(player, 32);
        if (grave == null || !grave.heldByKeeper || grave.items.isEmpty() || yard == null
                || !yard.anchorId.equals(grave.graveyardAnchor)) {
            return 0;
        }
        boolean owner = grave.owner.equals(player.getUUID().toString());
        if (!owner && !isPublic(grave)) {
            return 0;
        }
        if (owner && cfg.graveClaimFeeFraction > 0) {
            int fee = SurvivalLogic.respawnCostLevels(player.experienceLevel, cfg.graveClaimFeeFraction, 0, 0);
            if (player.experienceLevel < fee) {
                player.sendSystemMessage(Component.literal(
                        "The gravekeeper asks " + fee + " levels you do not have.").withStyle(ChatFormatting.RED));
                return 0;
            }
            if (fee > 0) {
                player.giveExperienceLevels(-fee);
                StatBoards.addScore(player, "sanct_toll", fee);
            }
        }
        int restored = restoreItems(player, grave);
        store().graves.remove(grave);
        save();
        if (!owner) {
            StatBoards.addScore(player, "sanct_robbed", 1);
        }
        com.k33bz.sanctuary.metrics.GraveEventLog.record("claimed", grave.id, grave.ownerName,
                grave.dim, grave.x, grave.y, grave.z, restored, player.getName().getString(), !owner);
        player.sendSystemMessage(Component.literal(owner
                ? "The keeper returns what was yours (" + restored + " stacks)."
                : "The keeper shrugs and hands over " + grave.ownerName + "'s effects ("
                        + restored + " stacks).")
                .withStyle(owner ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED));
        if (!owner) {
            notifyOwner(player.level().getServer(), grave,
                    "Your keeper-held grave expired and was claimed by " + player.getName().getString() + ".");
        }
        return 1;
    }

    /** Outcome of an admin clearworld: counts + the keeper hold graves were moved to. */
    public record ClearResult(int movedToHold, int removed, String holdYard) {
    }

    /**
     * Admin force-clear (part e). For every LOOT-bearing grave, remove its world headstone and move
     * its inventory into the NEAREST gravekeeper's hold (heldByKeeper=true, reclaimable via the
     * keeper dialog — NOTHING is lost). Empty/looted graves just have their marker + entry removed.
     * By default only WILD graves (not in a graveyard); {@code includeGraveyard} also clears
     * in-graveyard graves and their plot ground blocks. Returns null if no graveyard exists (there
     * would be nowhere to hold the loot).
     */
    public static ClearResult clearWorld(MinecraftServer server, boolean includeGraveyard) {
        if (store().yards.isEmpty()) {
            return null; // nowhere to hold loot
        }
        int movedToHold = 0;
        int removed = 0;
        String holdYard = null;
        var it = store().graves.iterator();
        List<Grave> toHold = new ArrayList<>();
        while (it.hasNext()) {
            Grave g = it.next();
            if (g.heldByKeeper) {
                continue; // already in a hold
            }
            if (g.inGraveyard && !includeGraveyard) {
                continue; // graveyard graves left alone unless asked
            }
            ServerLevel level = levelOf(server, g.dim);
            if (level != null) {
                killDisplays(level, g);
                // Restore the plot ground + clear its flower (graveyard graves only; no-op on wild).
                restoreGround(level, g);
            }
            if (g.looted || g.items.isEmpty()) {
                it.remove(); // empty/looted: nothing to preserve
                removed++;
                continue;
            }
            toHold.add(g); // defer the mutation; capture the destination yard below
        }
        for (Grave g : toHold) {
            Yard yard = nearestYard(g);
            if (yard == null) {
                continue;
            }
            g.heldByKeeper = true;
            g.inGraveyard = false;
            g.graveyardAnchor = yard.anchorId;
            g.floraStage = null;
            g.epitaphTier = null;
            holdYard = yard.anchorId;
            movedToHold++;
            com.k33bz.sanctuary.metrics.GraveEventLog.record("held", g.id, g.ownerName,
                    g.dim, g.x, g.y, g.z, g.items.size(), "clearworld", false);
        }
        save();
        return new ClearResult(movedToHold, removed, holdYard);
    }

    public static Grave byId(String id) {
        for (Grave g : store().graves) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }

    static void notifyOwner(MinecraftServer server, Grave grave, String message) {
        if (grave.owner == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(java.util.UUID.fromString(grave.owner));
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    public static ServerLevel levelOf(MinecraftServer server, String dim) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
    }

    public static void run(ServerLevel level, String command) {
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
