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
 * feral (rabid) hen becomes a Feral Egg carrying her tier and her bloodline's star quality:
 * still a vanilla egg item, identified by its styled name ("Feral Egg ★★★" — the tier is the
 * name's color, which an anvil cannot forge; the stars are its quality). A wild-turned hen lays
 * 0★; each destined generation adds a star, capped at 5★. Most eggs hatch a plain chick — star
 * quality shifts the odds toward the bloodline and, rarely, a tier above it (see
 * {@link SurvivalLogic#feralEggHatchOutcome}). Destined hatchlings are born calm carrying hidden
 * destiny + generation tags (the bird itself never shows stars); at adulthood they turn rabid at
 * the destined tier. A sanctuary pacifies a destined bird while it stands inside, but the
 * bloodline survives and the wilds wake it again. Five lucky generations to 5★ Nightmare stock.
 */
public final class FeralEgg {
    private FeralEgg() {
    }

    /** Persistent destiny tag on a hatchling: {@code sanctuary_feral_destiny_<tier>}. */
    private static final String DESTINY_TAG_PREFIX = "sanctuary_feral_destiny_";

    /** Persistent bloodline-generation tag: {@code sanctuary_feral_gen_<n>} (never displayed). */
    private static final String GENERATION_TAG_PREFIX = "sanctuary_feral_gen_";

    private static final String NAME = "Feral Egg";

    /** Star glyph Minecraft's font renders crisply (same reasoning as the threat skulls). */
    private static final String STAR = "★";

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

    /** Turn a laid egg stack into a Feral Egg of the given tier (2..4) and quality (0..5 stars). */
    public static ItemStack create(int tier, int stars, ItemStack laid) {
        ItemStack out = laid.copy();
        String display = stars > 0 ? NAME + " " + STAR.repeat(stars) : NAME;
        out.set(DataComponents.CUSTOM_NAME, Component.literal(display)
                .withStyle(style -> style.withColor(MobDifficulty.tierColor(tier)).withItalic(false)));
        out.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Something " + MobDifficulty.tierName(tier) + " stirs within.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal(stars > 0
                                ? "Generation " + stars + " of a proud, unhinged line."
                                : "First of her line. Probably just breakfast.")
                        .withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("A Zugzuggin' original.")
                        .withStyle(ChatFormatting.DARK_PURPLE))));
        out.set(DataComponents.RARITY, tier >= 4 ? Rarity.EPIC : tier == 3 ? Rarity.RARE : Rarity.UNCOMMON);
        return out;
    }

    /**
     * The parent tier a Feral Egg carries, or −1 for a plain egg / non-egg. Identification is the
     * styled name: the text must be "Feral Egg" (+ optional stars) AND the color must be a tier
     * color — an anvil can rename an egg but writes unstyled text, so market fakes stay fake.
     */
    public static int tierOf(ItemStack stack) {
        if (!stack.is(Items.EGG)) {
            return -1;
        }
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null || parseStars(name.getString()) < 0) {
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

    /** Star quality of a Feral Egg (0..5), or −1 if the stack isn't one. */
    public static int starsOf(ItemStack stack) {
        if (tierOf(stack) < 0) {
            return -1;
        }
        return parseStars(stack.get(DataComponents.CUSTOM_NAME).getString());
    }

    /** Stars from a display name: "Feral Egg" → 0, "Feral Egg ★★★" → 3, anything else → −1. */
    private static int parseStars(String display) {
        if (NAME.equals(display)) {
            return 0;
        }
        if (!display.startsWith(NAME + " ")) {
            return -1;
        }
        String suffix = display.substring(NAME.length() + 1);
        if (suffix.isEmpty() || suffix.length() > 5 || !suffix.chars().allMatch(c -> c == STAR.charAt(0))) {
            return -1;
        }
        return suffix.length();
    }

    /**
     * An egg item just appeared in the world: if a living feral hen stands within a block, her
     * clutch turns feral at her tier and her bloodline's star quality (wild-turned hens are 0★).
     * No thrower check needed beyond the owner gate — eggs a player tosses on the ground keep
     * their thrower and are ignored; the hen's own eggs have none. (Yes, this means her aura also
     * claims eggs a dropper feeds her. That's not a bug, that's a farm.)
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
                item.setItem(create(tier, SurvivalLogic.feralEggStars(generationOf(hen)), stack));
                return;
            }
        }
    }

    /**
     * A baby animal just spawned: if a Feral Egg projectile is hatching right there, roll the
     * star table against the egg's parent tier. Most rolls hatch a plain chick and the bloodline
     * ends; a destined chick gets hidden destiny + generation tags (one generation deeper than
     * the egg's stars) and stays calm until adulthood — {@code MobDifficulty.onAnimalLoad} does
     * the turning.
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
        ItemStack eggStack = eggs.get(0).getItem();
        int parent = tierOf(eggStack);
        int stars = Math.max(0, starsOf(eggStack));
        int destiny = SurvivalLogic.feralEggHatchOutcome(parent, stars, baby.getRandom().nextDouble());
        if (destiny < 2) {
            return; // a perfectly ordinary chick — the line ends here
        }
        baby.addTag(DESTINY_TAG_PREFIX + destiny);
        baby.addTag(GENERATION_TAG_PREFIX + SurvivalLogic.feralEggStars(stars + 1));
    }

    /** The tier a destined bird will turn at, or −1 if it carries no bloodline. */
    public static int destinyOf(Mob mob) {
        return taggedNumber(mob, DESTINY_TAG_PREFIX);
    }

    /** A bloodline bird's generation (= the stars her eggs carry); 0 for wild-turned/untagged. */
    public static int generationOf(Mob mob) {
        return Math.max(0, taggedNumber(mob, GENERATION_TAG_PREFIX));
    }

    private static int taggedNumber(Mob mob, String prefix) {
        for (String tag : mob.entityTags()) {
            if (tag.startsWith(prefix)) {
                try {
                    return Integer.parseInt(tag.substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
