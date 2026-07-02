package com.k33bz.sanctuary;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Applies leveling-driven survivability to a player as real, vanilla-rendered attributes:
 * bonus max-health hearts, armor icons, and an absorption "XP shield".
 *
 * <p>Modifiers are <em>transient</em> (not saved to the player file) and re-applied on an interval,
 * so they always reflect the current XP level and cleanly disappear when a system is toggled off.
 */
public final class LevelAttributes {
    private LevelAttributes() {
    }

    private static final Identifier HEALTH_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "level_health");
    private static final Identifier ARMOR_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "level_armor");
    private static final Identifier OXYGEN_ID = Identifier.fromNamespaceAndPath(Sanctuary.MOD_ID, "level_oxygen");

    /** Recompute and apply health/armor/shield for one player from their current XP level. */
    public static void apply(ServerPlayer player, SanctuaryConfig cfg) {
        int level = player.experienceLevel;
        int[] milestones = cfg.milestonesArray();

        double healthBonus = cfg.heartsEnabled ? SurvivalLogic.bonusHealth(level, milestones, cfg.hpPerMilestone) : 0.0;
        setModifier(player, Attributes.MAX_HEALTH, HEALTH_ID, cfg.heartsEnabled, healthBonus);

        double armor = cfg.armorEnabled ? SurvivalLogic.armorForLevel(level, cfg.armorPerLevel, cfg.armorMax) : 0.0;
        setModifier(player, Attributes.ARMOR, ARMOR_ID, cfg.armorEnabled, armor);

        double oxygen = cfg.breathEnabled ? SurvivalLogic.oxygenBonusForLevel(level, cfg.oxygenPerLevel, cfg.oxygenMax) : 0.0;
        setModifier(player, Attributes.OXYGEN_BONUS, OXYGEN_ID, cfg.breathEnabled, oxygen);

        // Max-health modifier is applied above, so getMaxHealth() already includes the bonus hearts.
        // Clamp current health if a milestone was lost (e.g. XP drained below it).
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }

        // Shield must be delivered via the Absorption effect: vanilla wipes a raw setAbsorptionAmount()
        // every tick. The effect grants absorption in 4-HP steps, so the target quantizes.
        if (cfg.shieldEnabled) {
            float targetHp = SurvivalLogic.shieldAmount(level, milestones, player.getMaxHealth(), cfg.shieldMaxFraction);
            int amplifier = SurvivalLogic.absorptionAmplifier(targetHp);
            int reached = SurvivalLogic.milestonesReached(level, milestones);
            double cooldownSeconds = SurvivalLogic.shieldRegenCooldownSeconds(reached,
                    cfg.shieldRegenCooldownBase, cfg.shieldRegenCooldownPerMilestone, cfg.shieldRegenCooldownMin);
            boolean outOfCombat = Sanctuary.ticksSinceCombat(player) >= (long) (cooldownSeconds * 20.0);
            applyShield(player, amplifier, outOfCombat);
        } else {
            clearOurShield(player);
        }
    }

    private static void applyShield(ServerPlayer player, int amplifier, boolean outOfCombat) {
        if (amplifier < 0) {
            clearOurShield(player);
            return;
        }
        MobEffectInstance current = player.getEffect(MobEffects.ABSORPTION);
        boolean ours = current != null && current.isInfiniteDuration();
        // Don't stomp a stronger, externally-applied (finite) absorption, e.g. an enchanted golden apple.
        if (current != null && !ours && current.getAmplifier() > amplifier) {
            return;
        }
        float fullForAmplifier = 4.0f * (amplifier + 1);
        boolean correctAndFull = ours
                && current.getAmplifier() == amplifier
                && player.getAbsorptionAmount() >= fullForAmplifier - 0.01f;

        // (Re)grant the shield ONLY when out of combat. Vanilla deletes the absorption effect the moment
        // it hits 0, so a depleted shield looks "missing" — but that must NOT be treated as a fresh grant,
        // or it refills mid-fight. On join/relog the player is out of combat, so the shield still applies
        // right away. In combat the shield decays and stays gone until the out-of-combat cooldown passes.
        if (!correctAndFull && outOfCombat) {
            player.removeEffect(MobEffects.ABSORPTION);
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION,
                    MobEffectInstance.INFINITE_DURATION, amplifier, false, false, false));
        }
    }

    /** Remove only our (infinite) absorption; leave finite effects like golden apples alone. */
    private static void clearOurShield(ServerPlayer player) {
        MobEffectInstance current = player.getEffect(MobEffects.ABSORPTION);
        if (current != null && current.isInfiniteDuration()) {
            player.removeEffect(MobEffects.ABSORPTION);
        }
    }

    private static void setModifier(ServerPlayer player, Holder<Attribute> attribute, Identifier id,
                                    boolean enabled, double amount) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        inst.removeModifier(id); // clear any prior value so we never stack
        if (enabled && amount != 0.0) {
            inst.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
