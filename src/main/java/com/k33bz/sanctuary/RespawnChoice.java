package com.k33bz.sanctuary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.k33bz.sanctuary.anchor.AnchorState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System 9 — respawn choice ("your soul settles"). Death sends the player to the nearest active
 * sanctuary for free; a native dialog then offers paid upgrades: return to bed (cheap) or
 * resurrect at the death site (the corpse-run skip, pricier). Costs are a fraction of current
 * levels, multiplied by a repeat-death escalation that decays with time played (vanilla
 * PLAY_TIME stat, so it can't be waited out offline) and resets entirely when a new XP milestone
 * is reached. The free option never charges beyond the ordinary soul-retention loss — a bad
 * streak can't spiral a player to zero. Escalation persists in config/sanctuary_deaths.json.
 */
public final class RespawnChoice {
    private RespawnChoice() {
    }

    /** A pending post-death offer. Positions are only offered within the same dimension. */
    private static final class Offer {
        double deathX, deathY, deathZ;
        String deathDim;
        double bedX, bedY, bedZ;
        boolean hasBed;
        int bedCost, backCost;
        long expiresAtTick;
        /** The dying player's own active anchors, nearest-death first (for the sanctuary picker). */
        List<AnchorState.PlacedAnchor> ownedAnchors = List.of();
    }

    private static final class Ledger {
        double escalation;
        long playTicks;      // PLAY_TIME stat when escalation was last updated
        int milestones;      // milestone count at last update (crossing a new one resets)
    }

    private static final Map<UUID, Offer> PENDING = new ConcurrentHashMap<>();
    private static Map<String, Ledger> ledger;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int TICKS_PER_SECOND = 20;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_deaths.json");
    }

    private static Map<String, Ledger> ledger() {
        if (ledger == null) {
            try {
                if (Files.exists(path())) {
                    ledger = GSON.fromJson(Files.readString(path()),
                            new TypeToken<Map<String, Ledger>>() { }.getType());
                }
            } catch (Exception e) {
                Sanctuary.LOGGER.warn("[sanctuary] could not read death ledger", e);
            }
            if (ledger == null) {
                ledger = new HashMap<>();
            }
        }
        return ledger;
    }

    private static void save() {
        try {
            Files.writeString(path(), GSON.toJson(ledger()));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] could not save death ledger", e);
        }
    }

    /**
     * Death bookkeeping (called from the respawn copy, dying side). Decays the escalation by
     * play time since the last death, resets it if a new milestone was reached since, computes
     * THIS death's surcharge from the decayed value, then bumps it for the next one.
     * Returns the escalation to price this death's offer with.
     */
    public static double onDeath(ServerPlayer oldPlayer, SanctuaryConfig cfg) {
        String id = oldPlayer.getUUID().toString();
        Ledger entry = ledger().computeIfAbsent(id, k -> new Ledger());
        long play = oldPlayer.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        double minutes = Math.max(0, play - entry.playTicks) / (double) TICKS_PER_SECOND / 60.0;
        int milestonesNow = SurvivalLogic.milestonesReached(oldPlayer.experienceLevel, cfg.milestonesArray());
        RespawnLedger.Update u = RespawnLedger.update(entry.escalation, minutes,
                cfg.respawnEscalationDecayPer10Min, milestonesNow, entry.milestones,
                cfg.respawnEscalationPerDeath);
        entry.escalation = u.nextStored;
        entry.playTicks = play;
        entry.milestones = milestonesNow;
        save();
        return u.priced;
    }

    /** Record where the player fell; called alongside {@link #onDeath}. */
    public static void recordDeath(ServerPlayer oldPlayer, double escalation, SanctuaryConfig cfg) {
        Offer offer = new Offer();
        offer.deathX = oldPlayer.getX();
        offer.deathY = oldPlayer.getY();
        offer.deathZ = oldPlayer.getZ();
        offer.deathDim = oldPlayer.level().dimension().identifier().toString();
        // Costs are priced on the level they'll have AFTER soul retention, which hasn't run yet —
        // priced here on the death level and charged only if affordable at click time.
        int level = oldPlayer.experienceLevel;
        offer.bedCost = SurvivalLogic.respawnCostLevels(level, cfg.respawnBedCostFraction,
                escalation, cfg.respawnMinCostLevels);
        offer.backCost = SurvivalLogic.respawnCostLevels(level, cfg.respawnBackCostFraction,
                escalation, cfg.respawnMinCostLevels);
        PENDING.put(oldPlayer.getUUID(), offer);
    }

    /**
     * After vanilla places the respawned player (bed or world spawn): capture that spot as the
     * bed option, move them to the nearest active sanctuary (the free default), and open the
     * choice dialog. If no sanctuary exists, vanilla's placement stands and only the
     * resurrect option is offered.
     */
    public static void onRespawn(ServerPlayer player, SanctuaryConfig cfg) {
        Offer offer = PENDING.get(player.getUUID());
        if (offer == null) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        String dimNow = level.dimension().identifier().toString();

        // A set respawn point (bed / respawn anchor) means vanilla just placed them at it.
        offer.hasBed = player.getRespawnConfig() != null;
        offer.bedX = player.getX();
        offer.bedY = player.getY();
        offer.bedZ = player.getZ();

        double[] anchor = nearestAnchor(level, offer.deathX, offer.deathZ, cfg);
        if (anchor != null) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) anchor[0], (int) anchor[1]);
            safePlace(level, player, anchor[0] + 0.5, y + 1.0, anchor[1] + 0.5);
        }
        offer.expiresAtTick = level.getGameTime() + cfg.respawnOfferTimeoutSeconds * (long) TICKS_PER_SECOND;
        // Nothing may kill the player while the respawn dialog is open (suffocation, mobs, lava,
        // fall) — grant full damage immunity for the offer window. Trimmed to a brief grace once
        // they choose (see endProtect); expires on its own if they walk away from the menu.
        protect(player, cfg.respawnOfferTimeoutSeconds * TICKS_PER_SECOND + 2 * TICKS_PER_SECOND);

        // Owned active PLACED anchors, nearest-to-death first — a player who holds two or more
        // gets to pick which sanctuary to wake at (free); the nearest is the default selection.
        offer.ownedAnchors = ownedActiveAnchors(level, player, offer.deathX, offer.deathZ);

        boolean offerBack = offer.deathDim.equals(dimNow);
        openDialog(player, offer, anchor != null, offerBack);
    }

    /** Nearest active anchor (config or placed) to a point, as {x, z}; null if none. */
    private static double[] nearestAnchor(ServerLevel level, double x, double z, SanctuaryConfig cfg) {
        double[] best = null;
        double bestSq = Double.MAX_VALUE;
        for (SanctuaryConfig.Anchor a : cfg.anchors) {
            double dx = a.x - x, dz = a.z - z;
            double d = dx * dx + dz * dz;
            if (d < bestSq) {
                bestSq = d;
                best = new double[]{a.x, a.z};
            }
        }
        long now = level.getGameTime();
        for (AnchorState.PlacedAnchor a : AnchorState.get() == null
                ? List.<AnchorState.PlacedAnchor>of() : AnchorState.get().anchors) {
            if (!a.isActive(now)) {
                continue;
            }
            double dx = a.x - x, dz = a.z - z;
            double d = dx * dx + dz * dz;
            if (d < bestSq) {
                bestSq = d;
                best = new double[]{a.x, a.z};
            }
        }
        return best;
    }

    /** The player's own ACTIVE placed anchors in the death dimension, nearest-to-death first. */
    private static List<AnchorState.PlacedAnchor> ownedActiveAnchors(
            ServerLevel level, ServerPlayer player, double x, double z) {
        long now = level.getGameTime();
        String me = player.getUUID().toString();
        List<AnchorState.PlacedAnchor> mine = new ArrayList<>();
        for (AnchorState.PlacedAnchor a : AnchorState.get() == null
                ? List.<AnchorState.PlacedAnchor>of() : AnchorState.get().anchors) {
            if (a.isActive(now) && me.equals(a.ownerId)) {
                mine.add(a);
            }
        }
        mine.sort(java.util.Comparator.comparingDouble(
                a -> (a.x - x) * (a.x - x) + (a.z - z) * (a.z - z)));
        return mine;
    }

    private static void openDialog(ServerPlayer player, Offer offer, boolean atSanctuary, boolean offerBack) {
        List<DialogBody> body = new ArrayList<>();
        var text = Component.literal(atSanctuary
                        ? "Your soul settles at the nearest sanctuary."
                        : "No sanctuary answered; you woke where the world put you.")
                .withStyle(ChatFormatting.GRAY);
        if (offer.bedCost > SurvivalLogic.respawnCostLevels(1, 0, 0, 1)) {
            text.append(Component.literal("\nRepeat deaths have raised your toll — it fades as you live, "
                    + "and clears at your next milestone.").withStyle(ChatFormatting.DARK_GRAY));
        }
        body.add(new PlainMessage(text, 250));

        List<ActionButton> buttons = new ArrayList<>();
        // Two or more owned sanctuaries: a single-option picker to choose which to wake at (free).
        // The nearest (index 0) is pre-selected, matching the default the free rest already gave.
        List<net.minecraft.server.dialog.Input> inputs = List.of();
        if (offer.ownedAnchors.size() >= 2) {
            List<net.minecraft.server.dialog.input.SingleOptionInput.Entry> entries = new ArrayList<>();
            for (int i = 0; i < offer.ownedAnchors.size(); i++) {
                AnchorState.PlacedAnchor a = offer.ownedAnchors.get(i);
                String label = (a.name != null && !a.name.isBlank())
                        ? a.name
                        : String.format(Locale.ROOT, "Sanctuary at %d, %d", (int) a.x, (int) a.z);
                if (i == 0) {
                    label = label + " (nearest)";
                }
                entries.add(com.k33bz.sanctuary.DialogInputs.entry(a.id, label, i == 0));
            }
            inputs = List.of(com.k33bz.sanctuary.DialogInputs.singleOption(
                    "sanctuary", "Wake at", 220, entries));
            buttons.add(new ActionButton(
                    new net.minecraft.server.dialog.CommonButtonData(
                            Component.literal("Wake at chosen sanctuary (free)"), 220),
                    com.k33bz.sanctuary.DialogInputs.command("sanctuarywake $(sanctuary)")));
        }
        if (offer.hasBed) {
            buttons.add(button("Return to bed — " + offer.bedCost + " level" + (offer.bedCost == 1 ? "" : "s"),
                    "sanctuaryrespawn bed"));
        }
        if (offerBack) {
            buttons.add(button("Resurrect where you fell — " + offer.backCost + " level"
                    + (offer.backCost == 1 ? "" : "s"), "sanctuaryrespawn back"));
        }
        CommonDialogData common = new CommonDialogData(
                Component.literal("Your Soul Settles"),
                Optional.empty(),
                true,
                false,
                DialogAction.CLOSE,
                body,
                inputs);
        // Guarded: every button above is conditional, so a player dying with no bed, fewer than two
        // owned sanctuaries and no resurrect offer (e.g. in the gathering world) produced a ZERO-action
        // dialog, which fails to encode and disconnects them. See DialogInputs.multiAction.
        Dialog dialog = com.k33bz.sanctuary.DialogInputs.multiAction(common, buttons,
                new ActionButton(
                        new CommonButtonData(Component.literal("Rest here (free)"), 120),
                        Optional.of(new StaticAction(new ClickEvent.RunCommand("sanctuaryrespawn rest")))),
                1);
        player.openDialog(Holder.direct(dialog));
    }

    private static ActionButton button(String label, String command) {
        // STATIC command → ClickEvent.RunCommand, NOT CommandTemplate: a CommandTemplate REQUIRES
        // at least one $(var) macro and throws "No variables in macro" on a static command, which
        // crashed the respawn dialog (0.8.6.1 regression). CommandTemplate is only for the $(var)
        // form-submit buttons (e.g. "Wake at chosen sanctuary" uses sanctuarywake $(sanctuary)).
        return new ActionButton(new CommonButtonData(Component.literal(label), 200),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(command))));
    }

    /**
     * Dialog button backend: {@code sanctuarywake <anchorId>}. Free — it only redirects the free
     * respawn to a DIFFERENT owned, active sanctuary than the nearest default. Validates the anchor
     * is in this player's pending offer (so it can't wake at anchors they don't own).
     */
    public static int wakeAt(ServerPlayer player, String anchorId) {
        Offer offer = PENDING.get(player.getUUID());
        ServerLevel level = (ServerLevel) player.level();
        if (offer == null || level.getGameTime() > offer.expiresAtTick) {
            PENDING.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("That door has closed.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        AnchorState.PlacedAnchor target = null;
        for (AnchorState.PlacedAnchor a : offer.ownedAnchors) {
            if (anchorId.equals(a.id)) {
                target = a;
                break;
            }
        }
        if (target == null || !target.isActive(level.getGameTime())) {
            player.sendSystemMessage(Component.literal("That sanctuary no longer answers.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) target.x, (int) target.z);
        safePlace(level, player, target.x, y + 1.0, target.z);
        endProtect(player);
        PENDING.remove(player.getUUID());
        String where = (target.name != null && !target.name.isBlank())
                ? "\"" + target.name + "\"" : "that sanctuary";
        player.sendSystemMessage(Component.literal("Your soul settles at " + where + ".")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        return 1;
    }

    /** Dialog button backend: {@code sanctuaryrespawn bed|back}. Charges levels, then teleports. */
    public static int applyChoice(ServerPlayer player, String choice) {
        Offer offer = PENDING.get(player.getUUID());
        ServerLevel level = (ServerLevel) player.level();
        if (offer == null || level.getGameTime() > offer.expiresAtTick) {
            PENDING.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("That door has closed.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        // "rest" = accept the free default placement (already made safe by onRespawn). Just close
        // the offer and trim the menu immunity to a short landing grace.
        if ("rest".equals(choice)) {
            PENDING.remove(player.getUUID());
            endProtect(player);
            return 1;
        }
        boolean back = "back".equals(choice);
        if (back && !offer.deathDim.equals(level.dimension().identifier().toString())) {
            player.sendSystemMessage(Component.literal("Your death lies in another world.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        if (!back && !offer.hasBed) {
            return 0;
        }
        int cost = back ? offer.backCost : offer.bedCost;
        if (player.experienceLevel < cost) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "Not enough of you remains (%d levels needed, %d held).",
                            cost, player.experienceLevel))
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        player.giveExperienceLevels(-cost);
        StatBoards.addScore(player, "sanct_toll", cost);
        if (back) {
            safePlace(level, player, offer.deathX, offer.deathY + 0.25, offer.deathZ);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "You claw your way back for %d levels.", cost))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            safePlace(level, player, offer.bedX, offer.bedY, offer.bedZ);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "Home, for %d levels.", cost))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        endProtect(player);
        PENDING.remove(player.getUUID());
        return 1;
    }

    // ---- safe placement + respawn immunity ------------------------------------------------

    /**
     * Teleport the player to a spot they can actually stand in near {@code (x,y,z)}: two passable
     * blocks (feet + head) over solid ground, no lava/water. Scans a few blocks around the target,
     * preferring the closest; if none is found (deep in rock), drops to the surface column and
     * clears the two occupied blocks. Fixes respawning inside a wall and suffocating instantly.
     */
    private static void safePlace(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        BlockPos origin = BlockPos.containing(x, y, z);
        BlockPos spot = null;
        for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3, 4, 5, 6, 7, 8, -4}) {
            BlockPos p = origin.above(dy);
            if (standable(level, p)) {
                spot = p;
                break;
            }
        }
        if (spot == null) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, origin.getX(), origin.getZ());
            spot = new BlockPos(origin.getX(), top + 1, origin.getZ());
            clearBlock(level, spot);
            clearBlock(level, spot.above());
        }
        player.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        return passable(level, p) && passable(level, p.above())
                && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    private static boolean passable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    private static void clearBlock(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!s.getCollisionShape(level, p).isEmpty() || !s.getFluidState().isEmpty()) {
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        }
    }

    /** Full damage immunity (Resistance V + Fire Resistance) for {@code ticks} — the menu window. */
    private static void protect(ServerPlayer player, int ticks) {
        player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks, 4, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, ticks, 0, false, false, false));
    }

    /** Once a choice is made, trim the immunity to a 3s landing grace (no minutes of invulnerability). */
    private static void endProtect(ServerPlayer player) {
        player.removeEffect(MobEffects.RESISTANCE);
        player.removeEffect(MobEffects.FIRE_RESISTANCE);
        player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 3 * TICKS_PER_SECOND, 4, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 3 * TICKS_PER_SECOND, 0, false, false, false));
    }
}
