# Sanctuary

A server-side Fabric mod for **Minecraft 26.1.2** that turns **XP into a life force** and
**distance into danger**. Vanilla clients connect with no mods — everything renders through
vanilla attributes, effects, and display entities.

## The idea

Your XP level is your vitality: it heals you, armors you, grows your hearts and shield, and can
buy back your life. The world scales the other way: the farther from a **sanctuary anchor**, the
stronger everything gets — until the deep wildlands one-shot the unprepared, the wildlife turns
rabid, and zombies tear the door (and its frame) off your shelter.

Sanctuaries are placeable: seat a **dragon egg in a beacon** and it becomes a spinning-crystal
anchor that calms the region around it. Civilization is something you build outward, anchor by
anchor.

## Systems at a glance

| System | Summary |
|--------|---------|
| XP regen | drain XP points to heal ~1 heart/s while hurt |
| Level armor | +0.25 armor/level (cap 20 = 80% reduction at level 80), real armor icons |
| Milestone hearts | +1 heart at levels 10, 25, 50, 100, 250, 500, 1000, 2500, 5000 |
| XP shield | absorption scaling with progress past your milestone; rebuilds out of combat |
| Breath | oxygen bonus per level — high levels effectively cannot drown |
| Lethal save | spend levels to survive a killing blow at ~1 heart |
| Wild-mob scaling | mobs gain damage/health/speed/XP with distance from the nearest anchor; fuzzy zone edges (±12% σ per spawn); optional exponential damage curve |
| Threat tiers | Feral → Savage → Ferocious → Nightmare — color-coded names + particle auras |
| Hunters | deep mobs track players from up to 3× vanilla range |
| Door-breakers | deep zombies break wooden doors on **any** difficulty — and smash the frame blocks of a *player-placed* door when the way through is blocked |
| Rabid wildlife | in Savage+ zones, 25% of animals spawn hostile and hunt players |
| Sanctuary anchors | beacon + dragon egg; union of safe circles; breaking pops the egg back out |
| Anti-farming | buffed mobs inside a sanctuary revert to vanilla — scaled XP included |
| Threat readout | actionbar zone messages with a personalized 5-skull scale (☠), on crossing and on login |
| World-danger scaling | difficulty/world-age damage multiplier on players (distance term off by default) |

Full formulas, tables, and worked examples: **[docs/MECHANICS.md](docs/MECHANICS.md)**.
Design history: [SPEC.md](SPEC.md) · [CHANGELOG.md](CHANGELOG.md).

Scaling and anchors apply to the **Overworld only** by default (`scalingDimensions`); the Nether
and End stay vanilla.

## Requirements & build

- **JDK 25** (Minecraft 26.x requires it). No mappings, no mixin refmap — 26.x ships
  un-obfuscated, so the code targets Mojang names directly.

```sh
./gradlew build        # compiles + runs the SurvivalLogic unit tests → build/libs/
./gradlew runServer    # dev dedicated server on :25565 (validates mixin application)
```

## Deploy

1. `./gradlew build`
2. Copy `build/libs/sanctuary-<version>.jar` into the server's `mods/` folder.
3. Server needs **Fabric Loader ≥ 0.19.3**, **Fabric API 0.153.0+26.1.2**, **Java 25**.
4. First launch writes `config/sanctuary.json` — tune the levers there (no recompile needed).

## Configuration & commands

All balance levers live in `config/sanctuary.json` (see the
[config reference](docs/MECHANICS.md#9-config-reference)). Ops (level 2+) can tune **live** —
the tick handler and damage mixin read the config fresh every tick/hit:

| Command | Effect |
|---|---|
| `/sanctuary list` | Print every system toggle and numeric value |
| `/sanctuary get <key>` | Read one value (tab-completes keys) |
| `/sanctuary set <key> <value>` | Set a value live (range-validated) |
| `/sanctuary toggle <system>` | Flip a system on/off |
| `/sanctuary save` | Persist current live values to the json |
| `/sanctuary reload` | Re-read the json from disk |
| `/sanctuary anchor list\|add <x> <z> <radius>\|clear` | Manage config safe anchors |

`set`/`toggle`/`anchor` mutate the in-memory config (instant); `save` writes it to disk; `reload`
reads it back — tune live then `save`, or hand-edit the file and `reload`.

## License

MIT
