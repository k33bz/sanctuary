# The Wilds — distance is danger

Every hostile spawn is buffed by its distance beyond the nearest [sanctuary](ANCHORS.md):
health, damage, speed, follow range, and XP reward all scale, baked permanently into the mob.

## Threat tiers

Damage multiplier grows `+1× per 1000 blocks` (linear by default, cap 60×). Tier by bonus:

| Tier | Damage bonus | Roughly begins | Name color |
|---|---|---|---|
| Feral | ≥ 0.5 | ~500 blocks | yellow |
| Savage | ≥ 1.5 | ~1,500 | gold |
| Ferocious | ≥ 3.0 | ~3,000 | red |
| Nightmare | ≥ 5.0 | ~5,000 | dark red |

Zone edges are fuzzy (±12% per spawn) — boundaries are bands, not lines. Buffed mobs emit
tier particles; an actionbar readout shows your personal threat as ☠ skulls (your levels vs
the zone) when you cross boundaries.

## What hunts you out there

- **Hunters** — deep mobs notice you from up to 3× vanilla range.
- **Door-breakers** — past 1000 blocks, zombies break wooden doors on any difficulty, and
  smash the frame blocks of *player-placed* doors when the way is blocked.
- **Rabid wildlife** — in Savage+ zones, 25% of animals spawn hostile. Turned animals read
  **Feral <name>** — the color alone tells the tier. Their eggs are a
  [whole economy](FERAL-EGGS.md).
- **The Restless** — skip sleep and mine anyway: once per night, underground players with 2+
  days of sleep debt are visited by Restless Creakings that only move when unwatched. Count
  grows with insomnia; they dissolve at dawn. Phantoms own the surface; these own the mines.
- **XP goldmine** — deep mobs drop up to 20× XP. The death zone pays.

Anti-farming: any buffed mob standing inside a sanctuary reverts to vanilla (XP included).

Full formulas and config: [MECHANICS.md](MECHANICS.md).
