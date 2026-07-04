# Death, Respawn & Graves — your soul settles

## Respawn choice

Death sends you to the **nearest active sanctuary, free**. A dialog then offers upgrades,
priced as a fraction of the level you died with:

| Option | Cost | Notes |
|---|---|---|
| Rest here | free | beyond normal [soul retention](VITALITY.md), nothing charged |
| Return to bed | 5% | only if you have a bed/respawn point set |
| Resurrect where you fell | 15% | the corpse-run skip — your grave is right there |

**The death toll**: each death adds +25% to those prices. It decays 1% per 10 minutes
*played* (offline time doesn't count) and **clears entirely at your next XP milestone** —
climbing is cleansing. The free option never escalates, so a bad streak can't spiral you out.

## Graves

Your inventory seals into a grave at the death site: a headstone bearing **your own face**,
despawn-proof, hopper-proof (items live in the mod's ledger, not the world). Right-click to
claim. Timeline in **real hours**:

| Age | State |
|---|---|
| 0–24h | at the death site, owner-only |
| 24h | **drifts** to the nearest sanctuary graveyard (small claim fee applies there) |
| 48h | label turns red — **anyone may rob it**; robbed stones crack |
| 14 days (looted only) | the cracked memorial crumbles to ash — loot never decays |

## Graveyards & the Gravekeeper

**Consecration ritual** (survival, no commands): inside your *own* sanctuary, fence a pen —
9×9 up to 81×81, gate allowed — and build an iron golem body crowned with a **skeleton or
wither skeleton skull**. The effigy is consumed; the **Gravekeeper** rises, wandering his
grounds, never past the fence. One graveyard per sanctuary. (Admins: `/sanctuarygraveyard set`.)

Plots are 1×3 — headstone plus a walkable lane. When the yard fills it makes room: oldest
looted memorial cleared first, else the oldest unlooted grave enters the **keeper's hold** —
loot intact, off the lawn.

**Talk to the keeper** to: reclaim held remains (claim fee), summon your loot-bearing graves
from the wild or rival cemeteries (5% fee — an **allay courier** flies off and delivers the
headstone), or claim anyone's held estate once its 48h public timer lapses.

Never enable the bundled VT `graves` pack — the native system replaces it.
Config knobs: `respawn.*`, `grave.*` — see the [server guide](SERVER-GUIDE.md).
