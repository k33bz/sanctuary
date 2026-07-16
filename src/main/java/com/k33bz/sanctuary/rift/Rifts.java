package com.k33bz.sanctuary.rift;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rift travel + ambience. The gateways themselves are the world's ruined portals, established by
 * {@link RiftPortals}; this class owns only the crossing: stepping into a registered green portal
 * teleports to the {@code sanctuary:resource_world} gathering dimension, lazily building a matching
 * green return portal on the far side the first time.
 *
 * <p>Teleport uses {@code execute in <dim> run tp} rather than a mapping-specific {@code teleportTo}
 * overload, so it survives Minecraft version churn. The player name is interpolated into that
 * command, so it is validated against the vanilla name charset first (defense-in-depth: offline-mode
 * clients can send arbitrary names) — a name that fails is refused rather than trusted.
 */
public final class Rifts {
    private Rifts() {
    }

    /** Vanilla username charset. Anything outside it never reaches a command string. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** Per-player travel cooldown (game-time tick until which travel is suppressed). */
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();

    /**
     * Per-player latch: the id of the rift they most recently ARRIVED on. While they stand on it we
     * don't re-teleport (else you'd bounce forever); cleared once they step off every rift.
     */
    private static final Map<UUID, String> ARRIVED_ON = new HashMap<>();

    /** Drop per-player travel state on disconnect (wired from Sanctuary's DISCONNECT handler) so these
     *  maps don't accumulate one entry per unique UUID for the server's whole uptime. */
    public static void forget(UUID id) {
        COOLDOWN.remove(id);
        ARRIVED_ON.remove(id);
        RiftPortals.forget(id);
    }

    // Rift creation is no longer a player action — the world's ruined portals ARE the gateways
    // (see RiftPortals.checkRuined, driven from tick() below). This class now owns only travel + ambience.

    // --- travel + ambience (throttled tick from Sanctuary's server-tick loop, ~1/s) ---

    public static void tick(MinecraftServer server, SanctuaryConfig cfg) {
        if (cfg == null || !cfg.riftsEnabled) {
            return;
        }
        RiftStore store = RiftStore.get();
        long now = server.overworld().getGameTime();
        java.util.Set<String> shimmered = new java.util.HashSet<>(); // render each rift's particle plane once/tick
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isDeadOrDying()) {
                continue;
            }
            // Ruined portals auto-activate on contact: this may register a fresh green-membrane gateway,
            // which the travel scan below then carries the player across.
            if (player.level() instanceof ServerLevel psl) {
                RiftPortals.checkRuined(psl, player, cfg);
            }
            String dim = player.level().dimension().identifier().toString();
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            RiftStore.Rift standingOn = null;
            for (RiftStore.Rift r : store.rifts) {
                if (!r.dim.equals(dim)) {
                    continue;
                }
                double dx = (r.x + 0.5) - px;
                double dz = (r.z + 0.5) - pz;
                double horiz = Math.sqrt(dx * dx + dz * dz);
                if (horiz < 24.0 && shimmered.add(r.id)) {
                    shimmer(server, r); // green particle plane across a portal's opening; a puff at a point rift
                }
                // A ruined-portal rift triggers when you step into its opening (a taller, wider box than a
                // point rift — you walk into the particle plane from the front rather than stand on a cell).
                double radius = r.portal ? cfg.riftPortalTriggerRadius : cfg.riftTriggerRadius;
                double dyLimit = r.portal ? (r.h + 0.5) : 1.6;
                if (standingOn == null && horiz <= radius && Math.abs(py - r.y) < dyLimit) {
                    standingOn = r;
                }
            }
            UUID id = player.getUUID();
            if (standingOn == null) {
                ARRIVED_ON.remove(id); // stepped off everything → arm the next crossing
                continue;
            }
            if (standingOn.id.equals(ARRIVED_ON.get(id))) {
                continue; // still on the rift we arrived on; don't bounce
            }
            Long until = COOLDOWN.get(id);
            if (until != null && now < until) {
                continue;
            }
            travel(server, cfg, player, standingOn, now);
        }
    }

    private static void travel(MinecraftServer server, SanctuaryConfig cfg, ServerPlayer player,
                               RiftStore.Rift from, long now) {
        // Re-entry lockout: while a weekly reset is in flight, refuse any crossing that would enter
        // or leave the gathering world (an entry mid-clear invites a chunk-load race; the evacuator
        // handles anyone already inside).
        if (RiftReset.travelLocked()
                && (cfg.riftDimension.equals(from.dim) || !from.linked
                        || cfg.riftDimension.equals(from.linkDim))) {
            player.sendSystemMessage(hint("The gathering world is being remade — try again shortly."));
            return;
        }
        String name = player.getScoreboardName();
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            Sanctuary.LOGGER.warn("[sanctuary] refusing rift travel for a non-vanilla player name");
            return;
        }
        RiftStore store = RiftStore.get();
        String destDim;
        int tx;
        int ty;
        int tz;
        RiftStore.Rift destRift;
        if (from.linked) {
            destDim = from.linkDim;
            ServerLevel dest = levelOf(server, destDim);
            if (dest == null) {
                player.sendSystemMessage(hint("The rift's far side has faded."));
                return;
            }
            destRift = store.riftAt(destDim, new BlockPos(from.linkX, from.linkY, from.linkZ));
            // Land BESIDE the far portal's opening, never dead-centre inside its solid green membrane.
            BlockPos land = (destRift != null && destRift.portal)
                    ? RiftPortals.safeLandingNear(dest, from.linkX, from.linkY, from.linkZ, destRift.h)
                    : new BlockPos(from.linkX, from.linkY, from.linkZ);
            tx = land.getX();
            ty = land.getY();
            tz = land.getZ();
        } else {
            // First crossing from a fresh overworld portal: open + link the return side now.
            destDim = cfg.riftDimension;
            ServerLevel dest = levelOf(server, destDim);
            if (dest == null) {
                player.sendSystemMessage(hint("The gathering world is unavailable."));
                return;
            }
            // Force-generate the destination column first: a fresh, ungenerated chunk reports its
            // heightmap as the world floor, which would drop the crossing player into the void.
            dest.getChunkAt(new BlockPos(from.x, dest.getMinY() + 1, from.z));
            int groundY = dest.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, from.x, from.z);
            if (groundY <= dest.getMinY() + 1) {
                groundY = 128; // void-biome / still-empty guard — never strand the player at the floor
            }
            // The return rift is registered at the membrane interior; the player lands 2 blocks out on the
            // standing platform (so arrival doesn't latch onto the rift) and safeLandingNear handles every
            // later re-entry so nobody materialises inside the glass.
            int baseZ = from.z;
            BlockPos anchor = new BlockPos(from.x, groundY + 1, baseZ);
            RiftStore.Rift back = store.riftAt(destDim, anchor);
            // Rare: two overworld portals share an x,z (stacked). Nudge the whole return outward so the
            // second crossing builds its own gateway instead of stealing the first portal's link.
            int guard = 0;
            while (back != null && back.linked
                    && !(back.linkDim.equals(from.dim) && back.linkX == from.x
                            && back.linkY == from.y && back.linkZ == from.z)
                    && guard++ < 8) {
                baseZ += 3;
                anchor = new BlockPos(from.x, groundY + 1, baseZ);
                back = store.riftAt(destDim, anchor);
            }
            if (back == null) {
                BlockPos base = new BlockPos(from.x, groundY, baseZ);
                // Force-load every chunk the return-pad footprint spans so nothing is written into an
                // ungenerated neighbour chunk mid-crossing.
                for (int cx = base.getX() - 1; cx <= base.getX() + 2; cx += 3) {
                    for (int cz = base.getZ(); cz <= base.getZ() + 2; cz += 2) {
                        dest.getChunkAt(new BlockPos(cx, groundY, cz));
                    }
                }
                int[] cells = RiftPortals.buildReturnPortal(dest, base);
                back = store.create(destDim, anchor, from.owner, from.ownerId);
                back.portal = true;
                back.h = 3;
                back.membrane = cells;
            }
            store.link(from, back);
            destRift = back;
            // land on the platform in FRONT of the return membrane (+Z), not inside the glass
            tx = from.x;
            ty = groundY + 1;
            tz = baseZ + 2;
        }
        run(server, String.format(Locale.ROOT, "execute in %s run tp %s %.3f %.3f %.3f",
                destDim, name, tx + 0.5, (double) ty, tz + 0.5));
        run(server, String.format(Locale.ROOT,
                "execute in %s positioned %.2f %.2f %.2f run playsound minecraft:block.beacon.power_select "
                        + "block @a[distance=..12] ~ ~ ~ 1 1.3",
                destDim, tx + 0.5, ty + 0.5, tz + 0.5));
        COOLDOWN.put(player.getUUID(), now + Math.max(20L, cfg.riftTravelCooldownTicks));
        ARRIVED_ON.put(player.getUUID(), destRift != null ? destRift.id : "");
    }

    // --- helpers ---

    private static ServerLevel levelOf(MinecraftServer server, String dimId) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimId)));
        } catch (Exception e) {
            return null;
        }
    }

    private static Component hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    /** Ambient shimmer: for a ruined-portal rift, a green particle plane across every opening cell (the
     *  membrane, rendered by particles — no block is placed); for a point rift, a single reverse-portal puff. */
    private static void shimmer(MinecraftServer server, RiftStore.Rift r) {
        // Every particle MUST be dispatched with "execute in <dim>": run() builds a command source from
        // server.createCommandSourceStack(), whose level is always the overworld, and ParticleCommand
        // resolves its target level from the source. Without the override, a gathering-world rift's plane
        // would be spawned in the OVERWORLD at those coordinates — invisible to the player standing at the
        // portal, and leaking stray green sparks into the overworld column above the source portal.
        if (r.portal && r.membrane != null && r.membrane.length >= 3) {
            for (int i = 0; i + 2 < r.membrane.length; i += 3) {
                run(server, String.format(Locale.ROOT,
                        "execute in %s run particle minecraft:composter %.2f %.2f %.2f 0.24 0.24 0.24 0.004 3",
                        r.dim, r.membrane[i] + 0.5, r.membrane[i + 1] + 0.5, r.membrane[i + 2] + 0.5));
            }
        } else {
            run(server, String.format(Locale.ROOT,
                    r.portal ? "execute in %s run particle minecraft:composter %.2f %.2f %.2f 0.32 %.2f 0.32 0.01 8"
                             : "execute in %s run particle minecraft:reverse_portal %.2f %.2f %.2f 0.22 0.55 0.22 0.015 6",
                    r.dim, r.x + 0.5, r.y + (r.portal ? 1.0 : 0.4), r.z + 0.5,
                    r.portal ? Math.max(0.4, r.h * 0.35) : 0.55));
        }
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
