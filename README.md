# XP Vitality

A server-side Fabric mod for **Minecraft 26.1.2** that turns experience into a survival resource.
Built and compiled against the real 26.1.2 jars on this host (not guessed).

## The systems

| System | Where | Summary |
|--------|-------|---------|
| Passive regen | `XPVitality` (server tick) | While hurt and holding XP, drains XP to fast-heal ~1 heart per second. |
| Armor from level | `LevelAttributes` (`armor` attr) | Leveling grants real armor points (capped) — shows as **armor icons** and does the reduction via vanilla's curve. |
| Bonus hearts | `LevelAttributes` (`max_health` attr) | +1 **heart** at each level milestone (10, 25, 50, 100, 250, 500, …). |
| XP shield | `LevelAttributes` (absorption effect) | **Yellow hearts** = `(level/milestone − 1) × maxHealth`; depletes in combat and only rebuilds after an out-of-combat cooldown (10s − 1s/milestone). Resets at each milestone. |
| Breath | `LevelAttributes` (`oxygen_bonus` attr) | Air lasts ~`(level+1)×15s` underwater — level 500 ≈ can't drown. |
| Lethal save | `XPVitality` (`ALLOW_DEATH`) | A killing blow spends whole levels to survive at ~1 heart — if you can afford it. |
| World-danger scaling | `LivingEntityDamageMixin` | Mob damage scales up with difficulty, world age, and distance from safe anchors. |
| Wild-mob difficulty | `MobDifficulty` (spawn hook) | Hostiles are buffed **at spawn** by distance from the nearest anchor (health/damage/speed, capped) — baked into their NBT. Tiered names (Feral→Nightmare) + particle auras. |

Damage path order: the mixin **scales incoming mob damage up** at `hurtServer` HEAD, then vanilla's
armor reduction (from the level-granted armor) applies afterward — preserving "scale up, then reduce".
The hearts/armor/shield are standard synced attributes, so **vanilla clients render them** with no client mod.

### Sanctuary anchor (placeable safe region)

**Right-click a beacon while holding a dragon egg** to seat the egg inside it and form a sanctuary anchor:
hostiles that spawn within its radius (default 128 blocks) stay vanilla-strength, while the wildlands
beyond stay lethal (System 7). The egg appears as a shrunk dragon egg floating in the beacon (a
`block_display` entity; size/height tunable via `anchor.eggScale`/`anchor.eggHeight`). It's **all vanilla
blocks/entities**, so it renders natively on **every client** (vanilla or modded) — no custom content or
resource pack. Anchor beacons suppress the vanilla beacon UI; break the beacon to pop the egg back out and
drop the protection. Anchors persist in `config/xpvitality_anchors.json` and stack with the config
`anchors` list (nearest wins).

## Requirements

- **JDK 25** (Minecraft 26.x requires it). Already installed here via `brew install openjdk@25`;
  `JAVA_HOME` is set in `~/.zshrc`.
- No mappings and no mixin refmap: **26.x ships un-obfuscated**, so the code targets Mojang names directly.

## Build & test

```sh
cd ~/minecraft-mods/xpvitality
./gradlew build        # compiles + runs SurvivalLogic unit tests
./gradlew test         # just the pure-logic tests
```

The built mod jar lands in `build/libs/xpvitality-0.3.0.jar`.

## Run / smoke-test

```sh
./gradlew runServer    # launches a dev dedicated server with the mod loaded
```

Mixin *application* (as opposed to compilation) is only fully validated at runtime, so `runServer`
is the real confirmation that `LivingEntityDamageMixin` hooks `LivingEntity.hurtServer`.

## Deploy to mc.kast.ro

1. `./gradlew build`
2. Copy `build/libs/xpvitality-0.3.0.jar` into the server's `mods/` folder.
3. Ensure the server runs on **Fabric Loader ≥ 0.19.3** with **Fabric API 0.153.0+26.1.2** and **Java 25**.
4. First launch writes `config/xpvitality.json` — tune the levers there (no recompile needed).

## Editing in Kiro

```sh
kiro ~/minecraft-mods/xpvitality
```

Kiro + Claude Code drive `./gradlew` directly. You lose IntelliJ's inline mixin gutter checks, but the
compiler + `runServer` are the correctness oracle — which is exactly how this mod was verified.

## Config (`config/xpvitality.json`)

All balance levers live in `XPVitalityConfig`: regen rate/cost; `armor.perLevel`/`armor.max`;
`hearts.hpPerMilestone` + the `milestones` list; `shield.maxFraction` and `shield.cooldown{Base,PerMilestone,Min}`;
`oxygen.perLevel`/`oxygen.max`; lethal-save cost/revive-health; and the `danger` weights plus the `anchors`
list (`{x, z, safeRadius}`, default spawn with a 128-block safe radius). The file is written with defaults on first launch.

### In-game commands (`/xpvitality`, op / level 2+)

Changes apply **live** — the tick handler and damage mixin read the config fresh every tick/hit, so
there's no restart needed.

| Command | Effect |
|---|---|
| `/xpvitality list` | Print every system toggle and numeric value |
| `/xpvitality get <key>` | Read one value (tab-completes keys, e.g. `armor.max`) |
| `/xpvitality set <key> <value>` | Set a value live (range-validated) |
| `/xpvitality toggle <system>` | Flip a system on/off (`regen`, `armor`, `hearts`, `shield`, `breath`, `lethalSave`, `danger`) |
| `/xpvitality save` | Persist current live values to the json |
| `/xpvitality reload` | Re-read the json from disk |
| `/xpvitality anchor list\|add <x> <z> <radius>\|clear` | Manage System-4 safe anchors |

`set`/`toggle`/`anchor` mutate the in-memory config (instant); `save` writes it to disk; `reload`
reads it back — so you can tune live, then `save`, or hand-edit the file and `reload`.

See [SPEC.md](SPEC.md) for the full design and [CHANGELOG.md](CHANGELOG.md) for version history.
