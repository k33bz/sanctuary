package com.k33bz.sanctuary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A traveller's kit, handed to each player ONCE on their first join — Middle-earth fare instead of
 * empty pockets: waybread, a cordial, and clothes for the road.
 *
 * <p>Everything is a VANILLA item wearing data components (custom name, lore, food, dye, potion), never a
 * new item type. That keeps the mod server-side: a stock client renders every piece correctly with no
 * resource pack and no modpack requirement. It is also why ordinary bread is untouched — Lembas is its own
 * named stack carrying its own food component, so a wheat farm doesn't quietly delete hunger from the game.
 */
public final class StartingKit {
    private StartingKit() {
    }

    /** Persisted roster of players already outfitted. Public field for trivial Gson (de)serialization. */
    public static final class Roster {
        public List<String> given = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Roster state;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_kit.json");
    }

    private static Roster state() {
        if (state == null) {
            try {
                if (Files.exists(path())) {
                    state = GSON.fromJson(Files.readString(path()), Roster.class);
                }
            } catch (Exception e) {
                // Starting empty here would re-gift EVERY player on the server, so say so loudly.
                Sanctuary.LOGGER.warn("[sanctuary] could not read the kit roster; nobody will be re-gifted "
                        + "until it is readable again", e);
                state = new Roster();
                state.given = null; // force the guard below to leave us in a safe, non-gifting state
            }
            if (state == null) {
                state = new Roster();
            }
            if (state.given == null) {
                state.given = new ArrayList<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.writeString(path(), GSON.toJson(state()));
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] failed to save the kit roster", e);
        }
    }

    /** First-join hook. Idempotent: the roster is written BEFORE the grant, so a failure mid-grant can
     *  never hand out a second kit on rejoin (better a missing sock than an infinite lembas dispenser). */
    public static void onJoin(ServerPlayer player) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        if (cfg == null || !cfg.startingKitEnabled) {
            return;
        }
        String id = player.getUUID().toString();
        Roster r = state();
        if (r.given.contains(id)) {
            return;
        }
        r.given.add(id);
        save();
        grant(player);
        player.sendSystemMessage(Component.literal(
                        "You set out with a traveller's kit — waybread for the road, and a cordial against the cold.")
                .withStyle(ChatFormatting.GOLD));
    }

    private static void grant(ServerPlayer p) {
        // Worn, not carried: breeches and boots go straight on. Only if those slots are empty — a rejoining
        // player who somehow slipped the roster shouldn't have their gear silently replaced.
        if (p.getItemBySlot(EquipmentSlot.LEGS).isEmpty()) {
            p.setItemSlot(EquipmentSlot.LEGS, breeches());
        } else {
            give(p, breeches());
        }
        if (p.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
            p.setItemSlot(EquipmentSlot.FEET, boots());
        } else {
            give(p, boots());
        }
        give(p, lembas(2));
        give(p, cram(6));
        give(p, honeyCake(2));
        give(p, miruvor());
        give(p, entDraught());
    }

    /** Drop-safe give: a full inventory spills to the ground rather than voiding the item. */
    private static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) {
            p.drop(s, false);
        }
    }

    // --- the fare (List of Middle-earth food and drink) ---

    /** Lembas — the waybread of the Elves. One bite fills a grown man's belly, so: the whole hunger bar. */
    private static ItemStack lembas(int count) {
        ItemStack s = new ItemStack(Items.BREAD, count);
        named(s, "Lembas", ChatFormatting.AQUA);
        // nutrition 20 = the entire bar. canAlwaysEat: waybread sustains you even when you aren't hungry.
        s.set(DataComponents.FOOD, new FoodProperties(20, 1.0f, true));
        lore(s, "Elvish waybread, wrapped in mallorn leaves.",
                "One bite fills the belly of a grown man.");
        s.set(DataComponents.RARITY, Rarity.UNCOMMON);
        return s;
    }

    /** Cram — the dry, dull travelling biscuit of Dale and the Lake-men. Sustaining, never pleasant. */
    private static ItemStack cram(int count) {
        ItemStack s = new ItemStack(Items.COOKIE, count);
        named(s, "Cram", ChatFormatting.GRAY);
        s.set(DataComponents.FOOD, new FoodProperties(4, 0.6f, false));
        lore(s, "A biscuit of Dale, baked for long journeys.",
                "Nourishing. Chewing it is its own reward, and its only one.");
        return s;
    }

    /** Honey-cakes — Beorn's, from The Hobbit. Rich enough that guests kept eating past sense. */
    private static ItemStack honeyCake(int count) {
        ItemStack s = new ItemStack(Items.PUMPKIN_PIE, count);
        named(s, "Honey-cake", ChatFormatting.GOLD);
        s.set(DataComponents.FOOD, new FoodProperties(8, 0.8f, false));
        lore(s, "Beorn's twice-baked honey-cakes.",
                "Sweet enough that guests forget to stop.");
        return s;
    }

    /** Miruvor — the cordial of Imladris. A mouthful puts fresh strength in the weary. */
    private static ItemStack miruvor() {
        ItemStack s = new ItemStack(Items.POTION);
        named(s, "Miruvor", ChatFormatting.LIGHT_PURPLE);
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.REGENERATION));
        lore(s, "The cordial of Imladris, carried by Gandalf over Caradhras.",
                "A mouthful puts new strength in the weary.");
        s.set(DataComponents.RARITY, Rarity.UNCOMMON);
        return s;
    }

    /** Ent-draught — Fangorn's water. It grew the hobbits taller; here it settles for making you stronger. */
    private static ItemStack entDraught() {
        ItemStack s = new ItemStack(Items.POTION);
        named(s, "Ent-draught", ChatFormatting.DARK_GREEN);
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRENGTH));
        lore(s, "Drawn from the springs of Fangorn.",
                "It made hobbits taller. It will settle for making you stronger.");
        return s;
    }

    // --- clothes for the road ---

    private static ItemStack breeches() {
        ItemStack s = new ItemStack(Items.LEATHER_LEGGINGS);
        named(s, "Traveller's Breeches", ChatFormatting.YELLOW);
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(0x6B4F2A)); // road-dust brown
        lore(s, "Stout cloth, patched at the knee.");
        return s;
    }

    private static ItemStack boots() {
        ItemStack s = new ItemStack(Items.LEATHER_BOOTS);
        named(s, "Well-worn Boots", ChatFormatting.YELLOW);
        s.set(DataComponents.DYED_COLOR, new DyedItemColor(0x4A3520)); // older, darker leather
        lore(s, "They have walked further than you have.");
        return s;
    }

    // --- helpers ---

    /** Italics off: a custom_name renders italic by default, which reads as "renamed in an anvil". */
    private static void named(ItemStack s, String label, ChatFormatting colour) {
        s.set(DataComponents.CUSTOM_NAME,
                Component.literal(label).withStyle(st -> st.withColor(colour).withItalic(false)));
    }

    private static void lore(ItemStack s, String... lines) {
        List<Component> out = new ArrayList<>();
        for (String line : lines) {
            out.add(Component.literal(line).withStyle(st -> st
                    .withColor(ChatFormatting.DARK_GRAY).withItalic(true)));
        }
        s.set(DataComponents.LORE, new ItemLore(out));
    }
}
