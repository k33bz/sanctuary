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
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.monster.Enemy;
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
 * The Gravekeeper — a still cleric (NoAI) who tends each graveyard, and his allay couriers.
 * Right-click opens a dialog listing the player's loot-bearing graves that are NOT in this cemetery
 * (wild or resting in rival graveyards); paying the summon fee dispatches an allay that lifts off,
 * fades over the horizon, returns with the headstone, and settles it into the next open plot. Empty
 * graves are beneath his notice.
 *
 * <p>He also SMITES (0.8.3): {@link #smiteSweep} strikes hostile mobs inside the yard (bounds +
 * margin, within a Y band) and REMOVES them with {@code discard()} — no death, so no loot, no XP,
 * no transforms (an anti-farm guard). Under open sky the strike is a visual-only lightning bolt +
 * thunder; under a roof it's a dark soul/sculk burst (no lightning through a ceiling); both end in a
 * black smoke cloud. He turns to face what he strikes ({@link #facePoint}) but stays stationary,
 * grounded, and NoAI.
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

    /**
     * Horizontal radius around a yard center within which a keeper is considered to be tending it —
     * used BOTH for the self-heal's exactly-one dedup and for the smite's keeper-presence check, so
     * a keeper anywhere near the yard (not just at an exact spot) counts. Generous on purpose: a
     * keeper is a keeper. Kept larger than the max sane smite margin.
     */
    public static final int KEEPER_REACH = 24;

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
        // Idempotent + the SINGLE source of the "exactly one keeper per yard" invariant (0.8.3.3):
        // DISCARD every tagged keeper-entity within KEEPER_REACH of the yard center (full Y column)
        // FIRST, then summon exactly one. Uses direct discard() — NOT the /kill command — so removing
        // strays fires NO death event and broadcasts NO "Gravekeeper was killed" message (the chat
        // spam the self-heal caused when it culled duplicates). discard() is silent: no death, no
        // loot, no message. NOT type-scoped to villager — a keeper struck by NATURAL lightning BEFORE
        // the 0.8.3.1 immunity became a WITCH that KEPT the tag; those lingering tagged witches must
        // be purged too. A full-Y AABB is independent of any command source's Y.
        net.minecraft.world.phys.AABB reachBox = new net.minecraft.world.phys.AABB(
                yard.x - KEEPER_REACH, level.getMinY(), yard.z - KEEPER_REACH,
                yard.x + 1 + KEEPER_REACH, level.getMaxY(), yard.z + 1 + KEEPER_REACH);
        for (Mob stray : level.getEntitiesOfClass(Mob.class, reachBox,
                m -> m.entityTags().contains(KEEPER_TAG))) {
            stray.discard(); // silent removal — no death broadcast, no loot
        }
        // NoGravity so the keeper holds a gentle hover just above the ground (the per-tick bob in
        // tickKeeperHover drives its Y). Summoned AT the hover base (yard.y + BASE_LIFT), not high up.
        Graves.run(level, String.format(Locale.ROOT,
                "summon minecraft:villager %d %.2f %d {Tags:[\"%s\"],NoAI:1b,Invulnerable:1b,"
                        + "PersistenceRequired:1b,Silent:1b,NoGravity:1b,"
                        + "VillagerData:{profession:\"minecraft:cleric\",level:5,type:\"minecraft:swamp\"},"
                        + "CustomName:{text:\"Gravekeeper\",color:\"gold\"},CustomNameVisible:1b}",
                yard.x, yard.y + KeeperHover.BASE_LIFT, yard.z, KEEPER_TAG));
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

    /** Lightning-bolt entity type, from the registry (same reason as {@link #ALLAY_TYPE}). */
    @SuppressWarnings("unchecked")
    private static final EntityType<LightningBolt> LIGHTNING_TYPE = (EntityType<LightningBolt>)
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getValue(net.minecraft.resources.Identifier.withDefaultNamespace("lightning_bolt"));

    /** Max hostiles zapped per keeper per sweep, so a horde isn't a blinding storm. */
    private static final int MAX_ZAPS_PER_SWEEP = 4;

    /** Smite counter, throttles the sweep to graveyardSmiteIntervalTicks. */
    private static int smiteTickCounter = 0;

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

    /** Per-keeper patrol state, keyed by entity id (0.8.4). Pruned as keepers vanish (see below). */
    private static final java.util.Map<Integer, KeeperPatrol.State> PATROL_STATES =
            new java.util.HashMap<>();

    /**
     * Per-tick keeper drift + hover-bob. As of 0.8.4 the keeper SLOWLY patrols its yard (issue #5):
     * {@link KeeperPatrol} eases it toward a wander target — preferring grave positions — at
     * {@code keeperPatrolSpeed} blocks/tick (a small fraction of walk speed, capped so it never reads
     * as brisk), lingering in place a few seconds on arrival before picking the next target, and never
     * leaving the fence ({@code keeperWanderMargin} inside the bounds). The keeper stays NoAI +
     * NoGravity — this is our OWN server-side drift, NOT vanilla villager goals (which would
     * bed-seek/work/panic). The gentle {@link KeeperHover} bob is layered on top of the horizontal
     * drift, and the keeper faces its travel direction (or the point it lingers at). {@code setPos}
     * per tick produces small position deltas the entity tracker broadcasts, so vanilla clients
     * interpolate the glide smoothly. When {@code keeperPatrol} is off, the keeper holds the 0.8.3.3
     * stationary hover. Cheap: only loaded yards with a keeper present are touched.
     */
    public static void tickKeeperHover(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.gravesEnabled) {
            return;
        }
        if (Graves.store().yards.isEmpty()) {
            return;
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Graves.Yard yard : Graves.store().yards) {
            ServerLevel level = Graves.levelOf(server, yard.dim);
            net.minecraft.core.BlockPos center = new net.minecraft.core.BlockPos(yard.x, yard.y, yard.z);
            if (level == null || !level.isLoaded(center)) {
                continue;
            }
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    yard.x - KEEPER_REACH, level.getMinY(), yard.z - KEEPER_REACH,
                    yard.x + 1 + KEEPER_REACH, level.getMaxY(), yard.z + 1 + KEEPER_REACH);
            List<Mob> keepers = level.getEntitiesOfClass(Mob.class, box,
                    m -> m.entityTags().contains(KEEPER_TAG)
                            && m instanceof net.minecraft.world.entity.npc.villager.Villager);
            long t = level.getGameTime();

            // Patrol bounds: the fence for a consecrated yard, else a small square around center for
            // an auto/hold-only yard (no fence) so the drift still stays put and small.
            boolean hasBounds = GraveyardSmite.hasBounds(yard.bMinX, yard.bMaxX);
            double minX, maxX, minZ, maxZ;
            if (hasBounds) {
                minX = yard.bMinX; maxX = yard.bMaxX + 1;
                minZ = yard.bMinZ; maxZ = yard.bMaxZ + 1;
            } else {
                int r = Math.max(2, cfg.graveyardDefaultRadius);
                minX = yard.x - r; maxX = yard.x + 1 + r;
                minZ = yard.z - r; maxZ = yard.z + 1 + r;
            }
            List<KeeperPatrol.Target> graveTargets = yardGraveTargets(yard);

            for (Mob keeper : keepers) {
                seen.add(keeper.getId());
                double phase = (keeper.getId() % 16) * (Math.PI / 8.0);
                double bobY = KeeperHover.hoverY(yard.y, t, phase);

                if (cfg.keeperPatrol) {
                    KeeperPatrol.State st = PATROL_STATES.computeIfAbsent(
                            keeper.getId(), id -> new KeeperPatrol.State());
                    KeeperPatrol.Rng rng = rngFor(level, keeper.getId());
                    KeeperPatrol.Move mv = KeeperPatrol.advance(st, keeper.getX(), keeper.getZ(),
                            graveTargets, minX, maxX, minZ, maxZ,
                            cfg.keeperWanderMargin, cfg.keeperPatrolSpeed,
                            cfg.keeperLingerTicksMin, cfg.keeperLingerTicksMax, rng);
                    keeper.setPos(mv.x, bobY, mv.z);
                    keeper.setYRot(mv.yaw);
                    keeper.setYHeadRot(mv.yaw);
                    keeper.setYBodyRot(mv.yaw);
                } else {
                    // Stationary 0.8.3.3 hover: bob in place, no horizontal drift.
                    keeper.setPos(keeper.getX(), bobY, keeper.getZ());
                }
                keeper.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); // we drive position directly
            }
        }
        // Prune patrol state for keepers no longer present (despawn / chunk unload / self-heal reset),
        // so the map can't grow unbounded across a long uptime.
        if (!PATROL_STATES.isEmpty()) {
            PATROL_STATES.keySet().retainAll(seen);
        }
    }

    /**
     * Grave positions resting in {@code yard}, as patrol targets — the keeper prefers to drift between
     * these. Keeper-held (off-lawn) graves are excluded; only graves physically in this yard count.
     */
    private static List<KeeperPatrol.Target> yardGraveTargets(Graves.Yard yard) {
        List<KeeperPatrol.Target> out = new ArrayList<>();
        for (Graves.Grave g : Graves.store().graves) {
            if (g.inGraveyard && !g.heldByKeeper && yard.anchorId.equals(g.graveyardAnchor)) {
                out.add(new KeeperPatrol.Target(g.x, g.z));
            }
        }
        return out;
    }

    /** Adapt the level's shared RandomSource to the pure patrol's injected RNG (id kept for salt). */
    private static KeeperPatrol.Rng rngFor(ServerLevel level, int id) {
        final net.minecraft.util.RandomSource src = level.getRandom();
        return new KeeperPatrol.Rng() {
            @Override
            public double nextDouble() {
                return src.nextDouble();
            }

            @Override
            public int nextInt(int bound) {
                return bound <= 0 ? 0 : src.nextInt(bound);
            }
        };
    }

    /**
     * The Gravekeeper's smite (0.8.3): every {@code graveyardSmiteIntervalTicks}, each keeper calls
     * down VISUAL-ONLY lightning on hostile mobs inside its yard (bounds + margin, within a Y band)
     * and kills them with direct lightning damage. Visual-only so the wooden fence never catches
     * fire and no mob is transformed (creeper->charged, pig->piglin, villager->witch, etc.). Never
     * touches players, passive/neutral mobs, couriers, or the keepers themselves. Capped per sweep.
     * The keeper rotates (server-side yaw only, no AI) to face the mob it zaps.
     */
    public static void smiteSweep(MinecraftServer server) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.gravesEnabled || !cfg.graveyardSmiteEnabled) {
            return;
        }
        int interval = Math.max(1, cfg.graveyardSmiteIntervalTicks);
        if (++smiteTickCounter < interval) {
            return;
        }
        smiteTickCounter = 0;
        if (Graves.store().yards.isEmpty()) {
            return;
        }
        int margin = Math.max(0, cfg.graveyardSmiteMargin);
        for (Graves.Yard yard : Graves.store().yards) {
            ServerLevel level = Graves.levelOf(server, yard.dim);
            if (level == null || !level.isLoaded(new net.minecraft.core.BlockPos(yard.x, yard.y, yard.z))) {
                continue;
            }
            smiteYard(level, yard, margin);
        }
    }

    private static void smiteYard(ServerLevel level, Graves.Yard yard, int margin) {
        boolean hasBounds = GraveyardSmite.hasBounds(yard.bMinX, yard.bMaxX);

        // Keeper-presence check: a keeper must be tending the grounds, but "tending" is generous — a
        // keeper ANYWHERE within KEEPER_REACH of the yard center (full Y column) counts, whether the
        // mod spawned it or it was hand-placed. (Bug B: the old check reused the exact smite zone, so
        // a keeper a few blocks past the margin — or a hand-spawned one at a slightly-off spot — was
        // not recognised and the grounds went unguarded.)
        AABB keeperBox = new AABB(
                yard.x - KEEPER_REACH, level.getMinY(), yard.z - KEEPER_REACH,
                yard.x + 1 + KEEPER_REACH, level.getMaxY(), yard.z + 1 + KEEPER_REACH);
        List<Mob> keepers = level.getEntitiesOfClass(Mob.class, keeperBox,
                m -> m.entityTags().contains(KEEPER_TAG));
        if (keepers.isEmpty()) {
            return;
        }

        // Target-smite zone: the tight fence bounds (auto/radius-0 yards: a square around center)
        // expanded by the margin, within the Y band. Only mobs HERE are struck.
        AABB search;
        if (hasBounds) {
            search = new AABB(yard.bMinX - margin, yard.y - GraveyardSmite.Y_BAND, yard.bMinZ - margin,
                    yard.bMaxX + 1 + margin, yard.y + GraveyardSmite.Y_BAND, yard.bMaxZ + 1 + margin);
        } else {
            search = new AABB(yard.x - margin, yard.y - GraveyardSmite.Y_BAND, yard.z - margin,
                    yard.x + 1 + margin, yard.y + GraveyardSmite.Y_BAND, yard.z + 1 + margin);
        }
        // Hostile targets in the zone: monsters/Enemy only; never players (not Mob), passives,
        // couriers, or the keepers. The AABB pre-filters; inZone() is the exact predicate.
        List<Mob> targets = level.getEntitiesOfClass(Mob.class, search, m ->
                m instanceof Enemy
                        && !m.entityTags().contains(KEEPER_TAG)
                        && !m.entityTags().contains(COURIER_TAG)
                        && !m.isRemoved()
                        && GraveyardSmite.inZone(m.getX(), m.getY(), m.getZ(), hasBounds,
                                yard.bMinX, yard.bMaxX, yard.bMinZ, yard.bMaxZ,
                                yard.x, yard.z, yard.y, margin, GraveyardSmite.Y_BAND));
        if (targets.isEmpty()) {
            return;
        }
        Mob keeper = keepers.get(0);
        int zapped = 0;
        for (Mob target : targets) {
            if (zapped >= MAX_ZAPS_PER_SWEEP) {
                break; // survivors die next sweep — no blinding storm
            }
            facePoint(keeper, target.getX(), target.getZ());
            smite(level, target);
            zapped++;
        }
    }

    /**
     * Strike a hostile mob: a sky-dependent effect, then remove it with {@code discard()}.
     *
     * <p><b>discard(), not damage/kill</b> — no death event, so NO loot roll, NO XP orbs, NO
     * transforms. This is the anti-farm guard: the keeper can't be used to harvest Withers/Wardens.
     * The strike + black cloud sell the kill visually.
     *
     * <p><b>Sky-dependent</b> — under OPEN SKY, a visual-only lightning bolt (no fire, no transform)
     * plus explicit thunder/impact sounds (visual-only bolts don't reliably emit sound). Under a
     * ROOF/underground, no lightning (it would clip through the ceiling) — instead a dark soul/sculk
     * burst. Both paths finish with a black smoke cloud as the mob vanishes.
     */
    private static void smite(ServerLevel level, Mob target) {
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        boolean openSky = seesSky(level, target);

        if (openSky) {
            if (LIGHTNING_TYPE != null) {
                LightningBolt bolt = LIGHTNING_TYPE.create(level, EntitySpawnReason.TRIGGERED);
                if (bolt != null) {
                    bolt.snapTo(new Vec3(x, y, z));
                    bolt.setVisualOnly(true); // flash only: no fire, no mob transforms
                    level.addFreshEntity(bolt);
                }
            }
            // Visual-only bolts don't reliably emit sound — play thunder + impact ourselves.
            run(level, fx("playsound minecraft:entity.lightning_bolt.thunder weather @a", x, y, z, "1 1"));
            run(level, fx("playsound minecraft:entity.lightning_bolt.impact weather @a", x, y, z, "1 1"));
        } else {
            // Roofed/underground: a dark soul/sculk burst instead of lightning.
            run(level, fx("particle minecraft:soul_fire_flame", x, y + 0.5, z, "0.3 0.6 0.3 0.02 30 force"));
            run(level, fx("particle minecraft:soul", x, y + 0.5, z, "0.3 0.6 0.3 0.02 20 force"));
            run(level, fx("particle minecraft:sculk_soul", x, y + 0.8, z, "0.3 0.6 0.3 0.02 20 force"));
            run(level, fx("playsound minecraft:block.sculk_shrieker.shriek block @a", x, y, z, "1 1"));
            run(level, fx("playsound minecraft:entity.warden.nearby_closer hostile @a", x, y, z, "0.7 1.2"));
        }

        // The "vanishes in a black cloud" beat (both paths), at the moment of removal.
        run(level, fx("particle minecraft:large_smoke", x, y + 0.5, z, "0.3 0.5 0.3 0.01 25 force"));
        run(level, fx("particle minecraft:campfire_cosy_smoke", x, y + 0.8, z, "0.2 0.4 0.2 0.005 6 force"));
        run(level, fx("particle minecraft:smoke", x, y + 0.4, z, "0.3 0.4 0.3 0.02 15 force"));

        // Remove silently — no loot, no XP, no transform.
        target.discard();
    }

    /** Whether the mob has open sky above it (nothing blocking up to the heightmap). */
    private static boolean seesSky(ServerLevel level, Mob target) {
        net.minecraft.core.BlockPos pos = target.blockPosition();
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                pos.getX(), pos.getZ());
        return pos.getY() >= surface;
    }

    /** Build a suppressed command string with the mob's position + trailing args. */
    private static String fx(String prefix, double x, double y, double z, String tail) {
        return String.format(Locale.ROOT, "%s %.2f %.2f %.2f %s", prefix, x, y, z, tail);
    }

    private static void run(ServerLevel level, String command) {
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** Rotate a NoAI keeper to face a horizontal point (server-side yaw only; no pathfinding/AI). */
    private static void facePoint(Mob keeper, double tx, double tz) {
        double dx = tx - keeper.getX();
        double dz = tz - keeper.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        keeper.setYRot(yaw);
        keeper.setYHeadRot(yaw);
        keeper.setYBodyRot(yaw);
    }
}
