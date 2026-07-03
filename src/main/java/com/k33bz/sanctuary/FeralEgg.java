package com.k33bz.sanctuary;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import com.k33bz.sanctuary.siege.RabidAttackGoal;

import java.util.List;

/**
 * Feral Eggs — the breeding market for hostile poultry. An egg materializing beside a living
 * feral (rabid) hen becomes a Feral Egg carrying her tier: still a vanilla egg item, identified
 * by its styled name (the tier is the name's color, which an anvil cannot forge). Hatchlings are
 * born calm but destined — at adulthood they turn rabid at the parent tier, drifted one tier
 * down/same/up at hatch time (default 25/50/25, clamped Savage..Nightmare). The bloodline is
 * permanent: a sanctuary pacifies a destined bird while it stands inside, but the wilds wake it
 * again. Goal of the economy: gamble Ferocious eggs into Nightmare hens.
 */
public final class FeralEgg {
    private FeralEgg() {
    }

    /** Persistent destiny tag on a hatchling: {@code sanctuary_feral_destiny_<tier>}. */
    private static final String DESTINY_TAG_PREFIX = "sanctuary_feral_destiny_";

    private static final String NAME = "Feral Egg";

    /** How far a feral hen's influence reaches when an egg appears (blocks). */
    private static final double CONTAMINATION_RANGE = 1.0;

    /** How close the hatching projectile must be to the fresh chick (blocks). */
    private static final double HATCH_RANGE = 2.0;

    /** Representative damage bonus per tier — inside each band of {@link SurvivalLogic#mobTier}. */
    static double representativeDamageBonus(int tier) {
        return switch (tier) {
            case 2 -> 2.0;   // Savage band [1.5, 3.0)
            case 3 -> 4.0;   // Ferocious band [3.0, 5.0)
            default -> 6.0;  // Nightmare band [5.0, ∞)
        };
    }

    /** Turn a laid egg stack into a Feral Egg of the given tier (2..4). */
    public static ItemStack create(int tier, ItemStack laid) {
        ItemStack out = laid.copy();
        out.set(DataComponents.CUSTOM_NAME, Component.literal(NAME)
                .withStyle(style -> style.withColor(MobDifficulty.tierColor(tier)).withItalic(false)));
        out.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Something " + MobDifficulty.tierName(tier) + " stirs within.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("Hatchlings favor the bloodline... usually.")
                        .withStyle(ChatFormatting.DARK_GRAY))));
        out.set(DataComponents.RARITY, tier >= 4 ? Rarity.EPIC : tier == 3 ? Rarity.RARE : Rarity.UNCOMMON);
        return out;
    }

    /**
     * The parent tier a Feral Egg carries, or −1 for a plain egg / non-egg. Identification is the
     * styled name: the text must match AND the color must be a tier color — an anvil can rename an
     * egg but writes unstyled text, so market fakes stay fake.
     */
    public static int tierOf(ItemStack stack) {
        if (!stack.is(Items.EGG)) {
            return -1;
        }
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null || !NAME.equals(name.getString())) {
            return -1;
        }
        TextColor color = name.getStyle().getColor();
        if (color == null) {
            return -1;
        }
        for (int tier = 2; tier <= 4; tier++) {
            if (color.equals(TextColor.fromLegacyFormat(MobDifficulty.tierColor(tier)))) {
                return tier;
            }
        }
        return -1;
    }

    /**
     * An egg item just appeared in the world: if a living feral hen stands within a block, her
     * clutch turns feral at her tier. No thrower check needed beyond the owner gate — eggs a
     * player tosses on the ground keep their thrower and are ignored; the hen's own eggs have
     * none. (Yes, this means her aura also claims eggs a dropper feeds her. That's not a bug,
     * that's a farm.)
     */
    public static void onItemLoad(ItemEntity item, ServerLevel level, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        if (!ms.enabled || !ms.rabidEnabled || !ms.feralEggsEnabled) {
            return;
        }
        ItemStack stack = item.getItem();
        if (!stack.is(Items.EGG) || tierOf(stack) >= 0 || item.getOwner() != null) {
            return;
        }
        List<Animal> hens = level.getEntitiesOfClass(Animal.class,
                item.getBoundingBox().inflate(CONTAMINATION_RANGE),
                a -> a.isAlive() && a.entityTags().contains(RabidAttackGoal.RABID_TAG));
        for (Animal hen : hens) {
            int tier = MobDifficulty.tierOf(hen, ms);
            if (tier >= 2) {
                item.setItem(create(tier, stack));
                return;
            }
        }
    }

    /**
     * A baby animal just spawned: if a Feral Egg projectile is hatching right there, roll the
     * bloodline drift (down/same/up) against the egg's parent tier and stamp the chick's destiny.
     * The chick stays calm until adulthood — {@code MobDifficulty.onAnimalLoad} does the turning.
     */
    public static void onBabyLoad(Animal baby, ServerLevel level, SanctuaryConfig cfg) {
        SanctuaryConfig.MobScaling ms = cfg.mobScaling;
        if (!ms.enabled || !ms.rabidEnabled || !ms.feralEggsEnabled || destinyOf(baby) >= 0) {
            return;
        }
        List<ThrownEgg> eggs = level.getEntitiesOfClass(ThrownEgg.class,
                baby.getBoundingBox().inflate(HATCH_RANGE), egg -> tierOf(egg.getItem()) >= 2);
        if (eggs.isEmpty()) {
            return;
        }
        int parent = tierOf(eggs.get(0).getItem());
        int destiny = SurvivalLogic.feralEggDestinyTier(parent, baby.getRandom().nextDouble(),
                ms.feralEggDownChance, ms.feralEggUpChance);
        baby.addTag(DESTINY_TAG_PREFIX + destiny);
    }

    /** The tier a destined bird will turn at, or −1 if it carries no bloodline. */
    public static int destinyOf(Mob mob) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(DESTINY_TAG_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(DESTINY_TAG_PREFIX.length()));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
