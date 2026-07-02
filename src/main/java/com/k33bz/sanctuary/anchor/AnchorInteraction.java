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

    /** Counts pulses so the dramatic sonic-boom accent fires only every few seconds. */
    private static int pulseCount = 0;

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

    /** A one-shot flash + sound the moment an anchor forms. */
    public static void playConversionEffect(MinecraftServer server, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.9;
        double cz = pos.getZ() + 0.5;
        run(server, String.format(Locale.ROOT, "particle minecraft:flash %.2f %.2f %.2f 0 0 0 0 3 force", cx, cy, cz));
        run(server, String.format(Locale.ROOT,
                "playsound minecraft:block.beacon.activate block @a %.2f %.2f %.2f 1 1.4", cx, cy, cz));
    }

    /** Pulsing particles at every anchor label (called on an interval), with a periodic sonic boom. */
    public static void pulseAnchors(MinecraftServer server) {
        if (AnchorState.get().anchors.isEmpty()) {
            return;
        }
        // The label floats anchorLabelHeight above the crystal; pulse at the crystal itself.
        double drop = (Sanctuary.CONFIG != null ? Sanctuary.CONFIG.anchorLabelHeight : 1.6) - 0.5;
        String sel = "@e[type=minecraft:text_display,tag=" + ANCHOR_TAG + "]";
        run(server, String.format(Locale.ROOT,
                "execute at %s run particle minecraft:sculk_soul ~ ~%.2f ~ 0.25 0.3 0.25 0.01 8", sel, -drop));
        run(server, String.format(Locale.ROOT,
                "execute at %s run particle minecraft:end_rod ~ ~%.2f ~ 0.35 0.25 0.35 0.005 6", sel, -drop));
        if (++pulseCount % 5 == 0) {
            run(server, String.format(Locale.ROOT,
                    "execute at %s run particle minecraft:sonic_boom ~ ~%.2f ~ 0 0 0 0 1 force", sel, -drop));
        }
    }

    /** Remove an anchor's display entities (on break). Also matches legacy egg-era display tags. */
    public static void removeDisplays(MinecraftServer server, BlockPos pos) {
        for (String tag : new String[]{ANCHOR_TAG, "sanctuary_anchor_egg"}) {
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
