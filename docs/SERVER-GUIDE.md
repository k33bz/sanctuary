# Server Guide — commands, config, bundled extras

## Live tuning

All balance levers live in `config/sanctuary.json`; ops (level 2+) tune **live** — every
value is read fresh each tick/hit, no restarts:

```
/sanctuary list | get <key> | set <key> <value> | toggle <system> | save | reload
```

Highlights: `/sanctuary crystal give`, `/sanctuary cap get|set`, `/sanctuary anchor
list|time|exempt`, `/sanctuary danger status|reset`, `/sanctuary metrics top`.
Full command and key list: [MECHANICS.md](MECHANICS.md).

## Bundled Vanilla Tweaks (59 packs, all opt-in)

Curated [Vanilla Tweaks](https://vanillatweaks.net/) datapacks + crafting tweaks ship inside
the jar as built-in datapacks — **all disabled by default**:

```
/datapack list available
/datapack enable "sanctuary:vt/<name>"
```

Notables: `timber`, `dragon_drops` (renewable eggs for anchors), `xp_bottling`,
`track_statistics` + `track_raw_statistics` (every stat as a scoreboard), `multiplayer_sleep`,
`custom_villager_shops`. Each branch bundles builds matching its Minecraft version.
Deliberately absent: teleport packs (they'd undercut the paid resurrect) and `graves`
(native system replaces it).

## Stat boards — leaderboards on walls

```
/sanctuaryboard add <objective> [title]     (ops; faces you, refreshes every 5s, persists)
/sanctuaryboard remove
```

Works with any scoreboard objective, including everything the VT stat packs emit, plus
Sanctuary's own: `sanct_hatched`, `sanct_gen_best`, `sanct_toll`.

## Quality of life & anti-grief

| Feature | Toggle | Behavior |
|---|---|---|
| [AFK] tab tag | `afkTag` | idle 5 min → gray [AFK] prefix; team-color friendly |
| Creeper terrain mercy | `creeperTerrainProtection` | full entity damage; only player-placed doors + thresholds break |
| Endermen clone | `endermanClone` | world keeps its block; the enderman carries a copy |
| The Restless | `restless` | underground insomnia visits — see [the Wilds](WILDS.md) |

## Observability

Kill metrics ledger (`/sanctuary metrics top`), NDJSON kill event log (per-day files,
SQLite-convertible), spawn-source tags on every mob, `config/sanctuary_deaths.json` /
`_graves.json` / `_boards.json` ledgers — all built for external dashboards.
