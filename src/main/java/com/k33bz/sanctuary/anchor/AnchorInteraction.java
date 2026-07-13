package com.k33bz.sanctuary.anchor;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import com.k33bz.sanctuary.Sanctuary;

import java.util.Locale;

/**
 * Visuals for a formed sanctuary anchor (a placed {@link SanctuaryCrystal} head): the floating
 * label, the activation flash, and the recurring particle pulse + sonic-boom accent. All driven
 * with vanilla display entities and particles, so vanilla clients render everything.
 */
public final class AnchorInteraction {
    private AnchorInteraction() {
    }

    /** Tag carried by an anchor's display entities (label), used to find and clean them up. */
    static final String ANCHOR_TAG = "sanctuary_anchor_display";
    /** Tag for the spinning crystal shell shown over ACTIVE anchors only. */
    static final String SPIN_TAG = "sanctuary_anchor_spin";

    /** Counts pulses so the dramatic sonic-boom accent fires only every few seconds. */
    private static int pulseCount = 0;
    /** Current shell rotation, advanced each pulse (degrees, shared across anchors). */
    private static double spinAngle = 0.0;

    /** Spawn the floating label above a freshly formed anchor. */
    public static void spawnAnchorDisplays(MinecraftServer server, BlockPos pos) {
        if (Sanctuary.CONFIG != null && !Sanctuary.CONFIG.anchorShowLabel) {
            return;
        }
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        double labelY = pos.getY() + (Sanctuary.CONFIG != null ? Sanctuary.CONFIG.anchorLabelHeight : 1.6);
        run(server, String.format(Locale.ROOT,
                "summon minecraft:text_display %.3f %.3f %.3f {Tags:[\"%s\"],billboard:\"center\","
                        + "see_through:1b,text:{text:\"Sanctuary Anchor\",color:\"light_purple\",bold:1b}}",
                cx, labelY, cz, ANCHOR_TAG));
    }

    /**
     * Spawn the spinning crystal shell over an active anchor: an item_display of the crystal head,
     * scaled just past the static placed head so it fully encloses it — the anchor visibly spins
     * while alive. Removed on dormancy, so a still anchor means a dead one. Idempotent.
     */
    public static void spawnSpinDisplay(MinecraftServer server, BlockPos pos) {
        removeSpinDisplay(server, pos);
        double s = 1.2; // placed heads are a 0.5 cube; a 1.2-scale shell (~0.6) encloses it
        run(server, String.format(Locale.ROOT,
                "summon minecraft:item_display %.3f %.3f %.3f "
                        + "{Tags:[\"%s\"],item:{id:\"minecraft:player_head\",count:1,"
                        + "components:{\"minecraft:profile\":{properties:[{name:\"textures\",value:\"%s\"}]}}},"
                        + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                        + "translation:[0f,%.3ff,0f],scale:[%.3ff,%.3ff,%.3ff]},"
                        + "brightness:{sky:15,block:15}}",
                pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5,
                SPIN_TAG, SanctuaryCrystal.CRYSTAL_TEXTURE, s * 0.25, s, s, s));
    }

    /** Remove the spinning shell at an anchor (dormancy or break). */
    public static void removeSpinDisplay(MinecraftServer server, BlockPos pos) {
        run(server, String.format(Locale.ROOT,
                "kill @e[type=minecraft:item_display,tag=%s,x=%.2f,y=%.2f,z=%.2f,distance=..2.0]",
                SPIN_TAG, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    /** A one-shot flash + sound the moment an anchor forms. */
    public static void playConversionEffect(MinecraftServer server, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.9;
        double cz = pos.getZ() + 0.5;
        run(server, String.format(Locale.ROOT, "particle minecraft:flash %.2f %.2f %.2f 0 0 0 0 3 force", cx, cy, cz));
        run(server, String.format(Locale.ROOT,
                "playsound minecraft:block.beacon.activate block @a %.2f %.2f %.2f 1 1.4", cx, cy, cz));
    }

    /**
     * Animate active anchors (called on an interval): the crystal shell spins continuously, and a
     * sonic-boom shockwave fires every ~5 intervals. Everything targets the SPIN shell, which only
     * exists on ACTIVE anchors — dormant ones sit dark and still, no extra checks needed.
     */
    public static void pulseAnchors(MinecraftServer server) {
        AnchorState state = AnchorState.get();
        if (state == null || state.anchors.isEmpty()) {
            return;
        }
        String sel = "@e[type=minecraft:item_display,tag=" + SPIN_TAG + "]";
        int dur = Sanctuary.CONFIG != null ? Sanctuary.CONFIG.regenIntervalTicks : 20;

        // Smooth spin: +30° per interval, interpolated across the whole interval.
        spinAngle = (spinAngle + 30.0) % 360.0;
        double half = Math.toRadians(spinAngle) / 2.0;
        run(server, String.format(Locale.ROOT,
                "execute as %s run data merge entity @s "
                        + "{start_interpolation:0,interpolation_duration:%d,"
                        + "transformation:{left_rotation:[0f,%.5ff,0f,%.5ff]}}",
                sel, dur, Math.sin(half), Math.cos(half)));

        if (++pulseCount % 5 == 0) {
            run(server, "execute at " + sel + " run particle minecraft:sonic_boom ~ ~0.3 ~ 0 0 0 0 1 force");
        }
    }

    /** Remove an anchor's display entities (on break). Also matches legacy egg-era display tags. */
    public static void removeDisplays(MinecraftServer server, BlockPos pos) {
        for (String tag : new String[]{ANCHOR_TAG, SPIN_TAG, "sanctuary_anchor_egg"}) {
            run(server, String.format(Locale.ROOT,
                    "kill @e[tag=%s,x=%.2f,y=%.2f,z=%.2f,distance=..3.0]",
                    tag, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5));
        }
    }

    private static void run(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }
}
