package com.k33bz.sanctuary.grave;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.StatBoards;

import java.util.Locale;
import java.util.Random;

/**
 * Grave robbing (System 10, issue #7). When a NON-owner breaks a WILD grave's protected block at
 * night (the block-break gate in {@code Sanctuary.registerAnchorBreak} calls {@link #canRob} then
 * {@link #rob}), the grave is dug up: the robber gains the buried soul (a flat XP reward) plus a
 * fraction of the goods, the rest shattering in the desecration; some yielded items come up damaged;
 * and the disturbed dead may rise and curse the robber. The headstone crumbles (displays + record
 * removed) and the owner is notified. Dice live in {@link GraveRobLogic}; this applies them.
 */
public final class GraveRob {
    private GraveRob() {
    }

    /** Tag on raised wraiths, so they read as event mobs (and could be swept later). */
    private static final String WRAITH_TAG = "sanctuary_grave_wraith";

    /** Whether {@code player} may dig up {@code grave} right now (all gates in {@link GraveRobLogic}). */
    public static boolean canRob(ServerPlayer player, Graves.Grave grave, SanctuaryConfig cfg,
                                 ServerLevel level) {
        if (grave == null || grave.owner == null) {
            return false;
        }
        boolean isOwner = grave.owner.equals(player.getUUID().toString());
        double elapsedHours = GraveLifecycle.elapsedDays(System.currentTimeMillis(), grave.diedAtMs) * 24.0;
        return GraveRobLogic.eligible(cfg.graveRobbingEnabled, isOwner, grave.inGraveyard, grave.looted,
                cfg.graveRobNightOnly, Gravekeeper.isNight(level), elapsedHours, cfg.graveRobbableAfterHours);
    }

    /**
     * Execute the rob. Assumes {@link #canRob} already passed. Transfers a fraction of the grave's
     * items (some damaged), grants soul XP, maybe raises wraiths + curses the robber, crumbles the
     * grave, and notifies the owner. The triggering block break is allowed by the caller.
     */
    public static void rob(ServerPlayer player, Graves.Grave grave, ServerLevel level, BlockPos pos,
                           SanctuaryConfig cfg) {
        Random rng = new Random();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());

        int taken = 0;
        int shattered = 0;
        for (JsonElement el : grave.items) {
            GraveRobLogic.Fate fate = GraveRobLogic.fate(rng,
                    cfg.graveRobItemYieldFraction, cfg.graveRobItemDamageFraction);
            if (fate == GraveRobLogic.Fate.SHATTER) {
                shattered++;
                continue;
            }
            var parsed = ItemStack.CODEC.parse(ops, el).result();
            if (parsed.isEmpty()) {
                continue;
            }
            ItemStack stack = parsed.get();
            if (fate == GraveRobLogic.Fate.YIELD_DAMAGED && stack.isDamageableItem()) {
                int dmg = GraveRobLogic.damageValue(rng, stack.getMaxDamage());
                if (dmg > 0) {
                    stack.setDamageValue(dmg);
                }
            }
            if (!player.getInventory().add(stack)) {
                level.addFreshEntity(new ItemEntity(level,
                        player.getX(), player.getY() + 0.4, player.getZ(), stack));
            }
            taken++;
        }
        grave.items.clear();

        // The buried soul — a flat reward (graves don't store the victim's XP; soul-retention keeps
        // that on the player), representing residue drawn up with the goods.
        if (cfg.graveRobXpPoints > 0) {
            player.giveExperiencePoints(cfg.graveRobXpPoints);
        }

        double px = pos.getX() + 0.5;
        double py = pos.getY() + 1.0;
        double pz = pos.getZ() + 0.5;

        boolean cursed = GraveRobLogic.wraithRises(rng, cfg.graveRobWraithChance);
        if (cursed) {
            for (int i = 0; i < cfg.graveRobWraithCount; i++) {
                run(level, String.format(Locale.ROOT,
                        "summon minecraft:zombie %d %d %d {Tags:[\"%s\"],PersistenceRequired:1b,"
                                + "CustomName:{text:\"Restless Wraith\",color:\"dark_purple\"},"
                                + "CustomNameVisible:0b}",
                        pos.getX(), pos.getY() + 1, pos.getZ(), WRAITH_TAG));
            }
            int ticks = cfg.graveRobCurseSeconds * 20;
            if (ticks > 0) {
                player.addEffect(new MobEffectInstance(MobEffects.WITHER, ticks, 0));
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0));
            }
            run(level, fx("particle minecraft:soul", px, py, pz, "0.4 0.8 0.4 0.02 30 force"));
            run(level, fx("particle minecraft:sculk_soul", px, py, pz, "0.4 0.8 0.4 0.02 20 force"));
            run(level, fx("playsound minecraft:entity.wither.spawn hostile @a", px, py, pz, "0.6 0.8"));
            player.sendSystemMessage(Component.literal("The disturbed dead rise against you!")
                    .withStyle(ChatFormatting.DARK_RED));
        } else {
            run(level, fx("particle minecraft:smoke", px, py, pz, "0.3 0.5 0.3 0.01 15 force"));
            run(level, fx("playsound minecraft:block.soul_soil.break block @a", px, py, pz, "0.7 0.7"));
        }

        // Crumble the headstone: remove displays + record. The triggering block break drops normally.
        Graves.killDisplays(level, grave);
        Graves.store().graves.remove(grave);
        Graves.save();

        // Robber feedback + records + owner notice.
        StatBoards.addScore(player, "sanct_robbed", 1);
        com.k33bz.sanctuary.metrics.GraveEventLog.record("robbed", grave.id, grave.ownerName,
                grave.dim, grave.x, grave.y, grave.z, taken, player.getName().getString(), true);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "You desecrate the grave of %s — %d stacks taken, %d lost to ruin.",
                        grave.ownerName, taken, shattered))
                .withStyle(ChatFormatting.RED));
        Graves.notifyOwner(level.getServer(), grave,
                "Your grave was dug up and robbed by " + player.getName().getString() + ".");
    }

    private static String fx(String prefix, double x, double y, double z, String tail) {
        return String.format(Locale.ROOT, "%s %.2f %.2f %.2f %s", prefix, x, y, z, tail);
    }

    private static void run(ServerLevel level, String command) {
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(), command);
    }
}
