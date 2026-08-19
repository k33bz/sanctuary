package com.k33bz.sanctuary.rift;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.Structure;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Gathering-world isolation policy: the single place that decides what the resource dimension is NOT.
 *
 * <p>The gathering world exists to be strip-mined and thrown away on the weekly reset, so anything that
 * would let a player carry progress OUT of it, or that spends worldgen budget on content nobody is meant
 * to explore, is suppressed here:
 * <ul>
 *   <li><b>No Nether gates.</b> {@code PortalShape.findEmptyPortalShape} is the sole chokepoint every
 *       ignition path funnels through ({@code BaseFireBlock} is its only caller), so returning empty there
 *       means a frame never lights, whether by hand, by dispenser, by fire charge, by lava spread or by ghast
 *       fireball. The frame can still be built; it just stays inert. {@link RiftSeal} keeps the
 *       player-facing refusal message and the end-portal / stray-portal-block backstops.</li>
 *   <li><b>No structures.</b> {@code ChunkGenerator.tryGenerateStructure} carries both the candidate
 *       structure and the dimension key, so suppression is exact and per-dimension: the home world keeps
 *       every structure it has. Covers ruined portals (all seven variants), strongholds, villages, swamp
 *       huts, trail ruins, trial chambers, ancient cities and anything a worldgen datapack adds.</li>
 *   <li><b>No listed features.</b> Monster rooms are a placed FEATURE, not a structure, so they need their
 *       own hook at {@code ConfiguredFeature.place}. Matching is by feature-type id, which is why one
 *       {@code minecraft:monster_room} entry retires both the shallow and the deep dungeon placements.</li>
 * </ul>
 *
 * <p>Ore, cave and terrain generation are deliberately untouched, since that is the whole point of the world.
 *
 * <p>This class holds the policy so the three mixins stay a few lines each and the decisions are readable
 * (and testable) in one file. It also owns the on-disk rename migration described in
 * {@link #migrateLegacyWorldFolder}.
 */
public final class RiftWorldgen {

    private RiftWorldgen() {
    }

    // The configured gathering dimension as a ResourceKey, cached so the worldgen hot paths compare keys
    // instead of allocating a String per structure candidate / per feature placement. Rebuilt only when
    // the config value itself changes (a /sanctuary set at runtime).
    private static String keyCacheId;
    private static ResourceKey<Level> keyCache;

    /** The configured gathering dimension as a key, or null if the config value is not a valid id. */
    private static ResourceKey<Level> gatheringKey(SanctuaryConfig cfg) {
        String id = cfg.riftDimension;
        if (id == null) {
            return null;
        }
        if (!id.equals(keyCacheId)) {
            Identifier parsed = Identifier.tryParse(id);
            keyCacheId = id;
            keyCache = parsed == null ? null : ResourceKey.create(Registries.DIMENSION, parsed);
            if (parsed == null) {
                Sanctuary.LOGGER.error("[sanctuary] riftDimension '{}' is not a valid identifier; "
                        + "gathering-world isolation is INACTIVE until it is fixed", id);
            }
        }
        return keyCache;
    }

    /** True when {@code dim} is the configured gathering world. */
    public static boolean isGathering(SanctuaryConfig cfg, ResourceKey<Level> dim) {
        if (cfg == null || dim == null) {
            return false;
        }
        ResourceKey<Level> key = gatheringKey(cfg);
        return key != null && key.equals(dim);
    }

    // ---- portal ignition ----------------------------------------------------------------------

    /**
     * True when a Nether portal must refuse to form here. {@code LevelAccessor} carries no dimension of
     * its own, so this resolves through {@link Level}; anything that is not a Level (never the case for
     * the one caller, {@code BaseFireBlock}) is left alone rather than guessed at.
     */
    public static boolean sealsIgnition(SanctuaryConfig cfg, LevelAccessor level) {
        if (cfg == null || !cfg.riftsEnabled || !cfg.sealResourcePortals) {
            return false;
        }
        return level instanceof Level l && isGathering(cfg, l.dimension());
    }

    // ---- structures ---------------------------------------------------------------------------

    /** True when this structure must not generate in this dimension. */
    public static boolean suppressStructure(SanctuaryConfig cfg, ResourceKey<Level> dim, Holder<Structure> structure) {
        if (cfg == null || !cfg.riftsEnabled || !cfg.riftSuppressStructures || structure == null) {
            return false;
        }
        return isGathering(cfg, dim) && !isAllowedStructure(cfg, structure);
    }

    /**
     * Exception list for {@code riftSuppressStructures}. Entries are either a plain structure id
     * ({@code minecraft:mineshaft}) or a {@code #}-prefixed structure tag ({@code #minecraft:village}),
     * matching the syntax players already know from {@code /locate}.
     */
    private static boolean isAllowedStructure(SanctuaryConfig cfg, Holder<Structure> structure) {
        List<String> allow = cfg.riftAllowedStructures;
        if (allow == null || allow.isEmpty()) {
            return false;
        }
        String id = structure.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
        for (String entry : allow) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            if (entry.charAt(0) == '#') {
                Identifier tag = Identifier.tryParse(entry.substring(1));
                if (tag != null && structure.is(TagKey.create(Registries.STRUCTURE, tag))) {
                    return true;
                }
            } else if (entry.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a structure SEARCH here can only scan fruitlessly and must be refused outright.
     *
     * <p>Suppressing generation alone is not enough. {@code findNearestMapStructure} runs on the SERVER
     * THREAD and walks outward chunk by chunk, blocking on storage reads; when nothing can ever generate
     * it never finds a hit and never exits early, so it burns the full search radius and trips the 60s
     * watchdog. That path is reachable from {@code /locate}, an eye of ender, an explorer map, and the
     * dolphin treasure goal, which makes it a server hang any player (or any dolphin) can trigger.
     *
     * <p>Returning "not found" immediately is both faster and truthful: there is genuinely nothing there.
     * The search is only refused when EVERY requested structure is suppressed, so an allowlisted structure
     * is still locatable.
     */
    public static boolean suppressStructureSearch(SanctuaryConfig cfg, ServerLevel level,
                                                  HolderSet<Structure> targets) {
        if (cfg == null || !cfg.riftsEnabled || !cfg.riftSuppressStructures || targets == null) {
            return false;
        }
        if (!isGathering(cfg, level.dimension())) {
            return false;
        }
        for (Holder<Structure> target : targets) {
            if (isAllowedStructure(cfg, target)) {
                return false;
            }
        }
        return true;
    }

    // ---- features -----------------------------------------------------------------------------

    /** True when this feature type must not be placed in this level (monster rooms and friends). */
    public static boolean suppressFeature(SanctuaryConfig cfg, WorldGenLevel level, Feature<?> feature) {
        if (cfg == null || !cfg.riftsEnabled || feature == null) {
            return false;
        }
        List<String> blocked = cfg.riftSuppressedFeatures;
        if (blocked == null || blocked.isEmpty() || !isGathering(cfg, level.getLevel().dimension())) {
            return false;
        }
        Identifier id = BuiltInRegistries.FEATURE.getKey(feature);
        return id != null && blocked.contains(id.toString());
    }

    // ---- on-disk rename migration -------------------------------------------------------------

    /**
     * Lossless rename of the gathering world's SAVE FOLDER, so renaming the dimension id does not strand
     * the world that was generated under the old one.
     *
     * <p>Minecraft derives a dimension's folder from its id ({@code dimensions/<namespace>/<path>}), so
     * {@code sanctuary:resource_world} → {@code sanctuary:rssworld} would otherwise silently abandon the
     * old folder and generate an empty world, taking with it every base sitting in a weekly-reset pad.
     * This moves the folder instead, which is why it must run at mod init: the server has not opened the
     * region files yet, and by {@code SERVER_STARTED} the fresh empty world already exists.
     *
     * <p>Because there is no {@code MinecraftServer} that early, the save directory is read from
     * {@code server.properties} ({@code level-name}, default {@code world}); this mod is server-side only,
     * so that file is always the authority. Every step is conditional and non-destructive: an existing
     * target folder, a missing source, or an unreadable save directory all leave the disk untouched.
     */
    public static void migrateLegacyWorldFolder(SanctuaryConfig cfg) {
        if (cfg == null || cfg.riftDimensionLegacyIds == null || cfg.riftDimensionLegacyIds.isEmpty()) {
            return;
        }
        Identifier current = Identifier.tryParse(cfg.riftDimension);
        if (current == null) {
            return;
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path worldDir = gameDir.resolve(levelName(gameDir));
        if (!Files.isRegularFile(worldDir.resolve("level.dat"))) {
            return; // first boot, or a save layout this can't reason about. Nothing to migrate.
        }
        Path dims = worldDir.resolve("dimensions");
        Path target = dims.resolve(current.getNamespace()).resolve(current.getPath());
        String legacy = RiftRenamePlan.chooseSource(cfg.riftDimension, cfg.riftDimensionLegacyIds,
                Files.exists(target), id -> Files.isDirectory(folderOf(dims, id)));
        if (legacy == null) {
            return;
        }
        Path src = folderOf(dims, legacy);
        try {
            Files.createDirectories(target.getParent());
            Files.move(src, target);
            Sanctuary.LOGGER.warn("[sanctuary] gathering world renamed on disk: {} -> {} (moved {} -> {}). "
                    + "Rift records are rewritten on load; nothing was lost.",
                    legacy, cfg.riftDimension, src, target);
        } catch (Exception e) {
            Sanctuary.LOGGER.error("[sanctuary] could not move the gathering world {} -> {}. The server will "
                    + "generate a FRESH {} and the old folder is left intact. Move it by hand to keep it.",
                    src, target, cfg.riftDimension, e);
        }
    }

    /** {@code dimensions/<namespace>/<path>} for a well-formed id (guaranteed by RiftRenamePlan). */
    private static Path folderOf(Path dims, String id) {
        String[] parts = RiftRenamePlan.split(id);
        return dims.resolve(parts[0]).resolve(parts[1]);
    }

    /** {@code level-name} from server.properties, defaulting to {@code world}. */
    private static String levelName(Path gameDir) {
        Path props = gameDir.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            try (InputStream in = Files.newInputStream(props)) {
                Properties p = new Properties();
                p.load(in); // Properties handles the escaping server.properties applies to paths.
                String name = p.getProperty("level-name");
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (Exception e) {
                Sanctuary.LOGGER.warn("[sanctuary] could not read server.properties; assuming level-name=world", e);
            }
        }
        return "world";
    }
}
