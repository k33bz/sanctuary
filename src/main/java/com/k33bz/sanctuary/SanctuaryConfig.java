package com.k33bz.sanctuary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * GSON-backed config, written to {@code config/sanctuary.json} on first run.
 * Every balance lever lives here so the mechanics can be tuned without recompiling.
 */
public class SanctuaryConfig {

    // System 1 — passive regen
    public boolean regenEnabled = true;
    public int regenIntervalTicks = 20;       // once per second
    public float regenHealPerInterval = 2.0f; // one heart per interval (fast emergency heal, ~1 heart/sec)
    public float regenXpPerHealth = 2.0f;     // XP points drained per 1.0 health restored

    // System 2 — level-scaled damage reduction, delivered as real vanilla armor points
    // (shows as armor icons; reduction uses vanilla's armor curve).
    public boolean armorEnabled = true;
    public double armorPerLevel = 0.25;  // armor points per level
    public double armorMax = 20.0;       // cap (20 = full armor bar)

    // System 5a — bonus max-health hearts granted at level milestones.
    public boolean heartsEnabled = true;
    public List<Integer> milestones = defaultMilestones();
    public double hpPerMilestone = 2.0;  // 2.0 HP = one heart per milestone

    // System 5b — absorption "XP shield": progress past the current milestone, as a fraction of max health.
    public boolean shieldEnabled = true;
    public double shieldMaxFraction = 2.0; // clamp on the (level/milestone - 1) fraction
    // Shield only refills after this long out of combat; reduced per milestone (veterans recover faster).
    public double shieldRegenCooldownBase = 10.0;        // seconds
    public double shieldRegenCooldownPerMilestone = 1.0; // seconds off per milestone reached
    public double shieldRegenCooldownMin = 1.0;          // floor (seconds)

    // System 6 — underwater breath via the OXYGEN_BONUS attribute (air lasts ~(bonus+1)*15s).
    public boolean breathEnabled = true;
    public double oxygenPerLevel = 1.0;   // level 500 -> ~2 hours underwater
    public double oxygenMax = 1000.0;

    // System 3 — XP-funded lethal save
    public boolean lethalSaveEnabled = true;
    public float lethalSaveLevelsPerDamage = 0.5f; // levels per point of killing-hit damage
    public int lethalSaveMinLevels = 1;
    public float lethalSaveReviveHealth = 2.0f;    // ~1 heart, then regen takes over

    // System 4 — world-danger damage scaling
    public DangerScaling danger = new DangerScaling();
    public List<Anchor> anchors = defaultAnchors();

    // Dimensions where distance-based scaling (Systems 4 & 7) and anchors apply. Anchor distances
    // are Overworld x/z; in the Nether (coords are /8) or the End they'd be nonsense, so every
    // other dimension stays vanilla unless explicitly added here.
    public List<String> scalingDimensions = new ArrayList<>(List.of("minecraft:overworld"));

    // Cosmetic: the shrunk dragon egg shown inside an anchor beacon (block_display), live-tunable.
    public double anchorEggScale = 0.75;
    public double anchorEggHeight = 0.9; // vertical centre within the beacon (0 = bottom, 1 = top)
    public boolean anchorShowLabel = true;   // floating "Sanctuary Anchor" text above the anchor
    public double anchorLabelHeight = 1.6;   // height of the label above the beacon base

    // System 7 — spawn-based wild-mob difficulty: hostiles are buffed by their distance from the
    // nearest anchor when they spawn (baked into their attributes), with tiered names + particles.
    public MobScaling mobScaling = new MobScaling();

    public static class MobScaling {
        // Linear "frontier" curve (see SPEC): +1x damage per 1000 blocks beyond a safe zone. The ≤~8k
        // zone stays balanced (under the caps); separate caps let the DEEP wildlands become a true death
        // zone (one-shots) without making mobs absurdly spongy or uncontrollably fast.
        public boolean enabled = true;
        public double healthPerBlock = 0.0015;
        public double damagePerBlock = 0.001;    // +1x damage per 1000 blocks
        public double speedPerBlock = 0.00003;   // gentle
        public double healthMaxMultiplier = 12.0;
        public double damageMaxMultiplier = 60.0; // deep wildlands one-shot fresh AND geared players
        public double speedMaxMultiplier = 1.4;   // fast + menacing but still controllable
        public double xpPerBlock = 0.0015;        // deeper mobs drop more XP (risk/reward)
        public double xpMaxMultiplier = 20.0;
        public double particleRange = 48.0;       // emit threat particles within this range of a player

        // Hunters: deep mobs notice and track players from farther away (FOLLOW_RANGE multiplier).
        public double followPerBlock = 0.0001;    // +1x notice range per 10k blocks
        public double followMaxMultiplier = 3.0;
        // Door-breakers: zombies spawning past the start distance roll a chance to break wooden
        // doors on ANY difficulty (vanilla only allows this on Hard). Needs mobGriefing=true.
        public double doorBreakStartBlocks = 1000.0;
        public double doorBreakChancePerBlock = 0.00015; // +15% per 1000 blocks past start (100% ~7.6k)
    }

    public static class DangerScaling implements SurvivalLogic.DangerParams {
        public boolean enabled = true;
        public float difficultyWeight = 0.15f; // per difficulty id (peaceful=0 .. hard=3)
        public double perDayWeight = 0.02;     // per in-game day the world has survived
        public double perBlockWeight = 0.0005; // per block beyond the nearest safe radius
        public float maxMultiplier = 4.0f;

        @Override
        public float difficultyWeight() {
            return difficultyWeight;
        }

        @Override
        public double perDayWeight() {
            return perDayWeight;
        }

        @Override
        public double perBlockWeight() {
            return perBlockWeight;
        }

        @Override
        public float maxMultiplier() {
            return maxMultiplier;
        }
    }

    public static class Anchor {
        public double x;
        public double z;
        public double safeRadius;

        public Anchor() {
        }

        public Anchor(double x, double z, double safeRadius) {
            this.x = x;
            this.z = z;
            this.safeRadius = safeRadius;
        }
    }

    private static List<Anchor> defaultAnchors() {
        List<Anchor> list = new ArrayList<>();
        list.add(new Anchor(0, 0, 128)); // spawn is the default safe zone
        return list;
    }

    private static List<Integer> defaultMilestones() {
        return new ArrayList<>(List.of(10, 25, 50, 100, 250, 500, 1000, 2500, 5000));
    }

    /** Milestones as an ascending int[] (nulls dropped, sorted) for the pure-logic helpers. */
    public int[] milestonesArray() {
        if (milestones == null || milestones.isEmpty()) {
            return new int[0];
        }
        return milestones.stream()
                .filter(java.util.Objects::nonNull)
                .sorted()
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /** Whether distance-based scaling + anchors apply in this dimension (default: Overworld only). */
    public boolean isScalingDimension(net.minecraft.world.level.Level level) {
        return scalingDimensions != null
                && scalingDimensions.contains(level.dimension().identifier().toString());
    }

    /** Blocks the player is beyond the nearest anchor's safe radius (0 if inside any safe zone). */
    public double blocksBeyondSafe(double px, double pz) {
        if (anchors == null || anchors.isEmpty()) {
            return 0.0;
        }
        double best = Double.MAX_VALUE;
        for (Anchor a : anchors) {
            double dx = px - a.x;
            double dz = pz - a.z;
            double beyond = Math.sqrt(dx * dx + dz * dz) - a.safeRadius;
            if (beyond < best) {
                best = beyond;
            }
        }
        return Math.max(0.0, best);
    }

    // --- persistence ---

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary.json");
    }

    /** Persist the current values to the standard config path. */
    public void save() {
        save(path());
    }

    public static SanctuaryConfig load() {
        Path path = path();
        try {
            if (Files.exists(path)) {
                SanctuaryConfig cfg = GSON.fromJson(Files.readString(path), SanctuaryConfig.class);
                if (cfg != null) {
                    cfg.save(path); // re-write so newly added keys appear with defaults
                    return cfg;
                }
            }
        } catch (IOException | RuntimeException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to read config; using defaults", e);
        }
        SanctuaryConfig cfg = new SanctuaryConfig();
        cfg.save(path);
        return cfg;
    }

    public void save(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            Sanctuary.LOGGER.warn("[sanctuary] Failed to write config", e);
        }
    }
}
