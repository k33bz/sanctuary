package com.k33bz.sanctuary.anchor;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import com.k33bz.sanctuary.Sanctuary;

import java.util.Locale;

/**
 * Right-click a beacon to open the sanctuary-anchor menu (sgui). From the menu you power it with a
 * dragon egg; the egg shows as a shrunk {@code block_display} floating in the beacon. Breaking the
 * beacon pops the egg back out (handled in {@link Sanctuary}).
 */
public final class AnchorInteraction {
    private AnchorInteraction() {
    }

    static final String EGG_TAG = "sanctuary_anchor_egg";

    /** minecraft-heads.com "Crystal" head skin — the floating object seated in a powered anchor. */
    private static final String CRYSTAL_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODMyMGIzZDQzYTRlY2EzMTZiM2MwZmJlZTU0N2FjZTJjNjBlZTcyM2NiZjMxYjgzZjQ5ZjhkNDM1NDgxMTdjNSJ9fX0=";

    public static void register() {
        UseBlockCallback.EVENT.register(AnchorInteraction::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (!level.getBlockState(pos).is(Blocks.BEACON)) {
            return InteractionResult.PASS;
        }
        // Leave normal beacons alone (vanilla UI). Only open the anchor menu when the player intends it:
        // holding a dragon egg (to power one), or the beacon is already an anchor.
        boolean isAnchor = AnchorState.get().isAnchor(pos);
        boolean holdingEgg = player.getItemInHand(hand).is(Items.DRAGON_EGG);
        if (!isAnchor && !holdingEgg) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            AnchorGui.open(serverPlayer, pos.immutable());
        }
        return InteractionResult.SUCCESS;
    }

    /** Spawn the shrunk dragon-egg display seated in the beacon. */
    static void spawnEggDisplay(MinecraftServer server, BlockPos pos) {
        double scale = Sanctuary.CONFIG != null ? Sanctuary.CONFIG.anchorEggScale : 0.75;
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5; // centre of the beacon block, so the crystal skins over it
        double cz = pos.getZ() + 0.5;
        // Skin the beacon AS a crystal: a player-head item_display sized to ~a full block, centred
        // on the beacon so the crystal appears to replace it. A skull renders ~half a block per
        // scale unit, so ~2.7x the configured "egg" scale (0.75 -> ~2.0) reads as a full block.
        double s = scale * 2.7;
        // A player-head item_display renders ~half a block low (its centre sits below the entity),
        // dropping it into the block. Lift by ~0.25x the render scale (~0.5 block here) to centre it.
        double lift = s * 0.25;
        run(server, String.format(Locale.ROOT,
                "summon minecraft:item_display %.3f %.3f %.3f "
                        + "{Tags:[\"%s\"],item:{id:\"minecraft:player_head\",count:1,"
                        + "components:{\"minecraft:profile\":{properties:[{name:\"textures\",value:\"%s\"}]}}},"
                        + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                        + "translation:[0f,%.3ff,0f],scale:[%.3ff,%.3ff,%.3ff]},"
                        + "brightness:{sky:15,block:15}}",
                cx, cy, cz, EGG_TAG, CRYSTAL_TEXTURE, lift, s, s, s));

        if (Sanctuary.CONFIG == null || Sanctuary.CONFIG.anchorShowLabel) {
            double labelY = pos.getY() + (Sanctuary.CONFIG != null ? Sanctuary.CONFIG.anchorLabelHeight : 1.6);
            // 26.x stores text_display#text as an NBT component, not a JSON string — the old
            // single-quoted JSON form rendered the raw braces. Use SNBT component form.
            run(server, String.format(Locale.ROOT,
                    "summon minecraft:text_display %.3f %.3f %.3f {Tags:[\"%s\"],billboard:\"center\","
                            + "see_through:1b,text:{text:\"Sanctuary Anchor\",color:\"light_purple\",bold:1b}}",
                    cx, labelY, cz, EGG_TAG));
        }
    }

    /** A one-shot flash + sound the moment a beacon is powered. */
    static void playConversionEffect(MinecraftServer server, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.9;
        double cz = pos.getZ() + 0.5;
        run(server, String.format(Locale.ROOT, "particle minecraft:flash %.2f %.2f %.2f 0 0 0 0 3 force", cx, cy, cz));
        run(server, String.format(Locale.ROOT,
                "playsound minecraft:block.beacon.activate block @a %.2f %.2f %.2f 1 1.4", cx, cy, cz));
    }

    /** Current spin of the seated crystals, advanced each pulse (degrees, shared across anchors). */
    private static double spinAngle = 0.0;
    /** Counts pulses so the dramatic sonic-boom accent fires only every few seconds. */
    private static int pulseCount = 0;

    /** Pulsing "focus" particles + a slow spin at every seated crystal (called on an interval). */
    public static void pulseAnchors(MinecraftServer server) {
        if (AnchorState.get().anchors.isEmpty()) {
            return;
        }
        String sel = "@e[type=minecraft:item_display,tag=" + EGG_TAG + "]";
        int dur = Sanctuary.CONFIG != null ? Sanctuary.CONFIG.regenIntervalTicks : 20;

        // Smooth spin: advance 30° per interval and interpolate the rotation over the whole interval,
        // so the crystal turns continuously (30°-per-step keeps the quaternion path always forward).
        spinAngle = (spinAngle + 30.0) % 360.0;
        double half = Math.toRadians(spinAngle) / 2.0;
        double qy = Math.sin(half);
        double qw = Math.cos(half);
        run(server, String.format(Locale.ROOT,
                "execute as %s run data merge entity @s "
                        + "{start_interpolation:0,interpolation_duration:%d,"
                        + "transformation:{left_rotation:[0f,%.5ff,0f,%.5ff]}}",
                sel, dur, qy, qw));

        // Particles: a sculk-soul pulse plus a gentle ring of end-rod sparks around the crystal.
        run(server, "execute at " + sel
                + " run particle minecraft:sculk_soul ~ ~0.3 ~ 0.25 0.3 0.25 0.01 8");
        run(server, "execute at " + sel
                + " run particle minecraft:end_rod ~ ~0.3 ~ 0.35 0.25 0.35 0.005 6");

        // Dramatic accent: a Warden sonic-boom shockwave every ~5 intervals.
        if (++pulseCount % 5 == 0) {
            run(server, "execute at " + sel
                    + " run particle minecraft:sonic_boom ~ ~0.4 ~ 0 0 0 0 1 force");
        }
    }

    /** Remove the seated egg display AND the floating label at a beacon (on break). */
    public static void removeEggDisplay(MinecraftServer server, BlockPos pos) {
        run(server, String.format(Locale.ROOT,
                "kill @e[tag=%s,x=%.2f,y=%.2f,z=%.2f,distance=..2.5]",
                EGG_TAG, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5));
    }

    private static void run(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }
}
