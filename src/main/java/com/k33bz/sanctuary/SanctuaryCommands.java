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
        num("anchor.crystalDropMinTier", () -> cfg().crystalDropMinTier,
                v -> cfg().crystalDropMinTier = (int) Math.round(v), 0, 4);
        num("anchor.crystalDropChance", () -> cfg().crystalDropChance, v -> cfg().crystalDropChance = v, 0, 1);
        num("anchor.labelHeight", () -> cfg().anchorLabelHeight, v -> cfg().anchorLabelHeight = v, 0, 4);
        num("anchor.startHours", () -> cfg().anchorStartHours, v -> cfg().anchorStartHours = v, 0, 100000);
        num("anchor.hoursPerEmerald", () -> cfg().anchorHoursPerEmerald, v -> cfg().anchorHoursPerEmerald = v, 0, 10000);
        num("anchor.maxFuelHours", () -> cfg().anchorMaxFuelHours, v -> cfg().anchorMaxFuelHours = v, 1, 1000000);
        num("anchor.flanClaimRadius", () -> cfg().flanClaimRadius, v -> cfg().flanClaimRadius = (int) Math.round(v), 1, 128);
        num("mobscaling.particleRange", () -> cfg().mobScaling.particleRange, v -> cfg().mobScaling.particleRange = v, 0, 256);
        num("lethalSave.levelsPerDamage", () -> cfg().lethalSaveLevelsPerDamage, v -> cfg().lethalSaveLevelsPerDamage = (float) v, 0, 100);
        num("lethalSave.minLevels", () -> cfg().lethalSaveMinLevels, v -> cfg().lethalSaveMinLevels = (int) Math.round(v), 0, 1000);
        num("lethalSave.reviveHealth", () -> cfg().lethalSaveReviveHealth, v -> cfg().lethalSaveReviveHealth = (float) v, 1, 1024);
        num("danger.difficultyWeight", () -> cfg().danger.difficultyWeight, v -> cfg().danger.difficultyWeight = (float) v, 0, 100);
        num("danger.perDayWeight", () -> cfg().danger.perDayWeight, v -> cfg().danger.perDayWeight = v, 0, 100);
        num("danger.perBlockWeight", () -> cfg().danger.perBlockWeight, v -> cfg().danger.perBlockWeight = v, 0, 100);
        num("danger.maxMultiplier", () -> cfg().danger.maxMultiplier, v -> cfg().danger.maxMultiplier = (float) v, 1, 1000);

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
        bool("flanIntegration", () -> cfg().flanIntegration, b -> cfg().flanIntegration = b);
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(build()));
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
                .then(Commands.literal("crystal")
                        .then(Commands.literal("give").executes(safe(SanctuaryCommands::crystalGive))))
                .then(Commands.literal("danger")
                        .then(Commands.literal("status").executes(safe(SanctuaryCommands::dangerStatus)))
                        .then(Commands.literal("reset").executes(safe(SanctuaryCommands::dangerReset))))
                .then(Commands.literal("anchor")
                        .then(Commands.literal("exempt").executes(safe(SanctuaryCommands::anchorExempt)))
                        .then(Commands.literal("list").executes(safe(SanctuaryCommands::anchorList)))
                        .then(Commands.literal("clear").executes(safe(SanctuaryCommands::anchorClear)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0))
                                                        .executes(safe(SanctuaryCommands::anchorAdd)))))));
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
        List<SanctuaryConfig.Anchor> anchors = cfg().anchors;
        if (anchors == null || anchors.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No danger anchors configured."), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal("Danger anchors:"), false);
        for (int i = 0; i < anchors.size(); i++) {
            SanctuaryConfig.Anchor a = anchors.get(i);
            int idx = i;
            src.sendSuccess(() -> Component.literal("  #" + idx + " (" + a.x + ", " + a.z + ") safeRadius=" + a.safeRadius), false);
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
