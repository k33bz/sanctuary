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
        VanillaTweaksPacks.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
                .register(StatBoards::ensureObjectives);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (CONFIG != null && CONFIG.gravesEnabled
                    && server.getPackRepository().getSelectedIds().contains("sanctuary:vt/graves")) {
                LOGGER.warn("[sanctuary] Native graves AND the VT graves pack are both active -- "
                        + "disable one or death inventories can duplicate.");
            }
        });
        // System 10 -- right-clicks on headstones (claim/rob) and the Gravekeeper (summon menu).
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
                (p, world, hand, entity, hit) -> {
                    SanctuaryConfig cfg = CONFIG;
                    if (cfg == null || !cfg.gravesEnabled || world.isClientSide()
                            || !(p instanceof ServerPlayer sp)
                            || hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
                        return net.minecraft.world.InteractionResult.PASS;
                    }
                    String prefix = com.k33bz.sanctuary.grave.Graves.GRAVE_TAG + "_";
                    for (String tag : entity.entityTags()) {
                        if (tag.startsWith(prefix)) {
                            var grave = com.k33bz.sanctuary.grave.Graves.byId(tag.substring(prefix.length()));
                            if (grave != null) {
                                com.k33bz.sanctuary.grave.Graves.tryClaim(sp, grave, cfg);
                                return net.minecraft.world.InteractionResult.SUCCESS;
                            }
                        }
                    }
                    if (entity.entityTags().contains(com.k33bz.sanctuary.grave.Gravekeeper.KEEPER_TAG)) {
                        com.k33bz.sanctuary.grave.Gravekeeper.openDialog(sp, cfg);
                        return net.minecraft.world.InteractionResult.SUCCESS;
                    }
                    return net.minecraft.world.InteractionResult.PASS;
                });
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        String version = loader.getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String mc = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        LOGGER.info("[sanctuary] v{} initialized (server-authoritative) for Minecraft {}", version, mc);

        AnchorState.get();        // load persisted sanctuary anchors (beacon + dragon egg)

        registerPlayerTick();
        registerLethalSave();
        registerMobScaling();
        registerAnchorBreak();
        registerCrystalDrops();
        registerSoulRetention();
        com.k33bz.sanctuary.anchor.AnchorUpkeep.register();
        // Forget zone tracking on disconnect so the next login re-announces the player's zone.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    MobDifficulty.clearPlayer(handler.player.getUUID());
                    AfkTracker.forget(handler.player.getUUID());
                });
        SanctuaryCommands.register();
        // Final flush/close of the metrics stores on clean shutdown.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            com.k33bz.sanctuary.metrics.KillMetrics.flush();
            com.k33bz.sanctuary.metrics.KillEventLog.close();
        });
    }

    /** Distance beyond the nearest safe anchor, considering BOTH config anchors and placed anchors. */
    public static double blocksBeyondNearestAnchor(SanctuaryConfig cfg, double x, double z) {
        return Math.min(cfg.blocksBeyondSafe(x, z), AnchorState.get().blocksBeyondSafe(x, z));
    }

    /**
     * Anchor breaking: permission-gated (sanctuary.anchor.break, default allowed — LuckPerms can
     * restrict it), deactivates the anchor and cleans up its displays. The crystal head drops
     * itself with its profile intact, so it can be re-placed; legacy beacon anchors pop their
     * dragon egg back out.
     */
    private void registerAnchorBreak() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!AnchorState.get().isAnchor(pos)) {
                return true;
            }
            boolean isAnchorBlock = state.is(Blocks.BEACON) || state.getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock;
            if (!isAnchorBlock) {
                return true;
            }
            if (player instanceof ServerPlayer sp
                    && !me.lucko.fabric.api.permissions.v0.Permissions.check(sp, "sanctuary.anchor.break", true)) {
                sp.sendOverlayMessage(net.minecraft.network.chat.Component
                        .literal("This sanctuary anchor is protected."));
                return false;
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            boolean isAnchorBlock = state.is(Blocks.BEACON) || state.getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock;
            if (isAnchorBlock && AnchorState.get().isAnchor(pos)) {
                AnchorState.get().ensureUnregistered(pos);
                MinecraftServer server = world.getServer();
                if (server != null) {
                    AnchorInteraction.removeDisplays(server, pos);
                }
                if (CONFIG != null && CONFIG.flanIntegration && world instanceof ServerLevel sl
                        && com.k33bz.sanctuary.anchor.FlanIntegration.available()) {
                    com.k33bz.sanctuary.anchor.FlanIntegration.removeClaim(sl, pos);
                }
                if (state.is(Blocks.BEACON)) {
                    Block.popResource(world, pos, new ItemStack(Items.DRAGON_EGG)); // legacy anchors
                }
            }
        });
    }

    /** Sanctuary Crystals drop rarely from high-tier wildlands mobs killed by players. */
    private void registerCrystalDrops() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            SanctuaryConfig cfg = CONFIG;
            if (cfg == null || !(entity instanceof net.minecraft.world.entity.Mob mob)
                    || !(entity.level() instanceof ServerLevel level)) {
                return;
            }
            com.k33bz.sanctuary.metrics.KillMetrics.record(mob, level, source);
            if (CONFIG.killEventLogEnabled) {
                com.k33bz.sanctuary.metrics.KillEventLog.record(mob, level, source,
                        MobDifficulty.tierOf(mob, cfg.mobScaling));
            }
            if (!cfg.isScalingDimension(level) || !(source.getEntity() instanceof ServerPlayer)) {
                return;
            }
            int tier = MobDifficulty.tierOf(mob, cfg.mobScaling);
            // Warden kills attune players to bind more sanctuaries (any Warden, then Feral+, ...).
            if (mob instanceof net.minecraft.world.entity.monster.warden.Warden
                    && source.getEntity() instanceof ServerPlayer slayer) {
                int newCap = com.k33bz.sanctuary.anchor.PlayerProgress.tryRaise(
                        slayer.getUUID().toString(), Math.max(0, tier), cfg.anchorCapBase, cfg.anchorCapMax);
                if (newCap > 0) {
                    slayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format(
                            java.util.Locale.ROOT,
                            "The Warden's heart attunes to you — you may now bind %d sanctuaries.", newCap))
                            .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                    LOGGER.info("[sanctuary] {} attuned to anchor cap {} (tier-{} warden)",
                            slayer.getGameProfile().name(), newCap, tier);
                }
            }
            if (tier < cfg.crystalDropMinTier
                    || mob.getRandom().nextDouble() >= cfg.crystalDropChance) {
                return;
            }
            Block.popResource(level, mob.blockPosition(),
                    com.k33bz.sanctuary.anchor.SanctuaryCrystal.create());
            LOGGER.info("[sanctuary] A tier-{} {} dropped a Sanctuary Crystal", tier,
                    mob.getType().getDescription().getString());
        });
    }

    /**
     * System 8 — soul retention: on respawn after death, restore a level-scaled fraction of the
     * levels the player died with. Runs in the respawn copy, after vanilla has already dropped
     * the (small, capped) XP orb at the death site — that recovery orb is unchanged.
     */
    private void registerSoulRetention() {
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            SanctuaryConfig cfg = CONFIG;
            if (alive || cfg == null) {
                return;
            }
            // System 9 — record the death for the respawn-choice dialog (priced pre-retention).
            if (cfg.respawnChoiceEnabled) {
                double escalation = RespawnChoice.onDeath(oldPlayer, cfg);
                RespawnChoice.recordDeath(oldPlayer, escalation, cfg);
            }
            if (!cfg.deathKeepEnabled) {
                return;
            }
            int level = oldPlayer.experienceLevel;
            int kept = SurvivalLogic.deathKeptLevels(level, cfg.milestonesArray(),
                    cfg.deathKeepBase, cfg.deathKeepPerMilestone, cfg.deathKeepMax);
            if (kept > 0) {
                newPlayer.setExperienceLevels(kept);
                newPlayer.sendSystemMessage(net.minecraft.network.chat.Component
                        .literal(String.format(java.util.Locale.ROOT,
                                "The sanctuaries preserve part of your soul: %d of %d levels retained.",
                                kept, level))
                        .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
            }
        });
        // System 9 — after vanilla places the respawned player, move them to the nearest
        // sanctuary (the free default) and open the paid-upgrade dialog.
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> {
                    SanctuaryConfig cfg = CONFIG;
                    if (!alive && cfg != null && cfg.respawnChoiceEnabled) {
                        RespawnChoice.onRespawn(newPlayer, cfg);
                    }
                });
    }

    /** System 7 — buff hostiles by their spawn distance from the nearest anchor. */
    private void registerMobScaling() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            SanctuaryConfig cfg = CONFIG;
            if (cfg == null || !cfg.isScalingDimension(world)) {
                return;
            }
            // Enemy (not Monster): slimes, phantoms, and ghasts don't extend Monster but are
            // hostiles all the same — they scale too.
            if (entity instanceof net.minecraft.world.entity.Mob mob
                    && entity instanceof net.minecraft.world.entity.monster.Enemy) {
                MobDifficulty.onSpawn(mob, cfg);
            } else if (entity instanceof net.minecraft.world.entity.animal.Animal animal) {
                if (animal.isBaby()) {
                    // Hatched beside a Feral Egg projectile? Stamp the bloodline before the
                    // (baby-exempt) rabid pass ignores it.
                    FeralEgg.onBabyLoad(animal, world, cfg);
                }
                MobDifficulty.onAnimalLoad(animal, cfg);
            } else if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
                // A fresh egg beside a feral hen becomes a Feral Egg at her tier.
                FeralEgg.onItemLoad(item, world, cfg);
            }
        });
    }

    /**
     * Per-interval player update: refresh leveling-driven attributes (armor / bonus hearts / shield),
     * then run XP-funded regen. Runs regardless of whether regen is enabled so the visual layers stay current.
     */
    private void registerPlayerTick() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            StatBoards.tick(server); // renders on its own slower interval
            com.k33bz.sanctuary.grave.Gravekeeper.tickCouriers(server);
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
                AfkTracker.tick(player, cfg);
            }
            AnchorInteraction.pulseAnchors(server); // focus pulse at active anchors
            com.k33bz.sanctuary.anchor.AnchorUpkeep.tick(server, cfg);
            com.k33bz.sanctuary.grave.Graves.sweep(server, cfg);
            com.k33bz.sanctuary.metrics.KillMetrics.flush(); // no-op unless new kills landed
            com.k33bz.sanctuary.metrics.KillEventLog.flush();
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
            if (!(entity instanceof ServerPlayer player)) {
                return true;
            }
            if (cfg.lethalSaveEnabled) {
                int needed = SurvivalLogic.lethalSaveLevelCost(amount, cfg.lethalSaveLevelsPerDamage, cfg.lethalSaveMinLevels);
                if (player.experienceLevel >= needed) {
                    player.giveExperienceLevels(-needed);
                    player.setHealth(cfg.lethalSaveReviveHealth);
                    LOGGER.info("[sanctuary] Lethal save: {} spent {} level(s)", player.getName().getString(), needed);
                    return false; // cancel death
                }
            }
            // Death proceeds: seal the inventory into a grave (System 10) before vanilla
            // scatters it. Respects keepInventory by definition — an empty inventory after
            // vanilla's keep means capture() sees nothing... but capture runs FIRST, so gate
            // on the gamerule explicitly.
            if (cfg.gravesEnabled
                    && !player.level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY)) {
                com.k33bz.sanctuary.grave.Graves.capture(player, cfg);
            }
            return true;
        });
    }
}
