package com.k33bz.sanctuary;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.k33bz.sanctuary.anchor.AnchorInteraction;
import com.k33bz.sanctuary.anchor.AnchorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * XP Vitality — turns experience into a survival resource.
 *
 * <ul>
 *   <li>System 1 (regen): passive healing funded by draining XP, on a server tick.</li>
 *   <li>System 2 (mitigation) and System 4 (world-danger scaling): in {@code LivingEntityDamageMixin}.</li>
 *   <li>System 3 (lethal save): an XP-funded death cancel via {@link ServerLivingEntityEvents#ALLOW_DEATH}.</li>
 * </ul>
 *
 * Server-authoritative: no client entrypoint, so vanilla clients can connect.
 */
public class Sanctuary implements ModInitializer {
    public static final String MOD_ID = "sanctuary";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Loaded once at init; read from the tick handler and the damage mixin. */
    public static SanctuaryConfig CONFIG;

    /** Per-player game-time of last damage, for the shield's out-of-combat regen cooldown. */
    private static final java.util.Map<java.util.UUID, Long> LAST_COMBAT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Record that a player just took damage (called from the damage mixin). */
    public static void markCombat(java.util.UUID id, long gameTime) {
        LAST_COMBAT.put(id, gameTime);
    }

    /** Ticks since this player last took damage ({@code Long.MAX_VALUE} if never). */
    public static long ticksSinceCombat(ServerPlayer player) {
        Long last = LAST_COMBAT.get(player.getUUID());
        return last == null ? Long.MAX_VALUE : player.level().getGameTime() - last;
    }

    private int updateTickCounter = 0;

    @Override
    public void onInitialize() {
        CONFIG = SanctuaryConfig.load();
        LOGGER.info("[sanctuary] v0.4.0 initialized (server-authoritative) for Minecraft 26.1.2");

        AnchorState.get();        // load persisted sanctuary anchors (beacon + dragon egg)

        registerPlayerTick();
        registerLethalSave();
        registerMobScaling();
        registerAnchorBreak();
        // Forget zone tracking on disconnect so the next login re-announces the player's zone.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> MobDifficulty.clearPlayer(handler.player.getUUID()));
        AnchorInteraction.register();
        SanctuaryCommands.register();
    }

    /** Distance beyond the nearest safe anchor, considering BOTH config anchors and placed anchors. */
    public static double blocksBeyondNearestAnchor(SanctuaryConfig cfg, double x, double z) {
        return Math.min(cfg.blocksBeyondSafe(x, z), AnchorState.get().blocksBeyondSafe(x, z));
    }

    /** When an anchor beacon is broken, deactivate it, remove the seated egg display, and pop the egg back out. */
    private void registerAnchorBreak() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (state.is(Blocks.BEACON) && AnchorState.get().isAnchor(pos)) {
                AnchorState.get().ensureUnregistered(pos);
                MinecraftServer server = world.getServer();
                if (server != null) {
                    AnchorInteraction.removeEggDisplay(server, pos);
                }
                Block.popResource(world, pos, new ItemStack(Items.DRAGON_EGG));
            }
        });
    }

    /** System 7 — buff hostiles by their spawn distance from the nearest anchor. */
    private void registerMobScaling() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            SanctuaryConfig cfg = CONFIG;
            if (cfg != null && entity instanceof Monster mob && cfg.isScalingDimension(world)) {
                MobDifficulty.onSpawn(mob, cfg);
            }
        });
    }

    /**
     * Per-interval player update: refresh leveling-driven attributes (armor / bonus hearts / shield),
     * then run XP-funded regen. Runs regardless of whether regen is enabled so the visual layers stay current.
     */
    private void registerPlayerTick() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SanctuaryConfig cfg = CONFIG;
            if (++updateTickCounter < cfg.regenIntervalTicks) {
                return;
            }
            updateTickCounter = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.isDeadOrDying()) {
                    continue;
                }
                LevelAttributes.apply(player, cfg);
                if (cfg.regenEnabled) {
                    tryRegen(player, cfg);
                }
                if (player.level() instanceof ServerLevel serverLevel) {
                    MobDifficulty.tickParticles(serverLevel, player, cfg);
                }
                MobDifficulty.tickBoundary(player, cfg);
            }
            AnchorInteraction.pulseAnchors(server); // focus pulse at active anchors
        });
    }

    private void tryRegen(ServerPlayer player, SanctuaryConfig cfg) {
        if (player.isDeadOrDying()) {
            return;
        }
        float health = player.getHealth();
        float max = player.getMaxHealth();
        if (health >= max) {
            return;
        }
        boolean hasXp = player.totalExperience > 0 || player.experienceLevel > 0 || player.experienceProgress > 0;
        if (!hasXp) {
            return;
        }
        float heal = Math.min(cfg.regenHealPerInterval, max - health);
        int cost = SurvivalLogic.regenXpCost(heal, cfg.regenXpPerHealth);
        if (cost <= 0) {
            return;
        }
        // Negative argument drains XP and re-derives level/progress safely (clamped at 0).
        player.giveExperiencePoints(-cost);
        player.setHealth(Math.min(max, health + heal));
    }

    /** System 3 — spend whole levels to cancel an otherwise-lethal hit, dropping to ~1 heart. */
    private void registerLethalSave() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            SanctuaryConfig cfg = CONFIG;
            if (!cfg.lethalSaveEnabled || !(entity instanceof ServerPlayer player)) {
                return true; // allow the death to proceed
            }
            int needed = SurvivalLogic.lethalSaveLevelCost(amount, cfg.lethalSaveLevelsPerDamage, cfg.lethalSaveMinLevels);
            if (player.experienceLevel < needed) {
                return true; // can't afford the save -> die normally
            }
            player.giveExperienceLevels(-needed);
            player.setHealth(cfg.lethalSaveReviveHealth);
            LOGGER.info("[sanctuary] Lethal save: {} spent {} level(s)", player.getName().getString(), needed);
            return false; // cancel death
        });
    }
}
