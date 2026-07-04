package com.k33bz.sanctuary.grave;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.StatBoards;
import com.k33bz.sanctuary.SurvivalLogic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The Gravekeeper — a still cleric who tends each graveyard, and his allay couriers. Right-click
 * opens a dialog listing the player's loot-bearing graves that are NOT in this cemetery (wild or
 * resting in rival graveyards); paying the summon fee dispatches an allay that lifts off, fades
 * over the horizon, returns with the headstone, and settles it into the next open plot. Empty
 * graves are beneath his notice.
 */
public final class Gravekeeper {
    private Gravekeeper() {
    }

    public static final String KEEPER_TAG = "sanctuary_gravekeeper";
    private static final String COURIER_TAG = "sanctuary_courier";

    /** Active courier theater runs, animated every server tick. */
    private static final List<Run> RUNS = new ArrayList<>();

    private static final class Run {
        ServerLevel level;
        String graveId;
        Graves.Yard yard;
        Vec3 from;
        Vec3 away;
        Mob allay;
        int phase;   // 0 = outbound, 1 = gone, 2 = inbound, 3 = done
        int tick;
        Vec3 inFrom;
        Vec3 inTo;
    }

    /** Summon the keeper at a freshly defined graveyard. */
    public static void spawnKeeper(ServerLevel level, Graves.Yard yard) {
        spawnKeeper(level, yard, false);
    }

    /** Ritual keepers wander (AI on, fenced in by the sweep); command keepers stand vigil. */
    public static void spawnKeeper(ServerLevel level, Graves.Yard yard, boolean wander) {
        Graves.run(level, String.format(Locale.ROOT,
                "kill @e[type=minecraft:villager,tag=%s,x=%d,y=%d,z=%d,distance=..%d]",
                KEEPER_TAG, yard.x, yard.y, yard.z, yard.radius + 8));
        Graves.run(level, String.format(Locale.ROOT,
                "summon minecraft:villager %d %d %d {Tags:[\"%s\"],NoAI:%db,Invulnerable:1b,"
                        + "PersistenceRequired:1b,Silent:1b,"
                        + "VillagerData:{profession:\"minecraft:cleric\",level:5,type:\"minecraft:swamp\"},"
                        + "CustomName:{text:\"Gravekeeper\",color:\"gold\"},CustomNameVisible:1b}",
                yard.x, yard.y + 1, yard.z, KEEPER_TAG, wander ? 0 : 1));
    }

    /** Right-click on the keeper: list summonable graves. */
    public static void openDialog(ServerPlayer player, SanctuaryConfig cfg) {
        Graves.Yard yard = Graves.yardNear(player, 32);
        if (yard == null) {
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        String me = player.getUUID().toString();
        for (Graves.Grave grave : Graves.store().graves) {
            if (!grave.owner.equals(me) || grave.looted || grave.items.isEmpty()) {
                continue;
            }
            boolean here = grave.inGraveyard && yard.anchorId.equals(grave.graveyardAnchor);
            if (here) {
                continue;
            }
            int fee = SurvivalLogic.respawnCostLevels(player.experienceLevel,
                    cfg.graveSummonFeeFraction, 0, 1);
            String where = grave.inGraveyard ? "another graveyard"
                    : String.format(Locale.ROOT, "%d, %d", (int) grave.x, (int) grave.z);
            buttons.add(new ActionButton(new CommonButtonData(Component.literal(
                    String.format(Locale.ROOT, "Summon grave at %s — %d level%s",
                            where, fee, fee == 1 ? "" : "s")), 220),
                    java.util.Optional.of(new StaticAction(new ClickEvent.RunCommand(
                            "sanctuarygrave summon " + grave.id)))));
        }
        // The keeper's hold: your evicted estates (fee) and, once expired, anyone's.
        for (Graves.Grave grave : Graves.store().graves) {
            if (!grave.heldByKeeper || grave.items.isEmpty()
                    || !yard.anchorId.equals(grave.graveyardAnchor)) {
                continue;
            }
            boolean mine = grave.owner.equals(me);
            if (mine) {
                int fee = SurvivalLogic.respawnCostLevels(player.experienceLevel,
                        cfg.graveClaimFeeFraction, 0, 0);
                buttons.add(new ActionButton(new CommonButtonData(Component.literal(
                        "Reclaim held remains -- " + fee + " level" + (fee == 1 ? "" : "s")), 220),
                        java.util.Optional.of(new StaticAction(new ClickEvent.RunCommand(
                                "sanctuarygrave claimheld " + grave.id)))));
            } else if (Graves.isPublic(grave)) {
                buttons.add(new ActionButton(new CommonButtonData(Component.literal(
                        "Claim expired estate of " + grave.ownerName), 220),
                        java.util.Optional.of(new StaticAction(new ClickEvent.RunCommand(
                                "sanctuarygrave claimheld " + grave.id)))));
            }
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(new PlainMessage(Component.literal(buttons.isEmpty()
                        ? "All your dead rest where they should."
                        : "The dead can be moved, for a price. My allays are discreet.")
                .withStyle(ChatFormatting.GRAY), 250));
        Dialog dialog = new MultiActionDialog(new CommonDialogData(
                Component.literal("The Gravekeeper"), java.util.Optional.empty(), true, false,
                DialogAction.CLOSE, body, List.of()), buttons,
                java.util.Optional.of(new ActionButton(
                        new CommonButtonData(Component.literal("Leave"), 100), java.util.Optional.empty())),
                1);
        player.openDialog(Holder.direct(dialog));
    }

    /** Dialog button backend: charge the fee and dispatch the courier. */
    public static int summon(ServerPlayer player, String graveId) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        Graves.Grave grave = Graves.byId(graveId);
        Graves.Yard yard = Graves.yardNear(player, 32);
        if (cfg == null || grave == null || yard == null || grave.looted || grave.items.isEmpty()
                || !grave.owner.equals(player.getUUID().toString())) {
            return 0;
        }
        int fee = SurvivalLogic.respawnCostLevels(player.experienceLevel, cfg.graveSummonFeeFraction, 0, 1);
        if (player.experienceLevel < fee) {
            player.sendSystemMessage(Component.literal("The keeper requires " + fee + " levels.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        player.giveExperienceLevels(-fee);
        StatBoards.addScore(player, "sanct_toll", fee);
        ServerLevel level = (ServerLevel) player.level();

        Run run = new Run();
        run.level = level;
        run.graveId = graveId;
        run.yard = yard;
        run.from = new Vec3(yard.x + 0.5, yard.y + 1.5, yard.z + 0.5);
        double angle = player.getRandom().nextDouble() * Math.PI * 2;
        run.away = run.from.add(Math.cos(angle) * 40, 25, Math.sin(angle) * 40);
        run.allay = spawnCourier(level, run.from);
        RUNS.add(run);
        player.sendSystemMessage(Component.literal("The keeper whispers. Something small and blue obeys.")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return 1;
    }

    private static Mob spawnCourier(ServerLevel level, Vec3 at) {
        Graves.run(level, String.format(Locale.ROOT,
                "summon minecraft:allay %.2f %.2f %.2f {Tags:[\"%s\"],NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}",
                at.x, at.y, at.z, COURIER_TAG));
        List<Mob> found = level.getEntitiesOfClass(Mob.class,
                new AABB(BlockPos.containing(at.x, at.y, at.z)).inflate(2),
                m -> m.entityTags().contains(COURIER_TAG));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Per-tick courier animation. Cheap: only active runs are touched. */
    public static void tickCouriers(MinecraftServer server) {
        if (RUNS.isEmpty()) {
            return;
        }
        Iterator<Run> it = RUNS.iterator();
        while (it.hasNext()) {
            Run run = it.next();
            run.tick++;
            switch (run.phase) {
                case 0 -> { // outbound: keeper -> sky
                    if (run.allay != null) {
                        double t = Math.min(1.0, run.tick / 80.0);
                        Vec3 p = run.from.lerp(run.away, t);
                        run.allay.teleportTo(p.x, p.y, p.z);
                    }
                    if (run.tick >= 80) {
                        if (run.allay != null) {
                            run.allay.discard();
                        }
                        run.phase = 1;
                        run.tick = 0;
                    }
                }
                case 1 -> { // gone fetching
                    if (run.tick >= 100) {
                        run.inFrom = new Vec3(run.yard.x + 0.5, run.yard.y + 22, run.yard.z + 0.5);
                        run.inTo = new Vec3(run.yard.x + 0.5, run.yard.y + 1.5, run.yard.z + 0.5);
                        run.allay = spawnCourier(run.level, run.inFrom);
                        run.phase = 2;
                        run.tick = 0;
                    }
                }
                case 2 -> { // inbound: sky -> yard center, then the drop
                    if (run.allay != null) {
                        double t = Math.min(1.0, run.tick / 80.0);
                        Vec3 p = run.inFrom.lerp(run.inTo, t);
                        run.allay.teleportTo(p.x, p.y, p.z);
                    }
                    if (run.tick >= 80) {
                        Graves.Grave grave = Graves.byId(run.graveId);
                        if (grave != null && !grave.looted) {
                            if (grave.inGraveyard) {
                                grave.inGraveyard = false; // free its old plot claim
                            }
                            Graves.moveToYard(server, grave, run.yard);
                            Graves.save();
                        }
                        Graves.run(run.level, String.format(Locale.ROOT,
                                "playsound minecraft:entity.allay.item_thrown neutral @a %.1f %.1f %.1f 1 0.8",
                                run.inTo.x, run.inTo.y, run.inTo.z));
                        if (run.allay != null) {
                            run.allay.discard();
                        }
                        run.phase = 3;
                        it.remove();
                    }
                }
                default -> it.remove();
            }
        }
    }
}
