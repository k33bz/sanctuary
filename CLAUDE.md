# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Sanctuary is a **server-side-only Fabric mod** for Minecraft 26.1.2 (Java 25). Two intertwined
economies: XP is a life force (heals, armors, shields, buys back your life) and distance is
danger (mobs scale with distance from the nearest sanctuary anchor).

Two version lines are kept at feature parity (currently both 0.8.7.2):

- **`main`** targets **MC 26.2** (run dir `run262/`).
- **`26.1`** targets **MC 26.1.2** — this is the line the **live gmc101 server actually runs**,
  so treat it as the deploy branch, not a throwaway backport. See the deploy notes in memory
  before shipping a jar there.

Port a change across the two lines with `git checkout <otherbranch> -- <paths>` then
`git diff --cached HEAD` to catch API-delta clobbers. Three known 26.1↔26.2 deltas:
`GRAY_STAINED_GLASS_PANE` ↔ `STAINED_GLASS_PANE.gray()`, `getBottomCenter()` ↔
`Vec3.atBottomCenterOf()`, and team color `ChatFormatting` ↔ `Optional<TeamColor>`.

## Commands

```sh
./gradlew build        # compile + run tests; jar lands in build/libs/ (needs JDK 25)
./gradlew test         # JUnit 5 unit tests only
./gradlew test --tests "com.k33bz.sanctuary.SurvivalLogicTest"                    # one class
./gradlew test --tests "com.k33bz.sanctuary.grave.GraveLifecycleTest.someMethod"  # one method
./gradlew runServer    # dev server on :25565 (run dir: run262/ on main, run/ on 26.1)
python3 scripts/check_deps.py   # bump dependency pins in-place (CI runs this weekly)
```

- **Never point a dev server at another branch's run dir** — `run/` holds a 26.1.2 world that a
  26.2 server would irreversibly upgrade. Loom uses `run262/` on `main` and `run/` on `26.1`.
- Local gradle often can't build (the toolchain can't see the portable JDK 25) — **rely on CI**
  and verify the artifact by `headSha` before downloading a CI jar.
- MC 26.x ships **un-obfuscated**: there is no mappings dependency and no mixin refmap; use
  Mojang names directly. CI's build workflow has a `javap` diagnose step for signature hunting
  when a Minecraft API changed under us.
- **26.x renamed gamerules to snake_case** (`keep_inventory`, `advance_time`,
  `players_sleeping_percentage`); the old camelCase names throw "Incorrect argument".
- **26.x `text_display` `text:` needs an SNBT compound** (`text:{text:"",extra:[{text:"Hi",
  color:"gold",bold:1b}]}`), not a single-quoted JSON string (that renders literally). Vanilla
  signs still want plain-string components.

## Hard constraints (from SPEC.md — read it before designing features)

- **Vanilla clients must always work.** No client entrypoint, no required resource pack.
  All UI/feedback goes through vanilla attributes, effects, display entities, actionbar text,
  26.x native dialogs, sgui container GUIs, and textured player heads.
- **Only the Overworld scales** (`scalingDimensions`); Nether/End stay vanilla.
- **Every balance lever is a config knob** in `SanctuaryConfig` (GSON →
  `config/sanctuary.json`), live-tunable via `/sanctuary set <key> <value>` — never hardcode
  a tunable.
- **Admin ≠ player**: creative anchor placement is eternal/cap-free; survival placement (even
  by ops) pays upkeep and progression.
- **No teleport systems** — travel friction is deliberate (decided 2026-07-04).

## Architecture

Entry point is `Sanctuary` (the sole `ModInitializer`, registered in `fabric.mod.json`). It
loads the static `Sanctuary.CONFIG`, registers recipes/event handlers, and wires the
subsystems. Gameplay features are numbered "System N" comments that thread through
`SanctuaryConfig`, `Sanctuary`, and the mixins — grep `System 4` etc. to find all pieces of
one mechanic.

Packages under `com.k33bz.sanctuary`:

- **root** — XP vitality (regen, armor, hearts, shield, lethal save), `SanctuaryConfig`,
  `SanctuaryCommands` (`/sanctuary ...`), `MobDifficulty` (distance-scaled tiers),
  respawn/death toll (`RespawnChoice` + `RespawnLedger` — death sends you to the nearest
  sanctuary, then a native dialog offers paid bed/corpse-run upgrades; `RespawnChoice.safePlace`
  guarantees a non-suffocating landing and grants immunity while the choice dialog is open),
  `DangerClock` (world-danger ages only while players are **online** — an occupied-time clock,
  so idle/pregen no longer ages it), `StartingKit` (first-join traveller's kit), feral eggs,
  `DialogInputs`, stat boards, AFK tracking.
- **anchor/** — sanctuary anchors: `AnchorState` (persisted to
  `config/sanctuary_anchors.json`), fuel/upkeep, cap progression via Warden kills
  (`AnchorCapRules`), the Wild Essence → Wild Membrane → Sanctuary Crystal crafting chain
  (component-aware special recipes registered before datapacks load), anchor dialogs/menus,
  `FlanIntegration`.
- **grave/** — headstones, graveyard consecration, the Gravekeeper villager + allay couriers,
  patrol/mutter/hover AI, graveyard protection and smite, `OfflineUuid`.
- **rift/** — the "gathering world" resource dimension. Access is via **ruined-portal rifts**
  (crying obsidian → the resource world; `RiftPortals`), NOT the retired `RiftAnchor`.
  `RiftSeal` blocks vanilla Nether/End portals inside the gathering world so it stays a
  dead-end resource dim. Weekly preserved-chunk reset lives in `RiftReset` / `RiftSnapshot` /
  `RiftStore` (reset is OFF by default); `Rifts` is the wiring.
- **event/** — themed **night events** on a deterministic sha256-seeded schedule (~1-in-8
  nights): `NightEvents`, `EventDrivers`, `EventSchedule`, `NightEvent`, `NightEventStore`.
  `EventExport` writes a JSON the mcweb site reads to show the schedule.
- **siege/** — hostile-mob intelligence: door-breakers, rabid wildlife, the Restless.
- **metrics/** — NDJSON event logs (kills, graves) under `config/sanctuary_kill_logs/` etc.
  Anti-farming is *observed*, not nerfed — don't add silent punishments.
- **mixin/** — every mixin must be listed in `src/main/resources/sanctuary.mixins.json`;
  field access is opened via `sanctuary.accesswidener`. Damage mitigation and world-danger
  scaling live in `LivingEntityDamageMixin`; spawn buffs in `MobFinalizeSpawnMixin` /
  `NaturalSpawnerMixin`.

Dependencies: sgui and fabric-permissions-api are bundled via `include(...)`. Flan is
`compileOnly` and gated at runtime on `isModLoaded` in `FlanIntegration` — keep it optional.
LuckPerms nodes (`sanctuary.anchor.create/break/admin`) refine permissions when present.

**Vanilla Tweaks is (mostly) internalized** (0.8.7.0 refactor): the 33-ish external VT datapacks
were cut, so `recipes`, `dragon`/`bat` drops are now **native code** rather than bundled packs.
**Branch gap:** on `main` (26.2) `xp_bottling` is ALSO native (`Sanctuary.registerXpBottling`,
config `xpBottlingEnabled`) and `vt_packs.txt` is empty; on **this `26.1` line `xp_bottling` is
still a bundled VT datapack** (`vt_packs.txt` lists it, pack under `resourcepacks/vt/`) — the
migration was never finished here (issue #6). `VanillaTweaksPacks.register()` reads `vt_packs.txt`.
The other datapack still bundled on both is `resourcepacks/crafting_tweaks/`.

## Testing

Tests are **pure-logic JUnit 5** — no game runtime. The convention: extract game-independent
math/state into classes with no Minecraft types (`SurvivalLogic`, `AnchorCapRules`,
`GraveLifecycle`, `OfflineUuid`, ...) and unit-test those. When adding a mechanic, put the
formula in a pure class and test it there. Watch for classes whose static init touches
`BuiltInRegistries` (e.g. `WaystoneRecord`'s CODEC) — loading them under JUnit throws
`ExceptionInInitializerError`, so keep testable logic in a Minecraft-free class.

Lesson written in blood (`docs/coe/2026-07-04-empty-graves.md`): a test that asserts structure
("a grave appears") but not the core value ("the items survive") is a blind spot that once
destroyed player inventories for 19 hours. Assert the behavior that matters, and run
`./gradlew build` before any release.

## Releases & docs

- Version is `mod_version` in `gradle.properties`; the jar is `sanctuary-<modver>+<mcver>.jar`.
  Every release bumps `mod_version` **and** adds a `CHANGELOG.md` entry (detailed,
  root-cause-style — see existing entries). Commit messages typically end with the version,
  e.g. `Fix lambda capture (0.8.6.2)`.
- Keep `main` and `26.1` at parity: ship a feature on one, port it to the other, and confirm
  **both** branches are CI-green before considering it done.
- Tagging `v*` triggers the GitHub release workflow; publishing a release pushes to Modrinth.
- Docs are layered: `SPEC.md` (design intent) → `docs/MECHANICS.md` (every formula, with
  worked examples — update it when you change a formula) → per-feature docs
  (`docs/VITALITY.md`, `WILDS.md`, `ANCHORS.md`, `FERAL-EGGS.md`, `DEATH-AND-GRAVES.md`,
  `SERVER-GUIDE.md`) → `docs/coe/` (incident post-mortems).
