package com.k33bz.sanctuary.grave;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;
import com.k33bz.sanctuary.StatBoards;
import com.k33bz.sanctuary.SurvivalLogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native graves + sanctuary graveyards. On death the player's inventory is sealed into a grave
 * at the death site (items live in JSON, not in-world — despawn/hopper/regen proof). Headstone
 * = block-display stone slab + the owner's shrunk head affixed on top + label + an interaction
 * hitbox: pristine stone bricks while loot remains, cracked once looted. Real-time lifecycle:
 * after {@code graveDriftHours} the grave drifts to the nearest sanctuary's graveyard (grid
 * plots around a command-defined center); after {@code gravePublicHours} it turns red and
 * anyone may loot it. Claiming from a graveyard costs a small XP fee; the Gravekeeper NPC
 * summons remote graves for a bigger one. Never enable the bundled VT graves pack alongside
 * this — both would capture the same inventory.
 */
public final class Graves {
    private Graves() {
    }

    public static final String GRAVE_TAG = "sanctuary_grave";

    public static final class Grave {
        public String id;
        public String owner;        // uuid
        public String ownerName;
        public String dim;
        public double x, y, z;
        public long diedAtMs;
        public boolean inGraveyard;
        public String graveyardAnchor; // anchor id hosting it, if drifted
        public boolean looted;
        public List<JsonElement> items = new ArrayList<>();
        /** displays spawned flag — false when the grave moved and old displays may linger. */
        public boolean displaysFresh;
    }

    public static final class Yard {
        public String anchorId;   // "config" allowed for the spawn anchor
        public String dim;
        public int x, y, z;       // center
        public int radius;
        public String keeperUuid; // gravekeeper entity uuid, if spawned
    }

    public static final class Store {
        public List<Grave> graves = new ArrayList<>();
        public List<Yard> yards = new ArrayList<>();
    }

    private static Store store;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Long> CLAIM_COOLDOWN = new ConcurrentHashMap<>();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_graves.json");
    }

    public static Store store() {
        if (store == null) {
            try {
                if (Files.exists(path())) {
                    store = GSON.fromJson(Files.readString(path()), new TypeToken<Store>() { }.getType());
                }
            } catch (Exception e) {
                Sanctuary.LOGGER.warn("[sanctuary] could not read graves store", e);
            }
            if (store == null) {
                store = new Store();
            }
        }
        return store;
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(store()));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] could not save graves", e);
        }
    }

    // --- capture ---

    /**
     * Seal the dying player's inventory into a grave. Called from the death gate AFTER the
     * lethal-save decision, BEFORE vanilla scatters drops; the inventory is cleared here so
     * nothing hits the ground. No grave for empty inventories.
     */
    public static void capture(ServerPlayer player, SanctuaryConfig cfg) {
        var inv = player.getInventory();
        var ops = RegistryOps.create(JsonOps.INSTANCE, player.level().registryAccess());
        List<JsonElement> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(items::add);
        }
        if (items.isEmpty()) {
            return;
        }
        inv.clearContent();

        Grave grave = new Grave();
        grave.id = Long.toHexString(player.level().getGameTime()) + "g" + store().graves.size();
        grave.owner = player.getUUID().toString();
        grave.ownerName = player.getName().getString();
        grave.dim = player.level().dimension().identifier().toString();
        BlockPos pos = groundedPos((ServerLevel) player.level(), player.blockPosition());
        grave.x = pos.getX() + 0.5;
        grave.y = pos.getY();
        grave.z = pos.getZ() + 0.5;
        grave.diedAtMs = System.currentTimeMillis();
        store().graves.add(grave);
        save();
        spawnDisplays((ServerLevel) player.level(), grave);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "Your belongings rest in a grave at %d %d %d.", pos.getX(), pos.getY(), pos.getZ()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /** Nudge a death position to something standable (void/lava deaths land on the surface). */
    private static BlockPos groundedPos(ServerLevel level, BlockPos pos) {
        if (pos.getY() < level.getMinY() + 2) {
            return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        }
        for (int dy = 0; dy > -8; dy--) {
            BlockPos p = pos.offset(0, dy, 0);
            if (!level.getBlockState(p.below()).isAir()) {
                return p;
            }
        }
        return pos;
    }

    // --- headstone displays ---

    /** Spawn (or respawn) the headstone display cluster for a grave at its current position. */
    public static void spawnDisplays(ServerLevel level, Grave grave) {
        killDisplays(level, grave);
        String block = grave.looted ? "minecraft:cracked_stone_bricks" : "minecraft:stone_bricks";
        String tag = GRAVE_TAG + "_" + grave.id;
        // headstone slab
        run(level, String.format(Locale.ROOT,
                "summon minecraft:block_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],"
                        + "block_state:{Name:\"%s\"},transformation:{left_rotation:[0f,0f,0f,1f],"
                        + "right_rotation:[0f,0f,0f,1f],translation:[-0.35f,0f,-0.1f],scale:[0.7f,0.9f,0.2f]}}",
                grave.x, grave.y, grave.z, GRAVE_TAG, tag, block));
        // the owner's shrunk head, affixed on top
        run(level, String.format(Locale.ROOT,
                "summon minecraft:item_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],"
                        + "item:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":\"%s\"}},"
                        + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                        + "translation:[0f,1.05f,0f],scale:[0.45f,0.45f,0.45f]}}",
                grave.x, grave.y, grave.z, GRAVE_TAG, tag, grave.ownerName));
        // label
        boolean isPublic = isPublic(grave);
        String color = grave.looted ? "dark_gray" : isPublic ? "red" : "gray";
        String line2 = grave.looted ? "taken by the living" : isPublic ? "unclaimed — free to any hand" : "rests undisturbed";
        run(level, String.format(Locale.ROOT,
                "summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],billboard:\"vertical\","
                        + "text:{text:\"Here lies %s\\n\",color:\"white\",extra:[{text:\"%s\",color:\"%s\"}]}}",
                grave.x, grave.y + 1.55, grave.z, GRAVE_TAG, tag, grave.ownerName, line2, color));
        // interaction hitbox for claiming
        run(level, String.format(Locale.ROOT,
                "summon minecraft:interaction %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],width:0.9f,height:1.4f}",
                grave.x, grave.y, grave.z, GRAVE_TAG, tag));
        grave.displaysFresh = true;
    }

    public static void killDisplays(ServerLevel level, Grave grave) {
        run(level, "kill @e[tag=" + GRAVE_TAG + "_" + grave.id + "]");
    }

    // --- lifecycle ---

    public static boolean isPublic(Grave grave) {
        SanctuaryConfig cfg = Sanctuary.CONFIG;
        double hours = (System.currentTimeMillis() - grave.diedAtMs) / 3_600_000.0;
        return !grave.looted && hours >= (cfg == null ? 48 : cfg.gravePublicHours);
    }

    /** Real-time sweep: drift due graves into their nearest graveyard when chunks allow. */
    public static void sweep(MinecraftServer server, SanctuaryConfig cfg) {
        if (!cfg.gravesEnabled || store().graves.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (Grave grave : store().graves) {
            double hours = (now - grave.diedAtMs) / 3_600_000.0;
            if (!grave.inGraveyard && !grave.looted && hours >= cfg.graveDriftHours) {
                if (moveToYard(server, grave, null)) {
                    dirty = true;
                    notifyOwner(server, grave, "Your remains were carried to the sanctuary graveyard.");
                }
            } else if (!grave.looted && !grave.displaysFresh) {
                ServerLevel level = levelOf(server, grave.dim);
                if (level != null && level.isLoaded(BlockPos.containing(grave.x, grave.y, grave.z))) {
                    spawnDisplays(level, grave);
                    dirty = true;
                }
            }
        }
        if (dirty) {
            save();
        }
    }

    /** Move a grave into a graveyard's next free plot. Chunk-lazy: false if not possible yet. */
    public static boolean moveToYard(MinecraftServer server, Grave grave, Yard preferred) {
        Yard yard = preferred != null ? preferred : nearestYard(grave);
        if (yard == null) {
            return false;
        }
        ServerLevel oldLevel = levelOf(server, grave.dim);
        ServerLevel yardLevel = levelOf(server, yard.dim);
        if (oldLevel == null || yardLevel == null
                || !oldLevel.isLoaded(BlockPos.containing(grave.x, grave.y, grave.z))) {
            return false;
        }
        BlockPos plot = nextPlot(yardLevel, yard);
        if (plot == null || !yardLevel.isLoaded(plot)) {
            return false;
        }
        killDisplays(oldLevel, grave);
        grave.dim = yard.dim;
        grave.x = plot.getX() + 0.5;
        grave.y = plot.getY();
        grave.z = plot.getZ() + 0.5;
        grave.inGraveyard = true;
        grave.graveyardAnchor = yard.anchorId;
        spawnDisplays(yardLevel, grave);
        return true;
    }

    /** Grid plots: spiral over a 2-block lattice inside the yard radius, skipping taken spots. */
    private static BlockPos nextPlot(ServerLevel level, Yard yard) {
        for (int ring = 0; ring <= yard.radius / 2; ring++) {
            for (int gx = -ring; gx <= ring; gx++) {
                for (int gz = -ring; gz <= ring; gz++) {
                    if (Math.max(Math.abs(gx), Math.abs(gz)) != ring) {
                        continue;
                    }
                    double px = yard.x + gx * 2 + 0.5;
                    double pz = yard.z + gz * 2 + 0.5;
                    boolean taken = false;
                    for (Grave g : store().graves) {
                        if (g.inGraveyard && g.dim.equals(yard.dim)
                                && Math.abs(g.x - px) < 0.9 && Math.abs(g.z - pz) < 0.9) {
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) {
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                                (int) Math.floor(px), (int) Math.floor(pz));
                        return BlockPos.containing(px, y, pz);
                    }
                }
            }
        }
        return null;
    }

    public static Yard nearestYard(Grave grave) {
        Yard best = null;
        double bestSq = Double.MAX_VALUE;
        for (Yard yard : store().yards) {
            if (!yard.dim.equals(grave.dim)) {
                continue;
            }
            double dx = yard.x - grave.x, dz = yard.z - grave.z;
            double d = dx * dx + dz * dz;
            if (d < bestSq) {
                bestSq = d;
                best = yard;
            }
        }
        return best;
    }

    public static Yard yardNear(ServerPlayer player, double range) {
        String dim = player.level().dimension().identifier().toString();
        for (Yard yard : store().yards) {
            if (yard.dim.equals(dim)
                    && player.distanceToSqr(yard.x + 0.5, yard.y + 0.5, yard.z + 0.5) < range * range) {
                return yard;
            }
        }
        return null;
    }

    // --- claiming ---

    /** A right-click landed on a grave's interaction hitbox (matched by tag). */
    public static void tryClaim(ServerPlayer player, Grave grave, SanctuaryConfig cfg) {
        long now = System.currentTimeMillis();
        Long last = CLAIM_COOLDOWN.get(player.getUUID() + grave.id);
        if (last != null && now - last < 1000) {
            return;
        }
        CLAIM_COOLDOWN.put(player.getUUID() + grave.id, now);
        if (grave.looted) {
            player.sendSystemMessage(Component.literal("Only memories remain here.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        boolean owner = grave.owner.equals(player.getUUID().toString());
        if (!owner && !isPublic(grave)) {
            player.sendSystemMessage(Component.literal("This grave does not know you. Yet.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        // the gravekeeper's fee: owners claiming from a graveyard pay a small toll
        if (owner && grave.inGraveyard && cfg.graveClaimFeeFraction > 0) {
            int fee = SurvivalLogic.respawnCostLevels(player.experienceLevel,
                    cfg.graveClaimFeeFraction, 0, 0);
            if (player.experienceLevel < fee) {
                player.sendSystemMessage(Component.literal(
                                "The gravekeeper asks " + fee + " levels you do not have.")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (fee > 0) {
                player.giveExperienceLevels(-fee);
                StatBoards.addScore(player, "sanct_toll", fee);
            }
        }
        ServerLevel level = (ServerLevel) player.level();
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        int restored = 0;
        for (JsonElement el : grave.items) {
            var parsed = ItemStack.CODEC.parse(ops, el).result();
            if (parsed.isEmpty()) {
                continue;
            }
            ItemStack stack = parsed.get();
            if (!player.getInventory().add(stack)) {
                level.addFreshEntity(new ItemEntity(level, grave.x, grave.y + 0.6, grave.z, stack));
            }
            restored++;
        }
        grave.items.clear();
        grave.looted = true;
        spawnDisplays(level, grave); // headstone cracks
        save();
        player.sendSystemMessage(Component.literal(owner
                        ? "Your belongings return to you (" + restored + " stacks)."
                        : "You rob the grave of " + grave.ownerName + " (" + restored + " stacks).")
                .withStyle(owner ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED));
        if (!owner) {
            notifyOwner(level.getServer(), grave, "Your unclaimed grave was looted by " + player.getName().getString() + ".");
        }
    }

    public static Grave byId(String id) {
        for (Grave g : store().graves) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }

    private static void notifyOwner(MinecraftServer server, Grave grave, String message) {
        ServerPlayer owner = server.getPlayerList().getPlayer(java.util.UUID.fromString(grave.owner));
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    public static ServerLevel levelOf(MinecraftServer server, String dim) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
    }

    static void run(ServerLevel level, String command) {
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
