package com.k33bz.sanctuary.rift;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Rift portals — the resource-world gateway lives inside the OVERWORLD's naturally-generated RUINED
 * PORTALS. Crying obsidian only comes from ruined portals (and player crafting), never from a hand-built
 * Nether portal, so "an open cell that touches a crying-obsidian frame" reliably marks a rift gateway while
 * plain-obsidian portals are left to vanilla (the Nether). When a player steps into such an opening, out in
 * the wild, the mod fills the opening plane with a GREEN membrane and REGISTERS the portal in
 * {@link RiftStore}; from then on it is permanent and position-based — rebuilding or tearing out the frame
 * doesn't disable it — and the existing {@link Rifts} travel carries the player to {@code resource_world}.
 *
 * <p>Detection is deliberately narrow (overworld only, wild only, frame-adjacent, capped per player) so a
 * gateway can't be forged from two stray crying blocks inside a base, spammed to exhaust the store, or
 * hijacked out of a lit vanilla Nether portal. The membrane stays in the portal's own vertical plane so an
 * incomplete/open ruined frame never bleeds glass across the landscape and a traveller is never entombed.
 */
public final class RiftPortals {
    private RiftPortals() {
    }

    private static final int RUINED_RADIUS = 2;     // search box (radius) for crying obsidian around a player
    private static final int MEMBRANE_CAP = 14;     // max green cells placed per portal (keeps a broken frame tidy)
    private static final int FILL_REACH = 2;        // membrane flood stays within this manhattan distance of centre
    private static final double DEDUPE = 5.0;       // don't register a new portal within this of an existing one

    public static void register() {
        // No ignition hook — ruined portals auto-activate on contact via checkRuined() (driven by Rifts.tick).
    }

    static boolean isFrame(BlockState s) {
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN);
    }

    static boolean isOpen(BlockState s) {
        // Only genuine air (or an already-placed membrane) counts as a portal opening. NETHER_PORTAL and
        // FIRE are excluded so a lit vanilla Nether portal is never hijacked into a rift gateway.
        return s.isAir() || s.is(Blocks.GREEN_STAINED_GLASS);
    }

    /**
     * Per-player, from {@link Rifts#tick} BEFORE the travel check: if the player is standing in a ruined
     * portal's opening (out in the wild) and no rift portal is registered nearby yet, establish one (green
     * membrane + register) so the travel check then carries them across. Cheap in the common case: bails
     * after a handful of block reads unless the player is actually stood in a frame-adjacent opening.
     */
    static void checkRuined(ServerLevel w, ServerPlayer p, SanctuaryConfig cfg) {
        // Rift gateways form only in the overworld's ruined portals — never in the Nether/End (whose ruined
        // portals are abundant and crying-obsidian-rich) nor inside the gathering world itself.
        if (!w.dimension().identifier().toString().equals("minecraft:overworld")) {
            return;
        }
        BlockPos feet = p.blockPosition();
        if (!isOpen(w.getBlockState(feet))) {
            return; // must be standing in an air/membrane cell (a portal opening)
        }
        if (!touchesFrame(w, feet)) {
            return; // cheap gate: a portal opening touches its frame — filters open air fast, no box scan
        }
        // Wild-only, like the retired Rift Anchor: no gateways at spawn or inside a sanctuary/claim, so
        // protected ground can't be turned into a resource-world doorway (and griefers can't trap a base).
        if (Sanctuary.blocksBeyondNearestAnchor(cfg, feet.getX() + 0.5, feet.getZ() + 0.5) <= 0.0) {
            return;
        }
        RiftStore store = RiftStore.get();
        for (RiftStore.Rift r : store.rifts) {
            if (r.portal && r.dim.equals("minecraft:overworld")
                    && feet.distSqr(new BlockPos(r.x, r.y, r.z)) <= DEDUPE * DEDUPE) {
                return; // already an established portal here — let the travel check handle it
            }
        }
        if (countCrying(w, feet) < cfg.riftMinCrying) {
            return; // a real ruined portal carries crying obsidian; a plain (all-obsidian) portal never fires
        }
        // Creation caps: bound the store, the return-portal spam, and the weekly-reset pad carving.
        // (0 = unlimited for either cap.)
        if (cfg.riftMaxTotal > 0 && store.rifts.size() >= cfg.riftMaxTotal) {
            return;
        }
        String ownerId = p.getUUID().toString();
        if (cfg.riftMaxPerPlayer > 0) {
            int mine = 0;
            for (RiftStore.Rift r : store.rifts) {
                if (r.portal && ownerId.equals(r.ownerId)) {
                    mine++;
                }
            }
            if (mine >= cfg.riftMaxPerPlayer) {
                p.sendSystemMessage(Component.literal("The wild will hold no more rifts of your making.")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
                return;
            }
        }
        establish(w, feet, p.getScoreboardName(), ownerId);
    }

    /** True if any of the six neighbours of {@code feet} is a portal-frame block (obsidian/crying). */
    private static boolean touchesFrame(ServerLevel w, BlockPos feet) {
        for (Direction d : Direction.values()) {
            if (isFrame(w.getBlockState(feet.relative(d)))) {
                return true;
            }
        }
        return false;
    }

    /** Light + register a rift portal centred on {@code centre} (a ruined-portal opening the player stands in). */
    static void establish(ServerLevel w, BlockPos centre, String owner, String ownerId) {
        fillMembrane(w, centre);
        RiftStore store = RiftStore.get();
        String dim = w.dimension().identifier().toString();
        if (!store.exists(dim, centre)) {
            RiftStore.Rift r = store.create(dim, centre, owner, ownerId); // create() persists the base record
            r.portal = true;
            r.h = 3;
            store.save(); // persist the portal/h flags set after create()
        }
        run(w.getServer(), String.format(Locale.ROOT,
                "particle minecraft:composter %.2f %.2f %.2f 0.4 0.7 0.4 0.05 40",
                centre.getX() + 0.5, centre.getY() + 1.0, centre.getZ() + 0.5));
        run(w.getServer(), String.format(Locale.ROOT,
                "playsound minecraft:block.beacon.activate block @a %.2f %.2f %.2f 1 1.4",
                centre.getX() + 0.5, centre.getY() + 0.5, centre.getZ() + 0.5));
    }

    private static int countCrying(ServerLevel w, BlockPos centre) {
        int n = 0;
        for (int dx = -RUINED_RADIUS; dx <= RUINED_RADIUS; dx++) {
            for (int dy = -RUINED_RADIUS; dy <= RUINED_RADIUS; dy++) {
                for (int dz = -RUINED_RADIUS; dz <= RUINED_RADIUS; dz++) {
                    if (w.getBlockState(centre.offset(dx, dy, dz)).is(Blocks.CRYING_OBSIDIAN)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * Flood-fill the opening with the green membrane, CONSTRAINED to the portal's own vertical plane. The
     * plane is the horizontal axis carrying the frame's side columns; the perpendicular ("depth") axis — the
     * walk-through faces — is left clear. This means (a) an incomplete/open ruined frame can't bleed glass
     * out across the landscape, and (b) the opening's front/back cells stay air, so a traveller landing at or
     * beside the opening is never entombed. The player's own cell is left open regardless.
     */
    private static void fillMembrane(ServerLevel w, BlockPos centre) {
        boolean fx = frameAlong(w, centre, Direction.Axis.X);
        boolean fz = frameAlong(w, centre, Direction.Axis.Z);
        Direction.Axis depth = (fz && !fx) ? Direction.Axis.X : Direction.Axis.Z; // default plane = X-Y
        Set<BlockPos> fill = new HashSet<>();
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(centre.immutable());
        while (!q.isEmpty() && fill.size() < MEMBRANE_CAP) {
            BlockPos b = q.poll();
            if (fill.contains(b) || centre.distManhattan(b) > FILL_REACH) {
                continue;
            }
            int depthOff = depth == Direction.Axis.X ? (b.getX() - centre.getX()) : (b.getZ() - centre.getZ());
            if (depthOff != 0) {
                continue; // stay in the plane — never fill the open walk-through faces
            }
            BlockState s = w.getBlockState(b);
            if (isFrame(s)) {
                continue; // frame boundary
            }
            if (!s.isAir() && !s.is(Blocks.GREEN_STAINED_GLASS)) {
                continue; // only fill genuinely open cells
            }
            fill.add(b.immutable());
            for (Direction d : Direction.values()) {
                if (d.getAxis() != depth) { // don't even traverse toward the open faces
                    q.add(b.relative(d));
                }
            }
        }
        BlockState glass = Blocks.GREEN_STAINED_GLASS.defaultBlockState();
        for (BlockPos b : fill) {
            if (!b.equals(centre) && w.getBlockState(b).isAir()) {
                w.setBlockAndUpdate(b, glass);
            }
        }
    }

    /** True if a portal-frame block sits within 2 cells of {@code c} along {@code axis} (a side column). */
    private static boolean frameAlong(ServerLevel w, BlockPos c, Direction.Axis axis) {
        for (int s = -2; s <= 2; s++) {
            if (s == 0) {
                continue;
            }
            BlockPos p = axis == Direction.Axis.X ? c.offset(s, 0, 0) : c.offset(0, 0, s);
            if (isFrame(w.getBlockState(p))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A stand-able cell at or beside a portal rift — searched outward (nearest ring first) so a traveller
     * never materialises inside the solid green membrane or a frame block. Returns {@code (x,y,z)} unchanged
     * only if nothing better is found within reach (a fully-buried portal), which the plane-constrained
     * membrane still leaves walk-out-able along its open faces.
     */
    public static BlockPos safeLandingNear(ServerLevel w, int x, int y, int z, int h) {
        int reach = Math.max(2, h + 1);
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // only the ring at Chebyshev distance r (so nearer cells win)
                    }
                    for (int dy = 0; dy <= reach; dy++) {
                        if (standable(w, x + dx, y + dy, z + dz)) {
                            return new BlockPos(x + dx, y + dy, z + dz);
                        }
                        if (dy != 0 && standable(w, x + dx, y - dy, z + dz)) {
                            return new BlockPos(x + dx, y - dy, z + dz);
                        }
                    }
                }
            }
        }
        return new BlockPos(x, y, z);
    }

    private static boolean standable(ServerLevel w, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        return w.getBlockState(feet).isAir()
                && w.getBlockState(feet.above()).isAir()
                && w.getBlockState(feet.below()).blocksMotion();
    }

    /**
     * Build a matching green return portal at a resource-world landing (a crying-obsidian frame + green
     * membrane in the X-Y plane at {@code base.z}, with a standing platform in front at +Z). Every write is
     * gated by {@link #replaceable} so it lays over air/natural terrain only — never carving through a
     * player build. Returns the membrane-interior anchor (bottom-left); {@link Rifts} registers that as the
     * {@code portal} rift and lands the traveller on the platform 2 blocks out. {@code base} = the ground cell.
     */
    static BlockPos buildReturnPortal(ServerLevel dest, BlockPos base) {
        int bx = base.getX();
        int by = base.getY();
        int bz = base.getZ();
        BlockState cry = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        BlockState obs = Blocks.OBSIDIAN.defaultBlockState();
        BlockState glass = Blocks.GREEN_STAINED_GLASS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        // floor under the frame + a 2-deep standing platform in front (+Z)
        for (int x = bx - 1; x <= bx + 2; x++) {
            for (int dz = 0; dz <= 2; dz++) {
                place(dest, new BlockPos(x, by, bz + dz), obs);
            }
        }
        // frame: top row + two side columns (crying obsidian, so it reads as a rift gateway)
        for (int x = bx - 1; x <= bx + 2; x++) {
            place(dest, new BlockPos(x, by + 4, bz), cry);
        }
        for (int y = by + 1; y <= by + 3; y++) {
            place(dest, new BlockPos(bx - 1, y, bz), cry);
            place(dest, new BlockPos(bx + 2, y, bz), cry);
        }
        // green membrane interior (2 wide x 3 tall)
        for (int x = bx; x <= bx + 1; x++) {
            for (int y = by + 1; y <= by + 3; y++) {
                place(dest, new BlockPos(x, y, bz), glass);
            }
        }
        // clear headroom in front so the player can stand + step in (clears air/natural only)
        for (int x = bx - 1; x <= bx + 2; x++) {
            for (int y = by + 1; y <= by + 4; y++) {
                for (int dz = 1; dz <= 2; dz++) {
                    clearIfNatural(dest, new BlockPos(x, y, bz + dz), air);
                }
            }
        }
        return new BlockPos(bx, by + 1, bz);
    }

    /** Place {@code state} only where the target is air or natural terrain — never overwrite a player build. */
    private static void place(ServerLevel w, BlockPos p, BlockState state) {
        if (replaceable(w.getBlockState(p))) {
            w.setBlockAndUpdate(p, state);
        }
    }

    /** Clear to air only where the target is a non-air natural block (leaves player builds intact). */
    private static void clearIfNatural(ServerLevel w, BlockPos p, BlockState air) {
        BlockState s = w.getBlockState(p);
        if (!s.isAir() && replaceable(s)) {
            w.setBlockAndUpdate(p, air);
        }
    }

    /** Air or common natural terrain the return pad may lay over — deliberately excludes anything a player
     *  is likely to have placed (wood, stone bricks, glass, ores stay untouched too, out of caution). */
    private static boolean replaceable(BlockState s) {
        if (s.isAir()) {
            return true;
        }
        return s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.GRAVEL) || s.is(Blocks.SAND)
                || s.is(Blocks.RED_SAND) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.TUFF) || s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.PACKED_MUD)
                || s.is(Blocks.SNOW) || s.is(Blocks.WATER) || s.is(Blocks.LAVA) || s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.MOSS_BLOCK)
                || s.is(Blocks.TERRACOTTA) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE);
    }

    // --- default portal (pre-light a known ruined portal at boot so it's green from the start) ---

    public static void onServerStarted(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.riftsEnabled || cfg.riftDefaultPortal == null || cfg.riftDefaultPortal.length < 3) {
            return;
        }
        ServerLevel over = server.overworld();
        int[] a = cfg.riftDefaultPortal;
        RiftStore store = RiftStore.get();
        for (int dy = 0; dy <= 3; dy++) {
            if (store.exists("minecraft:overworld", new BlockPos(a[0], a[1] + dy, a[2]))) {
                return; // already lit (permanent) — nothing to do
            }
        }
        over.getChunkAt(new BlockPos(a[0], a[1], a[2])); // ensure the column is generated
        // Admin-configured default: light it unconditionally (no wild/threshold/cap gate) so the starting
        // gateway is always active, as required.
        for (int dy = 0; dy <= 3; dy++) {
            BlockPos p = new BlockPos(a[0], a[1] + dy, a[2]);
            if (isOpen(over.getBlockState(p))) {
                establish(over, p, "server", "");
                Sanctuary.LOGGER.info("[sanctuary] default rift portal lit at {}", p);
                return;
            }
        }
        BlockPos forced = new BlockPos(a[0], a[1] + 1, a[2]);
        establish(over, forced, "server", "");
        Sanctuary.LOGGER.info("[sanctuary] default rift portal force-lit at {}", forced);
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
