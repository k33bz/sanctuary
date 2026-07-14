package com.k33bz.sanctuary.rift;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The rift mechanic: a {@link RiftAnchor} used on open ground OUTSIDE a sanctuary tears a persistent
 * rift to the {@code sanctuary:resource_world} gathering dimension; stepping onto a rift teleports
 * across, lazily opening a return rift on the far side the first time. Only creation is gated (you
 * must explore into the wild — {@link Sanctuary#blocksBeyondNearestAnchor} &gt; 0); travel is free.
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

    public static void register() {
        UseBlockCallback.EVENT.register(Rifts::onUseBlock);
    }

    // --- creation (Rift Anchor used on a block) ---

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!RiftAnchor.isRiftAnchor(held)) {
            return InteractionResult.PASS;
        }
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.riftsEnabled || !(level instanceof ServerLevel world)) {
            return InteractionResult.PASS;
        }
        // From here the player is deliberately using a Rift Anchor on a block: we own this
        // interaction and always cancel the default (skull placement), succeeding or hinting.
        BlockPos riftPos = hit.getBlockPos().relative(hit.getDirection());
        BlockPos floor = riftPos.below();
        if (!world.getBlockState(riftPos).canBeReplaced()
                || !world.getBlockState(riftPos.above()).canBeReplaced()
                || !world.getBlockState(floor).isFaceSturdy(world, floor, Direction.UP)) {
            sp.sendOverlayMessage(hint("There is no room to open a rift here."));
            return InteractionResult.SUCCESS;
        }
        String dimId = world.dimension().identifier().toString();
        if (dimId.equals(cfg.riftDimension)) {
            sp.sendOverlayMessage(hint("You cannot tear a rift from within the gathering world."));
            return InteractionResult.SUCCESS;
        }
        if (Sanctuary.blocksBeyondNearestAnchor(cfg, riftPos.getX() + 0.5, riftPos.getZ() + 0.5) <= 0.0) {
            sp.sendOverlayMessage(hint("The rift will not open — a sanctuary holds space together here."));
            return InteractionResult.SUCCESS;
        }
        RiftStore store = RiftStore.get();
        if (store.exists(dimId, riftPos)) {
            sp.sendOverlayMessage(hint("A rift already stands here."));
            return InteractionResult.SUCCESS;
        }
        store.create(dimId, riftPos, sp.getScoreboardName(), sp.getUUID().toString());
        if (!sp.isCreative()) {
            held.shrink(1);
        }
        burst(world.getServer(), riftPos);
        sp.sendOverlayMessage(Component.literal("A rift tears open. Step through to reach the gathering world.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return InteractionResult.SUCCESS;
    }

    // --- travel + ambience (throttled tick from Sanctuary's server-tick loop, ~1/s) ---

    public static void tick(MinecraftServer server, SanctuaryConfig cfg) {
        if (cfg == null || !cfg.riftsEnabled) {
            return;
        }
        RiftStore store = RiftStore.get();
        if (store.rifts.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isDeadOrDying()) {
                continue;
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
                if (horiz < 24.0) {
                    // gentle ambient shimmer at nearby rifts
                    run(server, String.format(Locale.ROOT,
                            "particle minecraft:reverse_portal %.2f %.2f %.2f 0.22 0.55 0.22 0.015 6",
                            r.x + 0.5, r.y + 0.4, r.z + 0.5));
                }
                if (standingOn == null && horiz <= cfg.riftTriggerRadius && Math.abs(py - r.y) < 1.6) {
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
            tx = from.linkX;
            ty = from.linkY;
            tz = from.linkZ;
            destRift = store.riftAt(destDim, new BlockPos(tx, ty, tz));
        } else {
            // First crossing from a fresh overworld anchor: open the return side now.
            destDim = cfg.riftDimension;
            ServerLevel dest = levelOf(server, destDim);
            if (dest == null) {
                player.sendSystemMessage(hint("The gathering world is unavailable."));
                return;
            }
            tx = from.x;
            tz = from.z;
            // Force-generate the destination column first: a fresh, ungenerated chunk reports its
            // heightmap as the world floor, which would drop the crossing player into the void.
            dest.getChunkAt(new BlockPos(tx, dest.getMinY() + 1, tz));
            ty = dest.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
            if (ty <= dest.getMinY() + 1) {
                ty = 128; // void-biome / still-empty guard — never strand the player at the floor
            }
            BlockPos landing = new BlockPos(tx, ty, tz);
            RiftStore.Rift back = store.riftAt(destDim, landing);
            if (back == null) {
                back = store.create(destDim, landing, from.owner, from.ownerId);
            }
            store.link(from, back);
            destRift = back;
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

    private static void burst(MinecraftServer server, BlockPos pos) {
        run(server, String.format(Locale.ROOT,
                "particle minecraft:reverse_portal %.2f %.2f %.2f 0.3 0.6 0.3 0.05 60",
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        run(server, String.format(Locale.ROOT,
                "playsound minecraft:block.beacon.activate block @a %.2f %.2f %.2f 1 0.7",
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    private static Component hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
