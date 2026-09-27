package com.k33bz.sanctuary.anchor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.metrics.BaseRepairLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * System 12 — base auto-repair: the world plumbing. The rules (tiers, delays, costs, speeds,
 * restore decisions) live in the Minecraft-free {@link BaseRepairRules}; this class journals
 * damage, persists the journal, rebuilds due entries, and pays for them out of anchor fuel.
 *
 * <p><b>What is journaled</b> — only inside an ACTIVE sanctuary in the overworld, and only damage
 * that isn't the anchor owner's own doing:
 * <ul>
 *   <li>explosions ({@code ExplosionRepairMixin}), unless the owner lit them (remodelling);</li>
 *   <li>mobs breaking blocks via {@code Level.destroyBlock} — withers, ravagers, the siege
 *       frame-smashers ({@code MobDestroyBlockMixin});</li>
 *   <li>zombies breaking doors ({@code BreakDoorGoalMixin});</li>
 *   <li>farmland trampled back to dirt by anything but the owner ({@code FarmlandTrampleMixin});</li>
 *   <li>blocks burned away by fire ({@code FireBurnMixin});</li>
 *   <li>enderman theft, when {@code endermanCloneNotSteal} is off ({@code EndermanTakeBlockMixin}).</li>
 * </ul>
 *
 * <p><b>Anti-dupe</b> — a journaled block drops NOTHING: it is removed and its state written to the
 * journal, so the only way it comes back is the rebuild. Blocks with block entities (chests,
 * shulkers, signs, beds, heads) are never journaled at all; they break exactly as vanilla, contents
 * and all, so nothing can be both dropped and rebuilt. TNT is never journaled (a rebuilt TNT next
 * to lingering fire is a bomb loop). Blocks other players break are logged, not rebuilt.
 *
 * <p><b>When it rebuilds</b> — each entry waits its tier's delay (scaled by the owner's speed),
 * then rebuilds the next time its chunk is loaded, provided the anchor is still active, repairs
 * aren't OFF, the anchor can pay without dropping under the fuel reserve, nothing living stands
 * in the spot, and nobody has built something else there in the meantime.
 */
public final class BaseRepair {
    private BaseRepair() {
    }

    /** One journaled block. Public fields for GSON. */
    public static final class Entry {
        public String anchorId;
        public int x;
        public int y;
        public int z;
        /** The original block state, encoded with {@code BlockState.CODEC} (JSON). */
        public JsonElement state;
        /** Block id before the damage (for "already fixed by hand" detection). */
        public String original;
        /** Block id the damage left behind (air for a blast, dirt for trampled farmland). */
        public String leftBehind;
        public String cause;
        public int tier;
        public long recordedAt;
        public long dueAt;

        long key() {
            return BlockPos.asLong(x, y, z);
        }
    }

    /** Persisted journal. */
    public static final class Store {
        public List<Entry> entries = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Store store;
    private static final Map<Long, Entry> BY_POS = new HashMap<>();
    private static boolean dirty = false;
    private static int saveCounter = 0;

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            save();
            BaseRepairLog.close();
        });
        // Raid log: a NON-owner breaking a block inside an active sanctuary. Not rebuilt (they keep
        // the drop, so a rebuild would be a duplicate); logged so patterns and dupe loops show up.
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            SanctuaryConfig cfg = Sanctuary.CONFIG;
            if (cfg == null || !cfg.baseRepairLogPlayerBreaks || !(world instanceof ServerLevel level)
                    || level.dimension() != Level.OVERWORLD) {
                return;
            }
            AnchorState.PlacedAnchor a = AnchorState.get().activeAnchorCovering(pos.getX() + 0.5, pos.getZ() + 0.5);
            if (a == null || isOwner(a, player)) {
                return;
            }
            BaseRepairLog.event("player_break",
                    "anchor", AnchorState.shortId(a.id), "owner", a.owner,
                    "breaker", player.getGameProfile().name(), "breakerId", player.getUUID().toString(),
                    "creative", player.isCreative(),
                    "block", blockId(state), "x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
        });
    }

    // ------------------------------------------------------------------ journaling

    /** Is this player the anchor's owner (by UUID)? */
    public static boolean isOwner(AnchorState.PlacedAnchor a, Entity e) {
        return e instanceof Player p && a.ownerId != null && a.ownerId.equals(p.getUUID().toString());
    }

    /**
     * The active anchor that would journal damage at {@code pos} caused by {@code actor}, or null
     * when nothing should be journaled there (feature off, wrong dimension, outside every active
     * sanctuary, or the owner did it).
     */
    private static AnchorState.PlacedAnchor guardian(ServerLevel level, BlockPos pos, Entity actor) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.baseRepairEnabled || level.dimension() != Level.OVERWORLD) {
            return null;
        }
        AnchorState.PlacedAnchor a = AnchorState.get().activeAnchorCovering(pos.getX() + 0.5, pos.getZ() + 0.5);
        if (a == null || isOwner(a, actor)) {
            return null;
        }
        return a;
    }

    /** Can this block ever be journaled? (No air, no block entities, no fire, no TNT, no anchor.) */
    private static boolean journalable(BlockState state, BlockPos pos) {
        return !state.isAir() && !state.hasBlockEntity() && !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)
                && !state.is(Blocks.TNT) && state.getFluidState().isEmpty()
                && !AnchorState.get().isAnchor(pos);
    }

    /**
     * Journal {@code original} at {@code pos} as damage that left {@code leftBehind} there. Does
     * NOT touch the world. Returns true if an entry was written.
     */
    public static boolean journal(ServerLevel level, BlockPos pos, BlockState original, String leftBehind,
                                  String cause, Entity actor) {
        AnchorState.PlacedAnchor a = guardian(level, pos, actor);
        if (a == null || !journalable(original, pos)) {
            return false;
        }
        return put(level, a, pos, original, leftBehind, cause);
    }

    private static boolean put(ServerLevel level, AnchorState.PlacedAnchor a, BlockPos pos, BlockState original,
                               String leftBehind, String cause) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        JsonElement encoded = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, original).result().orElse(null);
        if (encoded == null) {
            return false;
        }
        Store s = store();
        long key = pos.asLong();
        Entry prior = BY_POS.get(key);
        if (prior != null) {
            // Damaged again before the rebuild: whatever stands there now is the newest choice
            // (someone filled the hole), so it replaces the older journal entry.
            s.entries.remove(prior);
        }
        String id = blockId(original);
        int tier = BaseRepairRules.tier(original.getDestroySpeed(level, pos), id,
                cfg.baseRepairTierHardness, cfg.baseRepairPreciousBlocks);
        BaseRepairRules.Mode mode = modeOf(a);
        Entry e = new Entry();
        e.anchorId = a.id;
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.state = encoded;
        e.original = id;
        e.leftBehind = leftBehind;
        e.cause = cause;
        e.tier = tier;
        e.recordedAt = level.getGameTime();
        e.dueAt = e.recordedAt + BaseRepairRules.delayTicks(tier,
                mode == BaseRepairRules.Mode.OFF ? BaseRepairRules.Mode.NORMAL : mode, cfg.baseRepairTierDelaySeconds);
        s.entries.add(e);
        BY_POS.put(key, e);
        while (s.entries.size() > Math.max(1, cfg.baseRepairMaxQueue)) {
            Entry oldest = s.entries.remove(0);
            BY_POS.remove(oldest.key());
        }
        dirty = true;
        return true;
    }

    /**
     * Explosion hook: journal and remove (without drops) every block the blast would break inside
     * an active sanctuary, and hand the explosion back only the rest. Runs AFTER Flan has filtered
     * the list, so claim-protected blocks were never here in the first place.
     */
    public static List<BlockPos> interceptExplosion(ServerLevel level, Explosion explosion, List<BlockPos> blocks) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.baseRepairEnabled || level.dimension() != Level.OVERWORLD || blocks.isEmpty()) {
            return blocks;
        }
        Entity actor = explosion.getIndirectSourceEntity();
        if (actor == null) {
            actor = explosion.getDirectSourceEntity();
        }
        List<BlockPos> keep = new ArrayList<>(blocks.size());
        List<BlockPos> taken = new ArrayList<>();
        String cause = actor == null ? "explosion" : "explosion:" + typeId(actor);
        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            if (journal(level, pos, state, "minecraft:air", cause, actor)) {
                taken.add(pos);
            } else {
                keep.add(pos);
            }
        }
        // Remove in a second pass with no neighbour updates, so nothing attached pops off as an
        // item (a torch on a blasted wall would otherwise drop AND be rebuilt: a dupe).
        for (BlockPos pos : taken) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        if (!taken.isEmpty()) {
            logJournaled(level, taken.get(0), taken.size(), cause, actor);
        }
        return keep;
    }

    /**
     * Mob block-break hook ({@code Level.destroyBlock} with a mob breaker). Journals the block (and
     * the other half of a door/tall plant), removes it without drops, and returns true when it
     * handled the break — the caller then cancels vanilla's drop-and-destroy.
     */
    public static boolean interceptMobBreak(ServerLevel level, BlockPos pos, Entity breaker) {
        BlockState state = level.getBlockState(pos);
        String cause = "mob:" + typeId(breaker);
        if (!journal(level, pos, state, "minecraft:air", cause, breaker)) {
            return false;
        }
        journalPartnerHalf(level, pos, state, cause, breaker);
        level.levelEvent(2001, pos, Block.getId(state)); // break particles + sound, as vanilla would
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        logJournaled(level, pos, 1, cause, breaker);
        return true;
    }

    /** Door-breaking zombies remove (never drop) the door; journal both halves first. */
    public static void journalBeforeRemoval(ServerLevel level, BlockPos pos, String cause, Entity actor) {
        BlockState state = level.getBlockState(pos);
        if (journal(level, pos, state, "minecraft:air", cause, actor)) {
            journalPartnerHalf(level, pos, state, cause, actor);
            logJournaled(level, pos, 1, cause, actor);
        }
    }

    /** Doors and tall plants are two blocks; removing one silently removes the other. */
    private static void journalPartnerHalf(ServerLevel level, BlockPos pos, BlockState state, String cause, Entity actor) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return;
        }
        BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                ? pos.above() : pos.below();
        BlockState otherState = level.getBlockState(other);
        if (otherState.is(state.getBlock())) {
            journal(level, other, otherState, "minecraft:air", cause, actor);
        }
    }

    private static void logJournaled(ServerLevel level, BlockPos pos, int count, String cause, Entity actor) {
        AnchorState.PlacedAnchor a = AnchorState.get().activeAnchorCovering(pos.getX() + 0.5, pos.getZ() + 0.5);
        BaseRepairLog.event("journaled",
                "anchor", a == null ? null : AnchorState.shortId(a.id), "cause", cause, "count", count,
                "actor", actor instanceof Player p ? p.getGameProfile().name() : actor == null ? null : typeId(actor),
                "x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
    }

    // ------------------------------------------------------------------ rebuilding

    /** Once per second from the main tick loop. */
    public static void tick(MinecraftServer server, SanctuaryConfig cfg) {
        BaseRepairLog.flush();
        if (++saveCounter >= 60) {
            saveCounter = 0;
            if (dirty) {
                save();
            }
        }
        if (cfg == null || !cfg.baseRepairEnabled) {
            return;
        }
        Store s = store();
        if (s.entries.isEmpty()) {
            return;
        }
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        int budget = Math.max(1, cfg.baseRepairMaxPerTick);
        Map<String, int[]> restoredPer = new HashMap<>();   // anchor id -> {restored, superseded}
        Map<String, Double> costPer = new HashMap<>();
        boolean anchorsChanged = false;

        Iterator<Entry> it = s.entries.iterator();
        while (it.hasNext() && budget > 0) {
            Entry e = it.next();
            if (e.dueAt > now) {
                continue;
            }
            AnchorState.PlacedAnchor a = AnchorState.get().byId(e.anchorId);
            if (a == null) {
                it.remove(); // anchor broken: its journal goes with it
                BY_POS.remove(e.key());
                dirty = true;
                continue;
            }
            BaseRepairRules.Mode mode = modeOf(a);
            if (!a.isActive(now) || mode == BaseRepairRules.Mode.OFF) {
                continue; // kept: resumes when refuelled / switched back on
            }
            BlockPos pos = new BlockPos(e.x, e.y, e.z);
            if (!level.isLoaded(pos)) {
                continue; // overdue repairs land as soon as someone is near enough to load it
            }
            BlockState current = level.getBlockState(pos);
            double cost = BaseRepairRules.costHours(e.tier, mode, cfg.baseRepairTierCostHours);
            boolean blocked = !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty();
            boolean affordable = BaseRepairRules.canAfford(a.expiry, now, cost, cfg.baseRepairFuelReserveHours);
            BaseRepairRules.Outcome outcome = BaseRepairRules.decide(blockId(current), e.leftBehind, e.original,
                    current.canBeReplaced(), blocked, affordable);
            if (outcome == BaseRepairRules.Outcome.WAIT) {
                continue;
            }
            it.remove();
            BY_POS.remove(e.key());
            dirty = true;
            int[] tally = restoredPer.computeIfAbsent(a.id, k -> new int[2]);
            if (outcome == BaseRepairRules.Outcome.SUPERSEDED) {
                tally[1]++;
                continue;
            }
            BlockState original = BlockState.CODEC.parse(JsonOps.INSTANCE, e.state).result().orElse(null);
            if (original == null) {
                tally[1]++; // block no longer exists (mod removed / renamed): nothing to rebuild
                continue;
            }
            level.setBlock(pos, original, Block.UPDATE_ALL);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, e.x + 0.5, e.y + 0.5, e.z + 0.5, 3, 0.3, 0.3, 0.3, 0.0);
            if (!a.isExempt() && cost > 0) {
                a.expiry = BaseRepairRules.paidExpiry(a.expiry, cost);
                anchorsChanged = true;
            }
            tally[0]++;
            costPer.merge(a.id, cost, Double::sum);
            budget--;
        }
        if (anchorsChanged) {
            AnchorState.get().save();
        }
        for (Map.Entry<String, int[]> t : restoredPer.entrySet()) {
            if (t.getValue()[0] > 0) {
                BaseRepairLog.event("restored", "anchor", AnchorState.shortId(t.getKey()),
                        "count", t.getValue()[0], "costHours", costPer.getOrDefault(t.getKey(), 0.0));
            }
            if (t.getValue()[1] > 0) {
                BaseRepairLog.event("superseded", "anchor", AnchorState.shortId(t.getKey()),
                        "count", t.getValue()[1]);
            }
        }
    }

    // ------------------------------------------------------------------ owner controls / queries

    /** The anchor's repair speed (its own choice, else the server default). */
    public static BaseRepairRules.Mode modeOf(AnchorState.PlacedAnchor a) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        BaseRepairRules.Mode def = BaseRepairRules.Mode.parse(cfg == null ? null : cfg.baseRepairDefaultMode,
                BaseRepairRules.Mode.NORMAL);
        return BaseRepairRules.Mode.parse(a.repairMode, def);
    }

    /** How many blocks are waiting to be rebuilt for this anchor. */
    public static int queued(AnchorState.PlacedAnchor a) {
        int n = 0;
        for (Entry e : store().entries) {
            if (a.id != null && a.id.equals(e.anchorId)) {
                n++;
            }
        }
        return n;
    }

    /** Total journal size (for the admin report). */
    public static int queuedTotal() {
        return store().entries.size();
    }

    // ------------------------------------------------------------------ helpers / persistence

    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_repairs.json");
    }

    private static Store store() {
        if (store == null) {
            store = load();
            BY_POS.clear();
            for (Entry e : store.entries) {
                BY_POS.put(e.key(), e);
            }
        }
        return store;
    }

    private static Store load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                Store s = GSON.fromJson(Files.readString(p), Store.class);
                if (s != null) {
                    if (s.entries == null) {
                        s.entries = new ArrayList<>();
                    }
                    s.entries.removeIf(e -> e == null || e.state == null || e.anchorId == null);
                    return s;
                }
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to load the repair journal; starting empty", e);
        }
        return new Store();
    }

    public static void save() {
        if (store == null) {
            return;
        }
        try {
            Files.writeString(path(), GSON.toJson(store));
            dirty = false;
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to save the repair journal", e);
        }
    }
}
