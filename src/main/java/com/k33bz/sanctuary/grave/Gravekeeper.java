package com.k33bz.sanctuary.grave;

import net.minecraft.ChatFormatting;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.allay.Allay;
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
 * The Gravekeeper — a still cleric (NoAI) who tends each graveyard, and his allay couriers.
 * Right-click opens a dialog listing the player's loot-bearing graves that are NOT in this cemetery
 * (wild or resting in rival graveyards); paying the summon fee dispatches an allay that lifts off,
 * fades over the horizon, returns with the headstone, and settles it into the next open plot. Empty
 * graves are beneath his notice.
 *
 * <p>Couriers are spawned via the entity API so the animation holds a direct reference (the old
 * command-summon-then-search raced and returned null — the allay never flew and was orphaned).
 * {@link #sweepOrphanCouriers} clears any lingering tagged couriers at run start, at completion,
 * and on server start.
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

    /**
     * Summon the still cleric at a graveyard. ALL keepers are stationary (NoAI:1b): a wandering
     * villager constantly pathfinds toward the fence and looks broken, contradicting the "still
     * cleric" intent. A NoAI villager still fires the right-click use handler, so the summon dialog
     * opens as before. The keeper is Invulnerable, PersistenceRequired, and Silent.
     */
    public static void spawnKeeper(ServerLevel level, Graves.Yard yard) {
        Graves.run(level, String.format(Locale.ROOT,
                "kill @e[type=minecraft:villager,tag=%s,x=%d,y=%d,z=%d,distance=..%d]",
                KEEPER_TAG, yard.x, yard.y, yard.z, yard.radius + 8));
        Graves.run(level, String.format(Locale.ROOT,
                "summon minecraft:villager %d %d %d {Tags:[\"%s\"],NoAI:1b,Invulnerable:1b,"
                        + "PersistenceRequired:1b,Silent:1b,"
                        + "VillagerData:{profession:\"minecraft:cleric\",level:5,type:\"minecraft:swamp\"},"
                        + "CustomName:{text:\"Gravekeeper\",color:\"gold\"},CustomNameVisible:1b}",
                yard.x, yard.y + 1, yard.z, KEEPER_TAG));
    }

    /** Right-click on the keeper: list summonable graves (unfiltered). */
    public static void openDialog(ServerPlayer player, SanctuaryConfig cfg) {
        openDialog(player, cfg, null);
    }

    /**
     * List the keeper's summonable/holdable graves. When {@code filter} is non-blank, only estates
     * whose owner name contains it (case-insensitive) are shown — a static filter-and-reopen driven
     * by the "Search" text input. A big keeper hold can list dozens of estates; the filter keeps the
     * button wall navigable.
     */
    public static void openDialog(ServerPlayer player, SanctuaryConfig cfg, String filter) {
        Graves.Yard yard = Graves.yardNear(player, 32);
        if (yard == null) {
            return;
        }
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        boolean filtering = !needle.isEmpty();
        List<ActionButton> buttons = new ArrayList<>();
        String me = player.getUUID().toString();
        int hidden = 0;
        for (Graves.Grave grave : Graves.store().graves) {
            if (!grave.owner.equals(me) || grave.looted || grave.items.isEmpty()) {
                continue;
            }
            boolean here = grave.inGraveyard && yard.anchorId.equals(grave.graveyardAnchor);
            if (here) {
                continue;
            }
            if (filtering && !matchesOwner(grave, needle)) {
                hidden++;
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
            if (filtering && !matchesOwner(grave, needle)) {
                hidden++;
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
        // A "Show all" reset when filtered, so the player isn't trapped in a narrowed view.
        if (filtering) {
            buttons.add(new ActionButton(new CommonButtonData(Component.literal("Show all"), 220),
                    java.util.Optional.of(new StaticAction(new ClickEvent.RunCommand(
                            "sanctuarygrave search")))));
        }
        List<DialogBody> body = new ArrayList<>();
        String msg;
        if (buttons.isEmpty() && !filtering) {
            msg = "All your dead rest where they should.";
        } else if (filtering) {
            msg = String.format(Locale.ROOT, "Estates matching \"%s\" (%d hidden). Search again to refine.",
                    filter.trim(), hidden);
        } else {
            msg = "The dead can be moved, for a price. My allays are discreet.";
        }
        body.add(new PlainMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), 250));

        // A "Search" text input + Filter button re-opens the dialog narrowed to matching owners.
        List<net.minecraft.server.dialog.Input> inputs = List.of(
                com.k33bz.sanctuary.DialogInputs.text("query", "Search owner",
                        filtering ? filter.trim() : "", 24, 200));
        buttons.add(new ActionButton(new CommonButtonData(Component.literal("Filter by owner"), 220),
                com.k33bz.sanctuary.DialogInputs.command("sanctuarygrave search $(query)")));

        Dialog dialog = new MultiActionDialog(new CommonDialogData(
                Component.literal("The Gravekeeper"), java.util.Optional.empty(), true, false,
                DialogAction.CLOSE, body, inputs), buttons,
                java.util.Optional.of(new ActionButton(
                        new CommonButtonData(Component.literal("Leave"), 100), java.util.Optional.empty())),
                1);
        player.openDialog(Holder.direct(dialog));
    }

    /** Case-insensitive owner-name substring match for the ledger search. */
    private static boolean matchesOwner(Graves.Grave grave, String needle) {
        return grave.ownerName != null
                && grave.ownerName.toLowerCase(Locale.ROOT).contains(needle);
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

        // Clear any orphaned couriers from a prior (buggy) run before starting a fresh one.
        sweepOrphanCouriers(level.getServer());

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

    /**
     * Spawn the courier allay via the entity API and return a DIRECT reference — no command-summon-
     * then-search (the old approach: {@code summon} then re-find by tag, which raced and returned
     * null, so the allay never animated and was never discarded — it just stood orphaned in the
     * yard while only the grave "returned"). NoGravity keeps it from sinking between the animation's
     * teleports; it's also NoAI/Invulnerable/PersistenceRequired/Silent and carries {@link #COURIER_TAG}
     * so the orphan sweep can find any stragglers.
     */
    /** The allay entity type, resolved from the registry (the static EntityType.ALLAY field isn't
     *  on the mod's named compile classpath; the registry lookup is version-robust). */
    @SuppressWarnings("unchecked")
    private static final EntityType<Allay> ALLAY_TYPE = (EntityType<Allay>)
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getValue(net.minecraft.resources.Identifier.withDefaultNamespace("allay"));

    private static Mob spawnCourier(ServerLevel level, Vec3 at) {
        Allay allay = ALLAY_TYPE.create(level, EntitySpawnReason.COMMAND);
        if (allay == null) {
            return null;
        }
        allay.snapTo(at.x, at.y, at.z, 0.0f, 0.0f);
        allay.setNoAi(true);
        allay.setInvulnerable(true);
        allay.setPersistenceRequired();
        allay.setSilent(true);
        allay.setNoGravity(true);
        allay.addTag(COURIER_TAG);
        if (!level.addFreshEntity(allay)) {
            return null;
        }
        return allay;
    }

    /**
     * Kill any lingering courier allays (identified by {@link #COURIER_TAG}). Run at run start, at
     * completion, and on server start — the last clears orphans already standing in gmc101 yards
     * from the buggy pre-0.8.1 build the first time 0.8.1 boots.
     */
    public static void sweepOrphanCouriers(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Allay> couriers = level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Allay.class),
                    a -> !a.isRemoved() && a.entityTags().contains(COURIER_TAG));
            for (Allay allay : couriers) {
                // Don't reap an allay that's part of an active run (its animation still owns it).
                boolean inActiveRun = RUNS.stream().anyMatch(r -> r.allay == allay);
                if (!inActiveRun) {
                    allay.discard();
                }
            }
        }
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
                            com.k33bz.sanctuary.metrics.GraveEventLog.record("summoned", grave.id,
                                    grave.ownerName, grave.dim, grave.x, grave.y, grave.z,
                                    grave.items.size(), null, false);
                        }
                        Graves.run(run.level, String.format(Locale.ROOT,
                                "playsound minecraft:entity.allay.item_thrown neutral @a %.1f %.1f %.1f 1 0.8",
                                run.inTo.x, run.inTo.y, run.inTo.z));
                        if (run.allay != null) {
                            run.allay.discard();
                        }
                        run.phase = 3;
                        it.remove();
                        // Defensive: reap any courier that slipped its discard (never orphan one).
                        sweepOrphanCouriers(server);
                    }
                }
                default -> it.remove();
            }
        }
    }
}
