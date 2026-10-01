# Sanctuary

[![live on gmc101](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/gmc101.json&style=flat-square)](https://github.com/k33bz/sanctuary/deployments/gmc101)
[![gmc101 → 26.3](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/modpack-26.3.json&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/modpack.yml)
[![forgejo parity](https://img.shields.io/github/actions/workflow/status/k33bz/sanctuary/forgejo-parity.yml?label=forgejo%20parity&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/forgejo-parity.yml)

**Minecraft 26.3** · [`main`](../../tree/main)<br>
[![build](https://img.shields.io/github/actions/workflow/status/k33bz/sanctuary/build.yml?branch=main&label=build&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain)
[![sanctuary](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/mod.json&label=sanctuary&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain)
[![loader](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/loader.json&label=loader&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain)
[![fabric-api](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/fabric-api.json&label=fabric-api&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain)
[![flan](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/flan.json&label=flan&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain)
[![server test](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/main/server-test.json&label=server%20test&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3Amain)

**Minecraft 26.2** · [`26.2`](../../tree/26.2)<br>
[![build](https://img.shields.io/github/actions/workflow/status/k33bz/sanctuary/build.yml?branch=26.2&label=build&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.2)
[![sanctuary](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.2/mod.json&label=sanctuary&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.2)
[![loader](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.2/loader.json&label=loader&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.2)
[![fabric-api](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.2/fabric-api.json&label=fabric-api&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.2)
[![flan](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.2/flan.json&label=flan&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.2)
[![server test](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.2/server-test.json&label=server%20test&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.2)

**Minecraft 26.1.2** · [`26.1`](../../tree/26.1) — runs gmc101<br>
[![build](https://img.shields.io/github/actions/workflow/status/k33bz/sanctuary/build.yml?branch=26.1&label=build&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1)
[![sanctuary](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/mod.json&label=sanctuary&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1)
[![loader](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/loader.json&label=loader&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1)
[![fabric-api](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/fabric-api.json&label=fabric-api&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1)
[![flan](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/flan.json&label=flan&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1)
[![server test](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/k33bz/sanctuary/badges/26.1/server-test.json&label=server%20test&style=flat-square)](https://github.com/k33bz/sanctuary/actions/workflows/build.yml?query=branch%3A26.1)

<sub>Badges are written by CI on every push to `main`/`26.2`/`26.1` (`scripts/publish_badges.py`, kept on the `badges` branch). fabric-api and flan are the versions the real-server CI test booted with.
"gmc101 → 26.3" counts third-party mods with no 26.3 build yet (weekly, `scripts/check_modpack.py`).</sub>

**Your XP is your life force. Distance is danger.** A server-side Fabric mod — vanilla
clients connect with no mods; everything renders through vanilla attributes, effects, and
display entities.

Your levels heal you, armor you, and buy back your life. The world scales the other way: the
farther from a **sanctuary anchor**, the deadlier everything gets. Civilization is something
you carve out of the wilds — and defend, fuel, and one day get buried in.

## Versions

| Minecraft | Branch | Fabric API | Notes |
|---|---|---|---|
| 26.3 | [`main`](../../tree/main) | 0.161.0+26.3 | active development; Flan 1.12.8.b |
| 26.2 | [`26.2`](../../tree/26.2) | 0.161.0+26.2 | backports; Flan 1.12.8 (needs fabric-api >= 0.158) |
| 26.1.x | [`26.1`](../../tree/26.1) | 0.154.0+26.1.2 | live on gmc101; backports |

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
