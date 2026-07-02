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

    // Anchor visuals + the Sanctuary Crystal economy. The crystal (a textured player head) is
    // the anchor block itself: placing one forms a sanctuary; it drops rarely from high-tier
    // wildlands mobs killed by players, so expansion is gated on conquering the frontier.
    public boolean anchorShowLabel = true;   // floating "Sanctuary Anchor" text above the anchor
    public double anchorLabelHeight = 1.6;   // height of the label above the crystal
    public int crystalDropMinTier = 3;       // Ferocious+ mobs may drop a crystal
    public double crystalDropChance = 0.03;  // per qualifying player-kill

    // Upkeep: player-raised sanctuaries burn fuel (real hours of server uptime). Feed the crystal
    // emeralds to extend it (right-click; sneak = whole stack; emerald block = 9 emeralds). A dry
    // anchor goes dormant (no safety, no Flan claim) until refueled. Admin/creative-placed
    // anchors are exempt, as are legacy pre-upkeep anchors.
    public boolean anchorUpkeepEnabled = true;
    public double anchorStartHours = 24.0;     // charge a fresh crystal comes with
    public double anchorHoursPerEmerald = 1.0;
    public double anchorHoursPerEgg = 168.0;   // a dragon egg adds 7 days — and is the ONLY way
                                               // to rekindle a dormant anchor
    public double anchorMaxFuelHours = 720.0;  // 30 days banked max

    // Optional Flan integration: active anchors carry an auto-created admin claim of this radius
    // around the crystal (protection for the anchor + its immediate town core). Requires Flan.
    public boolean flanIntegration = true;
    public int flanClaimRadius = 16;

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
        // Damage/XP curve shape: 1.0 = linear; >1 ramps the deep wildlands superlinearly
        // (1.5 at 4000 blocks: 1+4^1.5 = 9x instead of 5x). Health/speed stay linear.
        public double damageCurveExponent = 1.0;
        // Fuzzy zone edges: each spawn's effective distance is jittered by a gaussian with this
        // sigma (fraction of distance). Near a tier boundary mobs can roll a tier up or down.
        public double edgeFuzz = 0.12;
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

        // Actionbar message when a player crosses a threat-zone boundary (safe <-> wild, tier ups/downs),
        // rate-limited so bouncing on a boundary doesn't spam.
        public boolean boundaryMessages = true;
        public double boundaryMessageCooldownSeconds = 10.0;
        // Skull scale calibration: player levels that offset one +1x of zone damage. At this
        // default a level-25 player entering a 2.5x zone reads ~3/5 skulls (an even fight).
        public double skullLevelsPerMultiplier = 15.0;

        // Anti-farming: a buffed wild mob that ends up inside a sanctuary safe zone (dragged,
        // nametagged, or wandered in) reverts to vanilla strength — and its scaled XP reverts
        // with it, so hauling Nightmare mobs home isn't an XP printer. A player-given name tag
        // survives the revert; only the mod's tier name is cleared.
        public boolean revertInSanctuary = true;

        // Rabid wildlife: in Savage+ zones (tier 2+), animals roll this chance to spawn rabid —
        // buffed like monsters and hunting players. Tamed animals and babies are exempt. Damage
        // is rabidBaseDamage scaled by the zone's damage multiplier (animals have no attack
        // attribute of their own).
        public boolean rabidEnabled = true;
        public double rabidChance = 0.25;
        public double rabidBaseDamage = 2.0;

        // Frame smashing: door-breaker zombies that can't path to their target may smash a few
        // soft blocks around a PLAYER-PLACED wooden door (never world-generated structures).
        public boolean frameSmashEnabled = true;
        public int frameRadius = 1;             // blocks around the door that are at risk
        public double frameSearchRange = 4.0;   // mob must be this close to a tracked door to start
        public double frameMaxHardness = 2.0;   // only blocks this soft break (planks=2.0, obsidian never)
        public int frameSmashTimeTicks = 160;   // ~8s of pounding per block
    }

    public static class DangerScaling implements SurvivalLogic.DangerParams {
        public boolean enabled = true;
        public float difficultyWeight = 0.15f; // per difficulty id (peaceful=0 .. hard=3)
        // Per in-game day the world has survived. Keep this SMALL: an always-on server burns ~72
        // in-game days per real day, so 0.02 hit the 4x cap in ~2.5 real days — everywhere, spawn
        // included. 0.0005 is a months-scale slow burn (~+1x per month of server uptime).
        public double perDayWeight = 0.0005;
        // Game time (ticks) the age pressure is measured from. /sanctuary danger reset sets this
        // to "now", so the world feels young again without touching the actual world clock.
        public long epochTick = 0L;
        // Per block beyond the nearest safe radius. Default 0: mob attributes (System 7) already
        // scale with distance, and this multiplied ON TOP of them (~24x at 5k instead of 6x).
        // Left as a knob for deliberate double-dipping experiments later.
        public double perBlockWeight = 0.0;
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

        @Override
        public long epochTick() {
            return epochTick;
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
