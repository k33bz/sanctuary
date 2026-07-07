package com.k33bz.sanctuary;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Op-only {@code /sanctuary} command tree for live, in-game tuning.
 *
 * <p>Knob getters/setters read {@link Sanctuary#CONFIG} fresh on every call, and the tick handler
 * and damage mixin also read it live — so {@code set}/{@code toggle} take effect on the very next
 * tick/hit with no restart. {@code save} writes the live values to disk; {@code reload} reads them back.
 */
public final class SanctuaryCommands {
    private SanctuaryCommands() {
    }

    private record NumKnob(DoubleSupplier get, DoubleConsumer set, double min, double max) {
    }

    private record BoolKnob(BooleanSupplier get, Consumer<Boolean> set) {
    }

    private static final Map<String, NumKnob> NUM = new LinkedHashMap<>();
    private static final Map<String, BoolKnob> BOOL = new LinkedHashMap<>();

    private static SanctuaryConfig cfg() {
        return Sanctuary.CONFIG;
    }

    private static void num(String key, DoubleSupplier g, DoubleConsumer s, double min, double max) {
        NUM.put(key, new NumKnob(g, s, min, max));
    }

    private static void bool(String key, BooleanSupplier g, Consumer<Boolean> s) {
        BOOL.put(key, new BoolKnob(g, s));
    }

    static {
        num("regen.intervalTicks", () -> cfg().regenIntervalTicks, v -> cfg().regenIntervalTicks = (int) Math.round(v), 1, 1200);
        num("regen.healPerInterval", () -> cfg().regenHealPerInterval, v -> cfg().regenHealPerInterval = (float) v, 0, 100);
        num("regen.xpPerHealth", () -> cfg().regenXpPerHealth, v -> cfg().regenXpPerHealth = (float) v, 0, 1000);
        num("armor.perLevel", () -> cfg().armorPerLevel, v -> cfg().armorPerLevel = v, 0, 30);
        num("armor.max", () -> cfg().armorMax, v -> cfg().armorMax = v, 0, 30);
        num("hearts.hpPerMilestone", () -> cfg().hpPerMilestone, v -> cfg().hpPerMilestone = v, 0, 20);
        num("shield.maxFraction", () -> cfg().shieldMaxFraction, v -> cfg().shieldMaxFraction = v, 0, 10);
        num("oxygen.perLevel", () -> cfg().oxygenPerLevel, v -> cfg().oxygenPerLevel = v, 0, 1000);
        num("oxygen.max", () -> cfg().oxygenMax, v -> cfg().oxygenMax = v, 0, 100000);
        num("shield.cooldownBase", () -> cfg().shieldRegenCooldownBase, v -> cfg().shieldRegenCooldownBase = v, 0, 600);
        num("shield.cooldownPerMilestone", () -> cfg().shieldRegenCooldownPerMilestone, v -> cfg().shieldRegenCooldownPerMilestone = v, 0, 60);
        num("shield.cooldownMin", () -> cfg().shieldRegenCooldownMin, v -> cfg().shieldRegenCooldownMin = v, 0, 600);
        num("mobscaling.healthPerBlock", () -> cfg().mobScaling.healthPerBlock, v -> cfg().mobScaling.healthPerBlock = v, 0, 1);
        num("mobscaling.damagePerBlock", () -> cfg().mobScaling.damagePerBlock, v -> cfg().mobScaling.damagePerBlock = v, 0, 1);
        num("mobscaling.speedPerBlock", () -> cfg().mobScaling.speedPerBlock, v -> cfg().mobScaling.speedPerBlock = v, 0, 1);
        num("mobscaling.healthMax", () -> cfg().mobScaling.healthMaxMultiplier, v -> cfg().mobScaling.healthMaxMultiplier = v, 1, 200);
        num("mobscaling.damageMax", () -> cfg().mobScaling.damageMaxMultiplier, v -> cfg().mobScaling.damageMaxMultiplier = v, 1, 500);
        num("mobscaling.speedMax", () -> cfg().mobScaling.speedMaxMultiplier, v -> cfg().mobScaling.speedMaxMultiplier = v, 1, 10);
        num("mobscaling.xpPerBlock", () -> cfg().mobScaling.xpPerBlock, v -> cfg().mobScaling.xpPerBlock = v, 0, 1);
        num("mobscaling.xpMax", () -> cfg().mobScaling.xpMaxMultiplier, v -> cfg().mobScaling.xpMaxMultiplier = v, 1, 200);
        num("essence.wardenChance", () -> cfg().wardenEssenceChance, v -> cfg().wardenEssenceChance = v, 0, 1);
        num("essence.nightmareChance", () -> cfg().nightmareEssenceChance, v -> cfg().nightmareEssenceChance = v, 0, 1);
        bool("beaconLavaConsumed", () -> cfg().beaconLavaConsumed, b -> cfg().beaconLavaConsumed = b);
        num("anchor.labelHeight", () -> cfg().anchorLabelHeight, v -> cfg().anchorLabelHeight = v, 0, 4);
        num("anchor.startHours", () -> cfg().anchorStartHours, v -> cfg().anchorStartHours = v, 0, 100000);
        num("anchor.hoursPerEmerald", () -> cfg().anchorHoursPerEmerald, v -> cfg().anchorHoursPerEmerald = v, 0, 10000);
        num("anchor.hoursPerEmeraldBlock", () -> cfg().anchorHoursPerEmeraldBlock, v -> cfg().anchorHoursPerEmeraldBlock = v, 0, 100000);
        num("anchor.hoursPerEgg", () -> cfg().anchorHoursPerEgg, v -> cfg().anchorHoursPerEgg = v, 0, 1000000);
        num("anchor.maxFuelHours", () -> cfg().anchorMaxFuelHours, v -> cfg().anchorMaxFuelHours = v, 1, 1000000);
        num("anchor.flanClaimRadius", () -> cfg().flanClaimRadius, v -> cfg().flanClaimRadius = (int) Math.round(v), 1, 128);
        num("anchor.minSpacing", () -> cfg().anchorMinSpacing, v -> cfg().anchorMinSpacing = v, 0, 100000);
        num("anchor.capBase", () -> cfg().anchorCapBase, v -> cfg().anchorCapBase = (int) Math.round(v), 1, 100);
        num("anchor.capMax", () -> cfg().anchorCapMax, v -> cfg().anchorCapMax = (int) Math.round(v), 1, 100);
        num("deathKeep.base", () -> cfg().deathKeepBase, v -> cfg().deathKeepBase = v, 0, 1);
        num("deathKeep.perMilestone", () -> cfg().deathKeepPerMilestone, v -> cfg().deathKeepPerMilestone = v, 0, 1);
        num("deathKeep.max", () -> cfg().deathKeepMax, v -> cfg().deathKeepMax = v, 0, 1);
        num("mobscaling.particleRange", () -> cfg().mobScaling.particleRange, v -> cfg().mobScaling.particleRange = v, 0, 256);
        num("lethalSave.levelsPerDamage", () -> cfg().lethalSaveLevelsPerDamage, v -> cfg().lethalSaveLevelsPerDamage = (float) v, 0, 100);
        num("lethalSave.minLevels", () -> cfg().lethalSaveMinLevels, v -> cfg().lethalSaveMinLevels = (int) Math.round(v), 0, 1000);
        num("lethalSave.reviveHealth", () -> cfg().lethalSaveReviveHealth, v -> cfg().lethalSaveReviveHealth = (float) v, 1, 1024);
        num("danger.difficultyWeight", () -> cfg().danger.difficultyWeight, v -> cfg().danger.difficultyWeight = (float) v, 0, 100);
        num("danger.perDayWeight", () -> cfg().danger.perDayWeight, v -> cfg().danger.perDayWeight = v, 0, 100);
        num("danger.perBlockWeight", () -> cfg().danger.perBlockWeight, v -> cfg().danger.perBlockWeight = v, 0, 100);
        num("danger.maxMultiplier", () -> cfg().danger.maxMultiplier, v -> cfg().danger.maxMultiplier = (float) v, 1, 1000);
        num("respawn.bedCost", () -> cfg().respawnBedCostFraction, v -> cfg().respawnBedCostFraction = v, 0, 1);
        num("respawn.backCost", () -> cfg().respawnBackCostFraction, v -> cfg().respawnBackCostFraction = v, 0, 1);
        num("respawn.minCostLevels", () -> cfg().respawnMinCostLevels, v -> cfg().respawnMinCostLevels = (int) Math.round(v), 0, 1000);
        num("respawn.escalationPerDeath", () -> cfg().respawnEscalationPerDeath, v -> cfg().respawnEscalationPerDeath = v, 0, 10);
        num("afk.minutes", () -> cfg().afkMinutes, v -> cfg().afkMinutes = v, 1, 120);
        num("restless.minInsomniaDays", () -> cfg().restlessMinInsomniaDays, v -> cfg().restlessMinInsomniaDays = (int) Math.round(v), 1, 30);
        num("restless.maxCount", () -> cfg().restlessMaxCount, v -> cfg().restlessMaxCount = (int) Math.round(v), 1, 10);
        num("grave.driftHours", () -> cfg().graveDriftHours, v -> cfg().graveDriftHours = v, 0, 10000);
        num("grave.publicHours", () -> cfg().gravePublicHours, v -> cfg().gravePublicHours = v, 0, 10000);
        num("grave.claimFee", () -> cfg().graveClaimFeeFraction, v -> cfg().graveClaimFeeFraction = v, 0, 1);
        num("grave.summonFee", () -> cfg().graveSummonFeeFraction, v -> cfg().graveSummonFeeFraction = v, 0, 1);
        num("grave.memorialDecayDays", () -> cfg().graveMemorialDecayDays, v -> cfg().graveMemorialDecayDays = v, 0, 100000);
        bool("graveyard.smite", () -> cfg().graveyardSmiteEnabled, b -> cfg().graveyardSmiteEnabled = b);
        num("graveyard.smiteMargin", () -> (double) cfg().graveyardSmiteMargin,
                v -> cfg().graveyardSmiteMargin = (int) Math.round(v), 0, 128);
        num("graveyard.smiteInterval", () -> (double) cfg().graveyardSmiteIntervalTicks,
                v -> cfg().graveyardSmiteIntervalTicks = (int) Math.round(v), 1, 12000);
        num("respawn.escalationDecayPer10Min", () -> cfg().respawnEscalationDecayPer10Min, v -> cfg().respawnEscalationDecayPer10Min = v, 0, 10);

        bool("regen", () -> cfg().regenEnabled, b -> cfg().regenEnabled = b);
        bool("armor", () -> cfg().armorEnabled, b -> cfg().armorEnabled = b);
        bool("hearts", () -> cfg().heartsEnabled, b -> cfg().heartsEnabled = b);
        bool("shield", () -> cfg().shieldEnabled, b -> cfg().shieldEnabled = b);
        bool("breath", () -> cfg().breathEnabled, b -> cfg().breathEnabled = b);
        bool("mobscaling", () -> cfg().mobScaling.enabled, b -> cfg().mobScaling.enabled = b);
        bool("anchorlabel", () -> cfg().anchorShowLabel, b -> cfg().anchorShowLabel = b);
        bool("lethalSave", () -> cfg().lethalSaveEnabled, b -> cfg().lethalSaveEnabled = b);
        bool("danger", () -> cfg().danger.enabled, b -> cfg().danger.enabled = b);
        bool("anchorUpkeep", () -> cfg().anchorUpkeepEnabled, b -> cfg().anchorUpkeepEnabled = b);
        bool("deathKeep", () -> cfg().deathKeepEnabled, b -> cfg().deathKeepEnabled = b);
        bool("killEventLog", () -> cfg().killEventLogEnabled, b -> cfg().killEventLogEnabled = b);
        bool("dialogMenu", () -> cfg().anchorDialogMenu, b -> cfg().anchorDialogMenu = b);
        bool("sanctuarySpawnSuppression", () -> cfg().suppressHostileSpawnsInSanctuary,
                b -> cfg().suppressHostileSpawnsInSanctuary = b);
        bool("flanIntegration", () -> cfg().flanIntegration, b -> cfg().flanIntegration = b);
        bool("respawnChoice", () -> cfg().respawnChoiceEnabled, b -> cfg().respawnChoiceEnabled = b);
        bool("creeperTerrainProtection", () -> cfg().creeperTerrainProtection, b -> cfg().creeperTerrainProtection = b);
        bool("endermanClone", () -> cfg().endermanCloneNotSteal, b -> cfg().endermanCloneNotSteal = b);
        bool("afkTag", () -> cfg().afkTagEnabled, b -> cfg().afkTagEnabled = b);
        bool("restless", () -> cfg().restlessEnabled, b -> cfg().restlessEnabled = b);
        bool("graves", () -> cfg().gravesEnabled, b -> cfg().gravesEnabled = b);
        bool("wildEssence", () -> cfg().wildEssenceEnabled, b -> cfg().wildEssenceEnabled = b);
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(build());
            // Player-level (permission 0): backs the native-dialog menu's feed buttons. Acts only
            // on an anchor within 6 blocks, consuming fuel from the player's own inventory.
            dispatcher.register(Commands.literal("sanctuaryfeed")
                    .then(Commands.argument("type", StringArgumentType.word())
                            .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 64))
                                    .executes(safe(SanctuaryCommands::dialogFeed)))));
            // Player-level (permission 0): backs the respawn-choice dialog's paid options.
            dispatcher.register(Commands.literal("sanctuaryrespawn")
                    .then(Commands.argument("choice", StringArgumentType.word())
                            .executes(safe(ctx -> RespawnChoice.applyChoice(
                                    ctx.getSource().getPlayerOrException(),
                                    StringArgumentType.getString(ctx, "choice"))))));
            // Player-level (permission 0): backs the respawn picker's "wake at chosen sanctuary"
            // single-option — a free redirect to another of the player's own active anchors.
            dispatcher.register(Commands.literal("sanctuarywake")
                    .then(Commands.argument("anchorId", StringArgumentType.word())
                            .executes(safe(ctx -> RespawnChoice.wakeAt(
                                    ctx.getSource().getPlayerOrException(),
                                    StringArgumentType.getString(ctx, "anchorId"))))));
            // Player-level (permission 0): the anchor-rename dialog. Bare form opens the text-input
            // dialog for the nearest owned anchor within 6 blocks; "set <name>" applies it. The
            // owner-or-creative check is enforced server-side here, not just in the dialog.
            dispatcher.register(Commands.literal("sanctuaryrename")
                    .executes(safe(ctx -> anchorRename(ctx, null)))
                    .then(Commands.literal("set")
                            .then(Commands.argument("name", StringArgumentType.greedyString())
                                    .executes(safe(ctx -> anchorRename(ctx,
                                            StringArgumentType.getString(ctx, "name")))))));
            // Ops: wall-mounted holographic leaderboards for any scoreboard objective.
            dispatcher.register(Commands.literal("sanctuaryboard")
                    .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("add")
                            .then(Commands.argument("objective", StringArgumentType.word())
                                    .executes(safe(ctx -> StatBoards.create(
                                            ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "objective"), null)))
                                    .then(Commands.argument("title", StringArgumentType.greedyString())
                                            .executes(safe(ctx -> StatBoards.create(
                                                    ctx.getSource().getPlayerOrException(),
                                                    StringArgumentType.getString(ctx, "objective"),
                                                    StringArgumentType.getString(ctx, "title")))))))
                    .then(Commands.literal("remove")
                            .executes(safe(ctx -> StatBoards.remove(ctx.getSource().getPlayerOrException())))));
            // Player-level: backs the Gravekeeper dialog's summon buttons.
            dispatcher.register(Commands.literal("sanctuarygrave")
                    // Player-level (permission 0): backs the headstone right-click claim/rob.
                    // Bare = the nearest grave within 6 blocks; with an id = that grave. Either
                    // way it runs the SAME Graves.tryClaim path the UseEntityCallback uses, so all
                    // ownership/public/keeper-held/looted/fee/cooldown rules apply unchanged.
                    .then(Commands.literal("claim")
                            .executes(safe(ctx -> graveClaim(ctx, null)))
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .executes(safe(ctx -> graveClaim(ctx,
                                            StringArgumentType.getString(ctx, "id"))))))
                    .then(Commands.literal("claimheld")
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .executes(safe(ctx -> com.k33bz.sanctuary.grave.Graves.claimHeld(
                                            ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "id"), cfg())))))
                    .then(Commands.literal("summon")
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .executes(safe(ctx -> com.k33bz.sanctuary.grave.Gravekeeper.summon(
                                            ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "id"))))))
                    // UI-facing grave search backing the Gravekeeper "Search" button (and manual
                    // use): bare = list every grave + position; greedy arg = filter by owner name.
                    // Returns TEXT RESULTS (never "unrecognized command"). Permission 0 so the
                    // dialog button — which runs as the clicking player — can invoke it.
                    .then(Commands.literal("search")
                            .executes(safe(ctx -> graveSearch(ctx, null)))
                            .then(Commands.argument("query", StringArgumentType.greedyString())
                                    .executes(safe(ctx -> graveSearch(ctx,
                                            StringArgumentType.getString(ctx, "query"))))))
                    // Ops (level 2): force-clear graves into the nearest keeper hold (loot safe).
                    // Default = wild graves only; "includegraveyard" also does in-yard graves.
                    .then(Commands.literal("clearworld")
                            .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(safe(ctx -> clearWorld(ctx, false)))
                            .then(Commands.literal("includegraveyard")
                                    .executes(safe(ctx -> clearWorld(ctx, true)))))
                    // Ops (level 2): debug — age a grave (shift its diedAtMs back N real days) to
                    // drive the epitaph-fuzz + flora aging without waiting. Bare = the nearest
                    // grave; with an id = that grave.
                    .then(Commands.literal("setage")
                            .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.argument("days", DoubleArgumentType.doubleArg(0, 100000))
                                    .executes(safe(ctx -> graveSetAge(ctx, null)))
                                    .then(Commands.argument("id", StringArgumentType.word())
                                            .executes(safe(ctx -> graveSetAge(ctx,
                                                    StringArgumentType.getString(ctx, "id")))))))
                    // Ops (level 2): debug — run the default-keeper check on demand (normally fires
                    // on SERVER_STARTED). Lets tests drive it without a server restart.
                    .then(Commands.literal("defaultkeeper")
                            .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(safe(ctx -> {
                                com.k33bz.sanctuary.grave.Graves.ensureDefaultKeeper(
                                        ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "Default-keeper check run."), true);
                                return 1;
                            })))
                    // Ops (level 2): debug — run the wild-flora migration on demand (normally fires
                    // on SERVER_STARTED). Lets tests drive the 0.8.2.1 cleanup without a restart.
                    .then(Commands.literal("migrateflora")
                            .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(safe(ctx -> {
                                com.k33bz.sanctuary.grave.Graves.migrateWildFlora(
                                        ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "Wild-flora migration run."), true);
                                return 1;
                            })))
                    // Ops (level 2): read-only diagnostics — list every consecrated yard (bounds +
                    // center + anchor), its grave count, and the live keeper count + entity positions
                    // per yard, plus the total world grave count. Invaluable for diagnosing keeper
                    // over-spawn and locating graves.
                    .then(Commands.literal("list")
                            .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(safe(SanctuaryCommands::graveList)))
                    // Ops (level 2): debug — run the keeper self-heal on demand (normally on
                    // SERVER_STARTED + periodically). Lets tests drive it without a restart.
                    .then(Commands.literal("healkeepers")
                            .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .executes(safe(ctx -> {
                                com.k33bz.sanctuary.grave.Graves.ensureKeepers(
                                        ctx.getSource().getServer());
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "Keeper self-heal run."), true);
                                return 1;
                            }))));
            // Ops: world-age danger pressure readout / re-zero (persisted epoch; external
            // dashboards watch danger.epochTick in the config to lap leaderboard seasons).
            dispatcher.register(Commands.literal("sanctuarydanger")
                    .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("status").executes(safe(ctx -> {
                        long epoch = cfg().danger.epochTick;
                        long now = ctx.getSource().getServer().overworld().getGameTime();
                        double days = Math.max(0, now - epoch) / 24000.0;
                        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                java.util.Locale.ROOT,
                                "World-age pressure: %.1f in-game days since epoch (tick %d).",
                                days, epoch)), false);
                        return 1;
                    })))
                    .then(Commands.literal("reset").executes(safe(ctx -> {
                        cfg().danger.epochTick = ctx.getSource().getServer().overworld().getGameTime();
                        cfg().save();
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "Danger epoch re-zeroed and saved."), true);
                        return 1;
                    }))));
            // Ops: define the graveyard for the sanctuary you stand in.
            dispatcher.register(Commands.literal("sanctuarygraveyard")
                    .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("set")
                            .executes(safe(ctx -> graveyardSet(ctx, 0)))
                            .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 32))
                                    .executes(safe(ctx -> graveyardSet(ctx,
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"))))))
                    .then(Commands.literal("remove")
                            .executes(safe(SanctuaryCommands::graveyardRemove)))
                    // Console-runnable consecration (no player entity needed — RCON/automation).
                    // Floods the pen from <pos> exactly like the survival ritual, so the yard gets
                    // real fence bounds; [owner] assigns ownership by offline-UUID name derivation.
                    .then(Commands.literal("consecrate")
                            .then(Commands.argument("pos",
                                            net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                    .executes(safe(ctx -> graveyardConsecrate(ctx, null)))
                                    .then(Commands.argument("owner", StringArgumentType.word())
                                            .executes(safe(ctx -> graveyardConsecrate(ctx,
                                                    StringArgumentType.getString(ctx, "owner"))))))));
        });
    }

    /**
     * Consecrate a graveyard from the console: flood-fill the fence pen around {@code pos} (the
     * ground level at the yard's center — the scan origin sits two blocks above, where the ritual
     * skull would be) and raise the keeper. Replaces any existing yard on the same anchor, retiring
     * its keeper. No effigy is consumed; this is the admin/automation twin of the survival ritual.
     */
    private static int graveyardConsecrate(CommandContext<CommandSourceStack> ctx, String ownerName)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos ground =
                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos");
        SanctuaryConfig cfg = cfg();
        if (Sanctuary.blocksBeyondNearestAnchor(cfg, ground.getX() + 0.5, ground.getZ() + 0.5) > 0) {
            ctx.getSource().sendFailure(Component.literal("Graveyards belong inside a sanctuary."));
            return 0;
        }
        String anchorId = "config";
        double bestSq = Double.MAX_VALUE;
        for (var a : com.k33bz.sanctuary.anchor.AnchorState.get().anchors) {
            double dx = a.x - ground.getX(), dz = a.z - ground.getZ();
            if (dx * dx + dz * dz < bestSq) {
                bestSq = dx * dx + dz * dz;
                anchorId = a.id != null ? a.id : "legacy";
            }
        }
        var scan = com.k33bz.sanctuary.grave.GraveyardRitual.scanPen(level, ground.above(2), cfg);
        if (!scan.ok()) {
            ctx.getSource().sendFailure(Component.literal(scan.error()));
            return 0;
        }
        var store = com.k33bz.sanctuary.grave.Graves.store();
        String fAnchor = anchorId;
        store.yards.stream().filter(y -> y.anchorId.equals(fAnchor)).findFirst().ifPresent(old ->
                com.k33bz.sanctuary.grave.Graves.run(level, "kill @e[type=minecraft:villager,tag="
                        + com.k33bz.sanctuary.grave.Gravekeeper.KEEPER_TAG
                        + ",x=" + old.x + ",y=" + old.y + ",z=" + old.z + ",distance=..6]"));
        store.yards.removeIf(y -> y.anchorId.equals(fAnchor));
        var yard = new com.k33bz.sanctuary.grave.Graves.Yard();
        yard.anchorId = anchorId;
        yard.owner = ownerName == null ? null : com.k33bz.sanctuary.grave.OfflineUuid.of(ownerName).toString();
        yard.dim = level.dimension().identifier().toString();
        yard.x = (scan.minX() + scan.maxX()) / 2;
        yard.y = ground.getY();
        yard.z = (scan.minZ() + scan.maxZ()) / 2;
        yard.radius = scan.radius();
        yard.bMinX = scan.minX();
        yard.bMaxX = scan.maxX();
        yard.bMinZ = scan.minZ();
        yard.bMaxZ = scan.maxZ();
        store.yards.add(yard);
        com.k33bz.sanctuary.grave.Graves.save();
        com.k33bz.sanctuary.grave.Gravekeeper.spawnKeeper(level, yard);
        int spanX = scan.spanX(), spanZ = scan.spanZ();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Graveyard consecrated %dx%d (fence-bounded%s). The Gravekeeper rises.",
                spanX, spanZ, ownerName == null ? "" : ", owner " + ownerName)), true);
        return 1;
    }

    private static int graveyardSet(CommandContext<CommandSourceStack> ctx, int radius)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        SanctuaryConfig cfg = cfg();
        if (Sanctuary.blocksBeyondNearestAnchor(cfg, player.getX(), player.getZ()) > 0) {
            ctx.getSource().sendFailure(Component.literal("Graveyards belong inside a sanctuary."));
            return 0;
        }
        String anchorId = "config";
        double bestSq = Double.MAX_VALUE;
        for (var a : com.k33bz.sanctuary.anchor.AnchorState.get().anchors) {
            double dx = a.x - player.getX(), dz = a.z - player.getZ();
            if (dx * dx + dz * dz < bestSq) {
                bestSq = dx * dx + dz * dz;
                anchorId = a.id != null ? a.id : "legacy";
            }
        }
        var store = com.k33bz.sanctuary.grave.Graves.store();
        String fAnchor = anchorId;
        store.yards.removeIf(y -> y.anchorId.equals(fAnchor));
        var yard = new com.k33bz.sanctuary.grave.Graves.Yard();
        yard.anchorId = anchorId;
        yard.dim = player.level().dimension().identifier().toString();
        yard.x = player.blockPosition().getX();
        yard.y = player.blockPosition().getY();
        yard.z = player.blockPosition().getZ();
        yard.radius = radius > 0 ? radius : cfg.graveyardDefaultRadius;
        store.yards.add(yard);
        com.k33bz.sanctuary.grave.Graves.save();
        com.k33bz.sanctuary.grave.Gravekeeper.spawnKeeper(
                (net.minecraft.server.level.ServerLevel) player.level(), yard);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Graveyard consecrated (r" + yard.radius + "). The Gravekeeper has arrived."), true);
        return 1;
    }

    private static int graveyardRemove(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        var yard = com.k33bz.sanctuary.grave.Graves.yardNear(player, 32);
        if (yard == null) {
            ctx.getSource().sendFailure(Component.literal("No graveyard within 32 blocks."));
            return 0;
        }
        com.k33bz.sanctuary.grave.Graves.store().yards.remove(yard);
        com.k33bz.sanctuary.grave.Graves.save();
        com.k33bz.sanctuary.grave.Graves.run((net.minecraft.server.level.ServerLevel) player.level(),
                "kill @e[type=minecraft:villager,tag=" + com.k33bz.sanctuary.grave.Gravekeeper.KEEPER_TAG
                        + ",distance=..40]");
        ctx.getSource().sendSuccess(() -> Component.literal("Graveyard deconsecrated."), true);
        return 1;
    }

    /**
     * Headstone-claim backend (permission 0): the command twin of the right-click on a grave's
     * interaction hitbox. With an explicit id, resolves that grave; otherwise picks the NEAREST
     * grave (in this dimension) within 6 blocks of the player. It then calls the SAME
     * {@link com.k33bz.sanctuary.grave.Graves#tryClaim} path the {@code UseEntityCallback} uses,
     * so every rule is enforced identically — owner-only until public, public-robbery credits
     * {@code sanct_robbed} and logs {@code robbery:true}, looted graves say "only memories",
     * the in-graveyard claim fee, and the 1s per-grave cooldown. This is purely an alternate
     * trigger for the already-gated action; it grants no access the rules wouldn't already allow.
     */
    private static int graveClaim(CommandContext<CommandSourceStack> ctx, String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        SanctuaryConfig cfg = cfg();
        if (!cfg.gravesEnabled) {
            ctx.getSource().sendFailure(Component.literal("Graves are disabled."));
            return 0;
        }
        com.k33bz.sanctuary.grave.Graves.Grave grave;
        if (id != null) {
            grave = com.k33bz.sanctuary.grave.Graves.byId(id);
            if (grave == null) {
                ctx.getSource().sendFailure(Component.literal("No grave with id '" + id + "'."));
                return 0;
            }
        } else {
            // Nearest grave to the player, in the same dimension, within 6 blocks — the same reach
            // the interaction hitbox affords. Keeper-held graves have no in-world headstone, so
            // (like the right-click) they are not reachable here; use /sanctuarygrave claimheld.
            String dim = player.level().dimension().identifier().toString();
            grave = null;
            double bestSq = 6 * 6;
            for (com.k33bz.sanctuary.grave.Graves.Grave g
                    : com.k33bz.sanctuary.grave.Graves.store().graves) {
                if (g.heldByKeeper || !dim.equals(g.dim)) {
                    continue;
                }
                double dx = g.x - player.getX(), dy = g.y - player.getY(), dz = g.z - player.getZ();
                double sq = dx * dx + dy * dy + dz * dz;
                if (sq < bestSq) {
                    bestSq = sq;
                    grave = g;
                }
            }
            if (grave == null) {
                ctx.getSource().sendFailure(Component.literal("No grave within reach."));
                return 0;
            }
        }
        // Same gated action as the headstone right-click.
        com.k33bz.sanctuary.grave.Graves.tryClaim(player, grave, cfg);
        return 1;
    }

    /** Ops read-only: list every consecrated yard (bounds/center/anchor), grave counts, and the live
     *  keeper population + positions per yard, plus the world grave total. */
    private static int graveList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("Graveyards + keepers:"), false);
        for (String line : com.k33bz.sanctuary.grave.Graves.describeYardsAndKeepers(src.getServer())) {
            src.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    /** UI-facing grave search (permission 0): text results by owner-name filter, or all graves. */
    private static int graveSearch(CommandContext<CommandSourceStack> ctx, String query) {
        CommandSourceStack src = ctx.getSource();
        List<String> results = com.k33bz.sanctuary.grave.Graves.searchGraves(query);
        boolean filtering = query != null && !query.isBlank();
        if (results.isEmpty()) {
            src.sendSuccess(() -> Component.literal(filtering
                    ? "No graves match \"" + query.trim() + "\"." : "No graves recorded."), false);
            return 0;
        }
        String header = filtering
                ? "Graves matching \"" + query.trim() + "\" (" + results.size() + "):"
                : "All graves (" + results.size() + "):";
        src.sendSuccess(() -> Component.literal(header), false);
        for (String line : results) {
            src.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    /** Admin force-clear: move loot-bearing graves to the nearest keeper hold; delete empties. */
    private static int clearWorld(CommandContext<CommandSourceStack> ctx, boolean includeGraveyard) {
        var result = com.k33bz.sanctuary.grave.Graves.clearWorld(
                ctx.getSource().getServer(), includeGraveyard);
        if (result == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No graveyard exists — nowhere to hold the loot. Consecrate one first."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Cleared graves: %d moved to the keeper's hold (%s), %d empty removed.%s",
                result.movedToHold(),
                result.holdYard() == null ? "no hold" : "yard " + result.holdYard(),
                result.removed(),
                includeGraveyard ? " (graveyard graves included)" : " (wild graves only)")), true);
        return 1;
    }

    /** Debug: age a grave by shifting its diedAtMs back N real days (drives epitaph/flora aging). */
    private static int graveSetAge(CommandContext<CommandSourceStack> ctx, String id)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        double days = DoubleArgumentType.getDouble(ctx, "days");
        com.k33bz.sanctuary.grave.Graves.Grave grave;
        if (id != null) {
            grave = com.k33bz.sanctuary.grave.Graves.byId(id);
        } else {
            net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
            String dim = player.level().dimension().identifier().toString();
            grave = null;
            double bestSq = 8 * 8;
            for (var g : com.k33bz.sanctuary.grave.Graves.store().graves) {
                if (g.heldByKeeper || !dim.equals(g.dim)) {
                    continue;
                }
                double dx = g.x - player.getX(), dy = g.y - player.getY(), dz = g.z - player.getZ();
                double sq = dx * dx + dy * dy + dz * dz;
                if (sq < bestSq) {
                    bestSq = sq;
                    grave = g;
                }
            }
        }
        if (grave == null) {
            ctx.getSource().sendFailure(Component.literal("No grave found to age."));
            return 0;
        }
        grave.diedAtMs = System.currentTimeMillis() - (long) (days * 86_400_000.0);
        grave.floraStage = null;   // force a flora + epitaph re-render on the next sweep
        grave.epitaphTier = null;
        com.k33bz.sanctuary.grave.Graves.save();
        final String gid = grave.id;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Aged grave %s to %.1f days old.", gid, days)), true);
        return 1;
    }

    /** Feed the nearest anchor from the executing player's inventory (dialog button backend). */
    private static int dialogFeed(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        String type = StringArgumentType.getString(ctx, "type");
        int count = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count");

        // nearest placed anchor within 6 blocks
        com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor best = null;
        double bestSq = 6 * 6;
        for (com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor a
                : com.k33bz.sanctuary.anchor.AnchorState.get().anchors) {
            double dx = a.x - player.getX(), dz = a.z - player.getZ();
            if (dx * dx + dz * dz < bestSq) {
                bestSq = dx * dx + dz * dz;
                best = a;
            }
        }
        if (best == null) {
            ctx.getSource().sendFailure(Component.literal("No sanctuary anchor within reach."));
            return 0;
        }
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(best.x, best.y, best.z);

        if (!type.equals("status") && count > 0) {
            net.minecraft.world.item.Item item = switch (type) {
                case "emerald" -> net.minecraft.world.item.Items.EMERALD;
                case "block" -> net.minecraft.world.item.Items.EMERALD_BLOCK;
                case "egg" -> net.minecraft.world.item.Items.DRAGON_EGG;
                default -> null;
            };
            if (item == null) {
                return 0;
            }
            // find a matching stack in the player's inventory and feed from it
            net.minecraft.world.item.ItemStack found = net.minecraft.world.item.ItemStack.EMPTY;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack st = player.getInventory().getItem(i);
                if (st.is(item)) {
                    found = st;
                    break;
                }
            }
            if (found.isEmpty()) {
                String label = switch (type) {
                    case "block" -> "emerald blocks";
                    case "egg" -> "dragon eggs";
                    default -> "emeralds";
                };
                player.sendOverlayMessage(Component.literal("You have no " + label + ".")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW));
            } else {
                com.k33bz.sanctuary.anchor.AnchorUpkeep.feed(player, pos, best, found,
                        Math.min(count, found.getCount()));
            }
        }
        // re-open with fresh numbers (this is the dialog's "refresh")
        com.k33bz.sanctuary.anchor.AnchorDialog.open(player, pos, best);
        return 1;
    }

    /**
     * Anchor rename backend (permission 0). Resolves the nearest anchor within 6 blocks the player
     * may rename (owner by UUID, or creative). {@code name == null} opens the text-input dialog;
     * a non-null name (from {@code set $(name)}) trims to 24 chars, strips section signs, applies,
     * and re-opens the anchor menu. Blank clears the name.
     */
    private static int anchorRename(CommandContext<CommandSourceStack> ctx, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor best = null;
        double bestSq = 6 * 6;
        for (com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor a
                : com.k33bz.sanctuary.anchor.AnchorState.get().anchors) {
            double dx = a.x - player.getX(), dz = a.z - player.getZ();
            if (dx * dx + dz * dz < bestSq
                    && com.k33bz.sanctuary.anchor.AnchorDialog.canRename(player, a)) {
                bestSq = dx * dx + dz * dz;
                best = a;
            }
        }
        if (best == null) {
            player.sendOverlayMessage(Component.literal("No sanctuary of yours within reach to name.")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            return 0;
        }
        if (name == null) {
            com.k33bz.sanctuary.anchor.AnchorDialog.openRename(player, best);
            return 1;
        }
        // Trim length and strip the section sign so names can't inject colour/format codes.
        String clean = name.replace('§', ' ').trim();
        if (clean.length() > 24) {
            clean = clean.substring(0, 24).trim();
        }
        best.name = clean.isEmpty() ? null : clean;
        com.k33bz.sanctuary.anchor.AnchorState.get().save();
        player.sendOverlayMessage(Component.literal(best.name == null
                        ? "The sanctuary's name was cleared."
                        : "This sanctuary is now \"" + best.name + "\".")
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        // Reflect the new label immediately; the periodic sweep also keeps it fresh.
        com.k33bz.sanctuary.anchor.AnchorUpkeep.refreshLabel(ctx.getSource().getServer(), best);
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(best.x, best.y, best.z);
        com.k33bz.sanctuary.anchor.AnchorDialog.open(player, pos, best);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("sanctuary")
                .requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list").executes(safe(SanctuaryCommands::list)))
                .then(Commands.literal("get")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(NUM.keySet(), b))
                                .executes(safe(SanctuaryCommands::get))))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(NUM.keySet(), b))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                        .executes(safe(SanctuaryCommands::set)))))
                .then(Commands.literal("toggle")
                        .then(Commands.argument("system", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(BOOL.keySet(), b))
                                .executes(safe(SanctuaryCommands::toggle))))
                .then(Commands.literal("save").executes(safe(SanctuaryCommands::save)))
                .then(Commands.literal("reload").executes(safe(SanctuaryCommands::reload)))
                .then(Commands.literal("cap")
                        .then(Commands.literal("get")
                                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                        .executes(safe(SanctuaryCommands::capGet))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                        .then(Commands.argument("cap", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                                .executes(safe(SanctuaryCommands::capSet))))))
                .then(Commands.literal("metrics")
                        .then(Commands.literal("top").executes(safe(SanctuaryCommands::metricsTop)))
                        .then(Commands.literal("clear").executes(safe(SanctuaryCommands::metricsClear))))
                .then(Commands.literal("crystal")
                        .then(Commands.literal("give").executes(safe(SanctuaryCommands::crystalGive))))
                .then(Commands.literal("essence")
                        .then(Commands.literal("give").executes(safe(SanctuaryCommands::essenceGive))))
                // Debug backends for the crafted-sanctuary chain (bot harness drives these; the
                // crafting grid + item-in-cauldron don't bridge cleanly through ViaProxy).
                .then(Commands.literal("membrane")
                        .then(Commands.literal("give")
                                .then(Commands.literal("raw").executes(safe(c -> membraneGive(c, true))))
                                .then(Commands.literal("cooked").executes(safe(c -> membraneGive(c, false))))))
                .then(Commands.literal("testcook")
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(safe(SanctuaryCommands::testCook))))
                .then(Commands.literal("danger")
                        .then(Commands.literal("status").executes(safe(SanctuaryCommands::dangerStatus)))
                        .then(Commands.literal("reset").executes(safe(SanctuaryCommands::dangerReset))))
                .then(Commands.literal("anchor")
                        .then(Commands.literal("time")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("hours", DoubleArgumentType.doubleArg(-100000, 100000))
                                                .executes(safe(c -> anchorTime(c, false, null)))
                                                .then(Commands.argument("ownerId", StringArgumentType.greedyString())
                                                        .executes(safe(c -> anchorTime(c, false,
                                                                StringArgumentType.getString(c, "ownerId")))))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("hours", DoubleArgumentType.doubleArg(0, 100000))
                                                .executes(safe(c -> anchorTime(c, true, null)))
                                                .then(Commands.argument("ownerId", StringArgumentType.greedyString())
                                                        .executes(safe(c -> anchorTime(c, true,
                                                                StringArgumentType.getString(c, "ownerId")))))))
                        )
                        .then(Commands.literal("exempt").executes(safe(SanctuaryCommands::anchorExempt)))
                        .then(Commands.literal("list").executes(safe(SanctuaryCommands::anchorList))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(safe(SanctuaryCommands::anchorList))))
                        .then(Commands.literal("clear").executes(safe(SanctuaryCommands::anchorClear)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0))
                                                        .executes(safe(SanctuaryCommands::anchorAdd)))))));
    }

    /**
     * Admin time management. {@code add} offsets the charge (negative drains); {@code set} pins
     * remaining hours exactly. Targets the nearest anchor within 16 blocks, or — given an id
     * argument — every anchor whose owner UUID starts with it (the 8-char id from the menus) or
     * whose owner name matches. Exempt anchors are skipped (flip them with /sanctuary anchor
     * exempt first).
     */
    private static int anchorTime(CommandContext<CommandSourceStack> ctx, boolean set, String ownerSel)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        double hours = DoubleArgumentType.getDouble(ctx, "hours");
        long now = ctx.getSource().getServer().overworld().getGameTime();
        com.k33bz.sanctuary.anchor.AnchorState state = com.k33bz.sanctuary.anchor.AnchorState.get();

        java.util.List<com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor> targets = new java.util.ArrayList<>();
        if (ownerSel != null) {
            for (var a : state.anchors) {
                if (com.k33bz.sanctuary.anchor.AnchorState.matches(a, ownerSel)) {
                    targets.add(a);
                }
            }
        } else {
            net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
            com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor best = null;
            double bestSq = 16 * 16;
            for (var a : state.anchors) {
                double dx = a.x - player.getX(), dz = a.z - player.getZ();
                if (dx * dx + dz * dz < bestSq) {
                    bestSq = dx * dx + dz * dz;
                    best = a;
                }
            }
            if (best != null) {
                targets.add(best);
            }
        }
        if (targets.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(ownerSel == null
                    ? "No placed anchor within 16 blocks." : "No anchors match '" + ownerSel + "'."));
            return 0;
        }
        int changed = 0;
        for (var a : targets) {
            if (a.isExempt()) {
                continue; // eternal — /sanctuary anchor exempt to make it fueled first
            }
            long expiry = set ? now + (long) (hours * 72000.0)
                    : Math.max(a.expiry, now) + (long) (hours * 72000.0);
            a.expiry = Math.max(now, expiry); // <= now means dormant right now
            changed++;
            String msg = String.format(java.util.Locale.ROOT, "  (%.0f, %.0f) %s — now %s [%.1fh]",
                    a.x, a.z, a.owner == null ? "server" : a.owner,
                    com.k33bz.sanctuary.anchor.AnchorUpkeep.remaining(a, now)[0], a.hoursLeft(now));
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        }
        if (changed > 0) {
            state.save();
            int n = changed;
            ctx.getSource().sendSuccess(() -> Component.literal(n + " anchor(s) updated."), true);
        } else {
            ctx.getSource().sendFailure(Component.literal(
                    "Matched only eternal anchors — use /sanctuary anchor exempt to make them fueled first."));
        }
        return changed;
    }

    /** Toggle upkeep exemption on the placed anchor nearest the executing player (within 16 blocks). */
    private static int anchorExempt(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.k33bz.sanctuary.anchor.AnchorState state = com.k33bz.sanctuary.anchor.AnchorState.get();
        com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor best = null;
        double bestSq = 16 * 16;
        for (com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor a : state.anchors) {
            double dx = a.x - player.getX(), dz = a.z - player.getZ();
            if (dx * dx + dz * dz < bestSq) {
                bestSq = dx * dx + dz * dz;
                best = a;
            }
        }
        if (best == null) {
            ctx.getSource().sendFailure(Component.literal("No placed anchor within 16 blocks."));
            return 0;
        }
        if (best.isExempt()) {
            best.expiry = player.level().getGameTime() + (long) (cfg().anchorStartHours * 72000.0);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Anchor is now fueled (upkeep applies, starting charge granted)."), true);
        } else {
            best.expiry = -1L;
            ctx.getSource().sendSuccess(() -> Component.literal("Anchor is now eternal (upkeep exempt)."), true);
        }
        state.save();
        return 1;
    }

    private static int capGet(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
        String uuid = player.getUUID().toString();
        int cap = com.k33bz.sanctuary.anchor.PlayerProgress.capOf(uuid, cfg().anchorCapBase);
        int owned = com.k33bz.sanctuary.anchor.AnchorState.get().countOwnedBy(uuid);
        int req = com.k33bz.sanctuary.anchor.PlayerProgress.requiredTierForNextRaise(uuid, cfg().anchorCapBase);
        String next = cap >= cfg().anchorCapMax ? "admin only"
                : (req <= 0 ? "any Warden" : com.k33bz.sanctuary.MobDifficulty.tierName(req) + "+ Warden");
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "%s: %d/%d anchors bound (next raise: %s)",
                player.getGameProfile().name(), owned, cap, next)), false);
        return 1;
    }

    private static int capSet(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
        int cap = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "cap");
        com.k33bz.sanctuary.anchor.PlayerProgress.setCap(player.getUUID().toString(), cap);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "%s's anchor cap set to %d.", player.getGameProfile().name(), cap)), true);
        return 1;
    }

    /** The busiest 64-block kill cells — a spawner-heavy hotspot is somebody's farm. */
    private static int metricsTop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        var top = com.k33bz.sanctuary.metrics.KillMetrics.top(10);
        if (top.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No kills recorded yet."), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Kill hotspots (%d cells tracked, %d-block bins):",
                com.k33bz.sanctuary.metrics.KillMetrics.size(),
                com.k33bz.sanctuary.metrics.KillMetrics.CELL_SIZE)), false);
        for (var e : top) {
            var c = e.getValue();
            src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                    "  %s — %d kills (%d by players, %d spawner-born, %d XP paid)",
                    e.getKey(), c.kills, c.playerKills, c.spawnerBorn, c.xp)), false);
        }
        return 1;
    }

    private static int metricsClear(CommandContext<CommandSourceStack> ctx) {
        com.k33bz.sanctuary.metrics.KillMetrics.clear();
        ctx.getSource().sendSuccess(() -> Component.literal("Kill metrics cleared."), true);
        return 1;
    }

    /** Hand the executing player a Sanctuary Crystal (for testing/admin seeding). */
    private static int crystalGive(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean added = player.getInventory().add(com.k33bz.sanctuary.anchor.SanctuaryCrystal.create());
        if (!added) {
            player.drop(com.k33bz.sanctuary.anchor.SanctuaryCrystal.create(), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Gave 1 Sanctuary Crystal."), true);
        return 1;
    }

    private static int essenceGive(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean added = player.getInventory().add(com.k33bz.sanctuary.anchor.WildEssence.create());
        if (!added) {
            player.drop(com.k33bz.sanctuary.anchor.WildEssence.create(), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Gave 1 Wild Essence."), true);
        return 1;
    }

    /** Hand the player a Raw or cooked Wild Membrane (debug backend for the bot harness). */
    private static int membraneGive(CommandContext<CommandSourceStack> ctx, boolean raw)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayerOrException();
        java.util.function.Supplier<net.minecraft.world.item.ItemStack> make = raw
                ? com.k33bz.sanctuary.anchor.WildMembrane::createRaw
                : com.k33bz.sanctuary.anchor.WildMembrane::create;
        if (!player.getInventory().add(make.get())) {
            player.drop(make.get(), false);
        }
        String which = raw ? "Raw " : "";
        ctx.getSource().sendSuccess(() -> Component.literal("Gave 1 " + which + "Wild Membrane."), true);
        return 1;
    }

    /**
     * Force the lava-cauldron temper at a position: if a Raw Wild Membrane item entity sits over a
     * LAVA cauldron there, run it to completion immediately (for the bot harness — it can't wait out
     * the natural cook timer reliably through ViaProxy). Reports what it found.
     */
    private static int testCook(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        net.minecraft.core.BlockPos pos =
                net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, "pos");
        if (!(src.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            src.sendFailure(Component.literal("Not a server level."));
            return 0;
        }
        boolean cooked = com.k33bz.sanctuary.anchor.LavaCauldronCook.forceCook(level, pos, cfg());
        if (cooked) {
            src.sendSuccess(() -> Component.literal("Tempered a Raw Wild Membrane at " + pos + "."), true);
            return 1;
        }
        src.sendFailure(Component.literal(
                "No Raw Wild Membrane over a lava cauldron at " + pos + "."));
        return 0;
    }

    /** The world-age pressure: days accrued since the epoch and the multiplier it produces. */
    private static int dangerStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        long gameTime = src.getServer().overworld().getGameTime();
        double days = Math.max(0L, gameTime - cfg().danger.epochTick) / 24000.0;
        float atSanctuary = SurvivalLogic.worldDangerMultiplier(
                src.getServer().overworld().getDifficulty().getId(), gameTime, 0.0, cfg().danger);
        String msg = String.format(
                "World-age danger: %.1f in-game days since last reset -> x%.2f inside a sanctuary (cap x%.1f)",
                days, atSanctuary, cfg().danger.maxMultiplier);
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** Re-zero the age pressure without touching the world clock. Persists immediately. */
    private static int dangerReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        cfg().danger.epochTick = src.getServer().overworld().getGameTime();
        cfg().save();
        src.sendSuccess(() -> Component.literal(
                "World-age danger reset — the world feels young again (saved to config)."), true);
        return 1;
    }

    /** Wraps a handler so any exception is logged with a full trace and reported, instead of a bare
     * "An unexpected error occurred" from the vanilla dispatcher. */
    private static Command<CommandSourceStack> safe(Command<CommandSourceStack> inner) {
        return ctx -> {
            try {
                return inner.run(ctx);
            } catch (Exception e) {
                Sanctuary.LOGGER.error("[sanctuary] command failed", e);
                ctx.getSource().sendFailure(Component.literal("sanctuary error: " + e));
                return 0;
            }
        };
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("XP Vitality — live config:"), false);
        for (Map.Entry<String, BoolKnob> e : BOOL.entrySet()) {
            src.sendSuccess(() -> Component.literal("  [system] " + e.getKey() + " = " + e.getValue().get().getAsBoolean()), false);
        }
        for (Map.Entry<String, NumKnob> e : NUM.entrySet()) {
            src.sendSuccess(() -> Component.literal("  " + e.getKey() + " = " + e.getValue().get().getAsDouble()), false);
        }
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        NumKnob k = NUM.get(key);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + key));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(key + " = " + k.get().getAsDouble()), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        double value = DoubleArgumentType.getDouble(ctx, "value");
        NumKnob k = NUM.get(key);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown key: " + key));
            return 0;
        }
        if (value < k.min() || value > k.max()) {
            ctx.getSource().sendFailure(Component.literal(key + " must be within [" + k.min() + ", " + k.max() + "]"));
            return 0;
        }
        k.set().accept(value);
        double applied = k.get().getAsDouble();
        ctx.getSource().sendSuccess(() -> Component.literal(key + " = " + applied + " (live; use /sanctuary save to persist)"), true);
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) {
        String system = StringArgumentType.getString(ctx, "system");
        BoolKnob k = BOOL.get(system);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown system: " + system));
            return 0;
        }
        boolean next = !k.get().getAsBoolean();
        k.set().accept(next);
        ctx.getSource().sendSuccess(() -> Component.literal("System '" + system + "' " + (next ? "ENABLED" : "DISABLED") + " (live)"), true);
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        cfg().save();
        ctx.getSource().sendSuccess(() -> Component.literal("Saved config to " + SanctuaryConfig.path()), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        Sanctuary.CONFIG = SanctuaryConfig.load();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded config from disk."), true);
        return 1;
    }

    private static int anchorList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String filter;
        try {
            filter = StringArgumentType.getString(ctx, "filter");
        } catch (IllegalArgumentException e) {
            filter = null;
        }
        src.sendSuccess(() -> Component.literal("Sanctuary anchors:"), false);
        List<SanctuaryConfig.Anchor> anchors = cfg().anchors;
        if (filter == null && anchors != null) {
            for (SanctuaryConfig.Anchor a : anchors) {
                src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                        "  config (%.0f, %.0f) r=%.0f — global (server-owned)", a.x, a.z, a.safeRadius)), false);
            }
        }
        long now = src.getServer().overworld().getGameTime();
        int shown = 0;
        for (com.k33bz.sanctuary.anchor.AnchorState.PlacedAnchor a
                : com.k33bz.sanctuary.anchor.AnchorState.get().anchors) {
            if (filter != null && !com.k33bz.sanctuary.anchor.AnchorState.matches(a, filter)) {
                continue;
            }
            shown++;
            String owner = a.owner == null ? "server" : a.owner;
            String id = a.id == null ? "????????" : a.id.substring(0, 8);
            String named = a.name == null || a.name.isBlank() ? "" : " \"" + a.name + "\"";
            String[] t = com.k33bz.sanctuary.anchor.AnchorUpkeep.remaining(a, now);
            String precise = a.isExempt() ? ""
                    : String.format(java.util.Locale.ROOT, " [%.1fh]", a.hoursLeft(now));
            src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                    "  [%s]%s (%.0f, %d, %.0f) r=%.0f — %s — %s%s",
                    id, named, a.x, a.y, a.z, a.radius, owner, t[0], precise)), false);
        }
        if (filter != null && shown == 0) {
            src.sendSuccess(() -> Component.literal("  (no anchors match)"), false);
        }
        return 1;
    }

    private static int anchorAdd(CommandContext<CommandSourceStack> ctx) {
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        double r = DoubleArgumentType.getDouble(ctx, "radius");
        if (cfg().anchors == null) {
            cfg().anchors = new java.util.ArrayList<>();
        }
        cfg().anchors.add(new SanctuaryConfig.Anchor(x, z, r));
        ctx.getSource().sendSuccess(() -> Component.literal("Added anchor (" + x + ", " + z + ") safeRadius=" + r + " (live)"), true);
        return 1;
    }

    private static int anchorClear(CommandContext<CommandSourceStack> ctx) {
        if (cfg().anchors != null) {
            cfg().anchors.clear();
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared all danger anchors (live)."), true);
        return 1;
    }
}
