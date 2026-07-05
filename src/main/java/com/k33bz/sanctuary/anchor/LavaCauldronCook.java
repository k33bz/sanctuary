package com.k33bz.sanctuary.anchor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.util.List;
import java.util.Locale;

/**
 * The lava-cauldron temper: a Raw {@link WildMembrane} item entity resting in a {@code LAVA_CAULDRON}
 * (STRICTLY lava — not water, not powder snow, not open lava/fire) is cooked into a finished Wild
 * Membrane. Server-side, no mixin, no furnace: a periodic sweep of loaded item entities.
 *
 * <p>On finding an eligible raw membrane over a lava cauldron it tags the entity (so a cook is never
 * started twice) and remembers the tick it started. After {@link #COOK_TICKS} it consumes the raw
 * item, empties the cauldron to a plain {@link Blocks#CAULDRON} (the lava is spent when
 * {@code beaconLavaConsumed} is true — the default), and pops a finished Wild Membrane up out of the
 * cauldron. Bubbling particles/sound play throughout via command strings, matching the mod's effect
 * idiom (see {@link AnchorInteraction}). The raw item's fire-resistance keeps it from burning before
 * the timer completes.
 */
public final class LavaCauldronCook {
    private LavaCauldronCook() {
    }

    /** Tag marking an item entity that has begun cooking (guards against double-processing). */
    public static final String COOKING_TAG = "sanctuary_membrane_cooking";
    /** Cook duration in ticks (~4s at 20 tps). */
    public static final int COOK_TICKS = 80;

    /** Entity id -> game-time tick the cook began. Cleared when the entity finishes or leaves lava. */
    private static final java.util.Map<Integer, Long> COOK_STARTED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The pure eligibility predicate: is a raw membrane resting in a LAVA cauldron (and only lava)?
     * Exposed (block-state form) so unit tests can prove lava-cauldron cooks and water-cauldron
     * doesn't, without a live item entity.
     */
    public static boolean isLavaCauldronCook(ItemStack stack, net.minecraft.world.level.block.state.BlockState atPos) {
        return WildMembrane.isRaw(stack) && atPos.is(Blocks.LAVA_CAULDRON);
    }

    /** Sweep loaded item entities in every scaling dimension and advance any lava-cauldron cooks. */
    public static void sweep(MinecraftServer server, SanctuaryConfig cfg) {
        if (server == null || cfg == null || !cfg.wildEssenceEnabled) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!cfg.isScalingDimension(level)) {
                continue;
            }
            long now = level.getGameTime();
            // Only Raw Wild Membrane item entities are ever candidates — cheap to enumerate.
            List<? extends ItemEntity> items = level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(ItemEntity.class),
                    e -> !e.isRemoved() && WildMembrane.isRaw(e.getItem()));
            for (ItemEntity item : items) {
                tick(level, item, cfg, now);
            }
        }
    }

    private static void tick(ServerLevel level, ItemEntity item, SanctuaryConfig cfg, long now) {
        ItemStack stack = item.getItem();
        BlockPos pos = item.blockPosition();
        boolean eligible = isLavaCauldronCook(stack, level.getBlockState(pos));
        if (!eligible) {
            // Drifted out of the cauldron before finishing — forget it so it can restart later.
            if (item.entityTags().contains(COOKING_TAG)) {
                item.removeTag(COOKING_TAG);
                COOK_STARTED.remove(item.getId());
            }
            return;
        }

        // Keep the raw entity from expiring mid-cook and make sure only the first stack cooks.
        item.setExtendedLifetime();
        if (!item.entityTags().contains(COOKING_TAG)) {
            item.addTag(COOKING_TAG);
            COOK_STARTED.put(item.getId(), now);
            bubble(level, pos); // an opening splash of bubbling
        }

        Long started = COOK_STARTED.get(item.getId());
        if (started == null) {
            COOK_STARTED.put(item.getId(), now);
            return;
        }
        long elapsed = now - started;
        if (elapsed % 20L == 0L && elapsed < COOK_TICKS) {
            bubble(level, pos); // periodic bubbling while it tempers
        }
        if (elapsed < COOK_TICKS) {
            return;
        }

        // Done: consume the raw item, empty the lava cauldron, pop the finished membrane out.
        COOK_STARTED.remove(item.getId());
        stack.shrink(1);
        if (stack.isEmpty()) {
            item.discard();
        } else {
            item.setItem(stack);
            item.removeTag(COOKING_TAG);
        }
        if (cfg.beaconLavaConsumed) {
            level.setBlockAndUpdate(pos, Blocks.CAULDRON.defaultBlockState());
        }
        finish(level, pos);

        ItemEntity result = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, WildMembrane.create());
        result.setDeltaMovement(0.0, 0.2, 0.0);
        level.addFreshEntity(result);
        Sanctuary.LOGGER.info("[sanctuary] A Raw Wild Membrane was tempered in lava at {}", pos);
    }

    /**
     * Immediately temper the first Raw Wild Membrane item entity resting over a lava cauldron at
     * {@code pos} (bot-harness backend; skips the natural timer). Returns true if one was cooked.
     */
    public static boolean forceCook(ServerLevel level, BlockPos pos, SanctuaryConfig cfg) {
        if (cfg == null || !cfg.wildEssenceEnabled || !level.getBlockState(pos).is(Blocks.LAVA_CAULDRON)) {
            return false;
        }
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                new AABB(pos).inflate(0.5), e -> !e.isRemoved() && WildMembrane.isRaw(e.getItem()));
        if (items.isEmpty()) {
            return false;
        }
        ItemEntity item = items.get(0);
        COOK_STARTED.remove(item.getId());
        ItemStack stack = item.getItem();
        stack.shrink(1);
        if (stack.isEmpty()) {
            item.discard();
        } else {
            item.setItem(stack);
            item.removeTag(COOKING_TAG);
        }
        if (cfg.beaconLavaConsumed) {
            level.setBlockAndUpdate(pos, Blocks.CAULDRON.defaultBlockState());
        }
        finish(level, pos);
        ItemEntity result = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, WildMembrane.create());
        result.setDeltaMovement(0.0, 0.2, 0.0);
        level.addFreshEntity(result);
        Sanctuary.LOGGER.info("[sanctuary] Forced temper of a Raw Wild Membrane at {}", pos);
        return true;
    }

    private static void bubble(ServerLevel level, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.55;
        double cz = pos.getZ() + 0.5;
        run(level, String.format(Locale.ROOT,
                "particle minecraft:lava %.2f %.2f %.2f 0.2 0.1 0.2 0.0 6 force", cx, cy, cz));
        run(level, String.format(Locale.ROOT,
                "particle minecraft:smoke %.2f %.2f %.2f 0.2 0.1 0.2 0.02 4 force", cx, cy + 0.2, cz));
        run(level, String.format(Locale.ROOT,
                "playsound minecraft:block.bubble_column.bubble_pop block @a %.2f %.2f %.2f 0.6 1.0", cx, cy, cz));
    }

    private static void finish(ServerLevel level, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.6;
        double cz = pos.getZ() + 0.5;
        run(level, String.format(Locale.ROOT,
                "particle minecraft:cloud %.2f %.2f %.2f 0.2 0.2 0.2 0.02 20 force", cx, cy, cz));
        run(level, String.format(Locale.ROOT,
                "playsound minecraft:block.lava.extinguish block @a %.2f %.2f %.2f 1.0 1.0", cx, cy, cz));
    }

    private static void run(ServerLevel level, String command) {
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack().withSuppressedOutput(), command);
    }
}
