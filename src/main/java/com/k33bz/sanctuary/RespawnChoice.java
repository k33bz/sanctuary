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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
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
    }

    private static final class Ledger {
        double escalation;
        long playTicks;      // PLAY_TIME stat when escalation was last updated
        int milestones;      // milestone count at last update (crossing a new one resets)
    }

    private static final Map<UUID, Offer> PENDING = new ConcurrentHashMap<>();
    private static Map<String, Ledger> ledger;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        double minutes = Math.max(0, play - entry.playTicks) / 20.0 / 60.0;
        double e = SurvivalLogic.decayedEscalation(entry.escalation, minutes, cfg.respawnEscalationDecayPer10Min);
        int milestonesNow = SurvivalLogic.milestonesReached(oldPlayer.experienceLevel, cfg.milestonesArray());
        if (milestonesNow > entry.milestones) {
            e = 0.0; // a new milestone cleanses the death toll
        }
        entry.escalation = e + cfg.respawnEscalationPerDeath;
        entry.playTicks = play;
        entry.milestones = milestonesNow;
        save();
        return e;
    }

    /** Record where the player fell; called alongside {@link #onDeath}. */
    public static void recordDeath(ServerPlayer oldPlayer, double escalation, SanctuaryConfig cfg) {
        Offer offer = new Offer();
        offer.deathX = oldPlayer.getX();
        offer.deathY = oldPlayer.getY();
        offer.deathZ = oldPlayer.getZ();
        offer.deathDim = oldPlayer.level().dimension().location().toString();
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
        String dimNow = level.dimension().location().toString();

        // Vanilla put them at bed/respawn-block if they have one; far from world spawn = a bed.
        var spawn = level.getSharedSpawnPos();
        double distSq = player.distanceToSqr(spawn.getX() + 0.5, player.getY(), spawn.getZ() + 0.5);
        offer.hasBed = distSq > 24 * 24;
        offer.bedX = player.getX();
        offer.bedY = player.getY();
        offer.bedZ = player.getZ();

        double[] anchor = nearestAnchor(level, offer.deathX, offer.deathZ, cfg);
        if (anchor != null) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) anchor[0], (int) anchor[1]);
            player.teleportTo(anchor[0] + 0.5, y + 1.0, anchor[1] + 0.5);
        }
        offer.expiresAtTick = level.getGameTime() + cfg.respawnOfferTimeoutSeconds * 20L;

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
        for (AnchorState.PlacedAnchor a : AnchorState.get().anchors) {
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
                List.of());
        Dialog dialog = new MultiActionDialog(common, buttons,
                Optional.of(new ActionButton(
                        new CommonButtonData(Component.literal("Rest here (free)"), 120), Optional.empty())),
                1);
        player.openDialog(Holder.direct(dialog));
    }

    private static ActionButton button(String label, String command) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 200),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(command))));
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
        boolean back = "back".equals(choice);
        if (back && !offer.deathDim.equals(level.dimension().location().toString())) {
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
        if (back) {
            player.teleportTo(offer.deathX, offer.deathY + 0.25, offer.deathZ);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "You claw your way back for %d levels.", cost))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            player.teleportTo(offer.bedX, offer.bedY, offer.bedZ);
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                            "Home, for %d levels.", cost))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        PENDING.remove(player.getUUID());
        return 1;
    }
}
