# Sanctuary

[![main: build + server test](https://github.com/k33bz/sanctuary/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain)
[![26.1: build + server test](https://github.com/k33bz/sanctuary/actions/workflows/build.yml/badge.svg?branch=26.1)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1)
[![GitHub mirror matches Forgejo](https://github.com/k33bz/sanctuary/actions/workflows/forgejo-parity.yml/badge.svg)](https://github.com/k33bz/sanctuary/actions/workflows/forgejo-parity.yml)
[![live on gmc101](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/gmc101.json)](https://github.com/k33bz/sanctuary/deployments/gmc101)

| Line | Mod | Minecraft | Loader | fabric-api (tested) | Flan (tested) | Server test |
|---|---|---|---|---|---|---|
| `main` | [![sanctuary (main)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/mod.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain) | [![minecraft (main)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/minecraft.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain) | [![fabric loader (main)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/loader.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain) | [![fabric-api (main)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/fabric-api.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain) | [![flan (main)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/flan.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain) | [![server test (main)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/server-test.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain) |
| `26.1` | [![sanctuary (26.1)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/mod.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1) | [![minecraft (26.1)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/minecraft.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1) | [![fabric loader (26.1)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/loader.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1) | [![fabric-api (26.1)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/fabric-api.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1) | [![flan (26.1)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/flan.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1) | [![server test (26.1)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/server-test.json)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1) |

<sub>Version badges are written by CI on every push to `main`/`26.1` (`scripts/publish_badges.py`, kept on the `badges` branch); "tested" is what the real-server CI test booted with.</sub>

**Your XP is your life force. Distance is danger.** A server-side Fabric mod — vanilla
clients connect with no mods; everything renders through vanilla attributes, effects, and
display entities.

Your levels heal you, armor you, and buy back your life. The world scales the other way: the
farther from a **sanctuary anchor**, the deadlier everything gets. Civilization is something
you carve out of the wilds — and defend, fuel, and one day get buried in.

## Versions

| Minecraft | Branch | Fabric API | Notes |
|---|---|---|---|
| 26.2 | [`main`](../../tree/main) | 0.161.0+26.2 | active development; Flan 1.12.8 (needs fabric-api >= 0.158) |
| 26.1.x | [`26.1`](../../tree/26.1) | 0.154.0+26.1.2 | maintenance — backports only |

Downloads: [GitHub Releases](../../releases) · [Modrinth](https://modrinth.com/project/y5hXc8My).
Jars are `sanctuary-<modver>+<mcversion>.jar` — grab the one matching your server.

## Features

- **[Vitality](docs/VITALITY.md)** — XP-funded healing, armor, hearts, shields, lethal saves,
  and soul retention on death.
- **[The Wilds](docs/WILDS.md)** — distance-scaled mobs (Feral → Savage → Ferocious →
  Nightmare), hunters, door-breakers, rabid wildlife, and the Restless that stalk sleepless
  miners.
- **[Sanctuary Anchors](docs/ANCHORS.md)** — raise safe zones from rare crystals, fuel them
  with emeralds, expand your cap with Warden kills. Flan + LuckPerms integration.
- **[Feral Eggs](docs/FERAL-EGGS.md)** — breed bloodline hostile poultry; star-graded eggs,
  frontier ranching, a market with luck and patience at its heart.
- **[Death, Respawn & Graves](docs/DEATH-AND-GRAVES.md)** — free sanctuary respawn or paid
  bed/resurrect with an escalating death toll; headstones that drift to consecrated
  graveyards tended by the Gravekeeper and his allay couriers.
- **[Server Guide](docs/SERVER-GUIDE.md)** — live config commands, 59 opt-in bundled Vanilla
  Tweaks packs, wall-mounted stat leaderboards, AFK tags, siege-aware anti-grief.

Deep math and the full config/command reference: **[docs/MECHANICS.md](docs/MECHANICS.md)** ·
history: [CHANGELOG.md](CHANGELOG.md) · design notes: [SPEC.md](SPEC.md)

## Install (server only)

Drop the jar in `mods/` with **Fabric Loader ≥ 0.19.3**, the **Fabric API matching your MC
version** (table above), **Java 25**. First launch writes `config/sanctuary.json` — every
lever tunes live in-game, no restarts.

## Build

```sh
./gradlew build        # JDK 25 → build/libs/
./gradlew runServer    # dev server on :25565
```

## License

MIT. Bundled [Vanilla Tweaks](https://vanillatweaks.net/) packs redistributed with
attribution per their terms — see `credits.txt`.
