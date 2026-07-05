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
| 0–48h | at the death site, owner-only |
| 48h | label turns red — **anyone may rob it** where it fell; robbed stones crack |
| 14 days | robbed **wild** stones crumble to ash (cemetery stones never decay) |
| 21 days | a still-unlooted grave **drifts** to the nearest sanctuary graveyard (claim fee applies there) |

Loot never decays — timers only ever move it or expose it, never delete it.

**The headstone remembers, then forgets.** Line 1 is your name; line 2 is an epitaph that blurs
with age — exact cause + in-game day when fresh ("Slain by a skeleton · Day 16"), then "some weeks
past", then the cause collapses to a place ("Fell in the wilds, long ago"), and finally "Lost to
time". Nature reclaims the plot too: the ground turns from freshly-turned podzol to grass, and after
a week a flower blooms (rarely a wither rose — mind the thorns). The grave block and its ground are
**unbreakable by anyone, owner included** — only the flower on top can be picked.

## Graveyards & the Gravekeeper

**Consecration ritual** (survival, no commands): inside your *own* sanctuary, fence a pen —
9×9 up to 81×81, gate allowed — and build an iron golem body crowned with a **skeleton or
wither skeleton skull**. The effigy is consumed; the **Gravekeeper** rises, a still cleric
standing vigil over his grounds. One graveyard per sanctuary. (Admins: `/sanctuarygraveyard set`.)

**Outgrew it?** Re-run the effigy ritual with a *larger* fence around the same yard — as long as
the new pen still encloses every resting grave, the graveyard **resizes** and re-lays its graves
into the wider ground. A smaller pen (or one that would fence a grave out) is refused.

Plots are 1×3 — headstone plus a walkable lane. When the yard fills it makes room: oldest
looted memorial cleared first, else the oldest unlooted grave enters the **keeper's hold** —
loot intact, off the lawn.

**Talk to the keeper** to: reclaim held remains (claim fee), summon your loot-bearing graves
from the wild or rival cemeteries (5% fee — an **allay courier** flies off and delivers the
headstone), or claim anyone's held estate once its 48h public timer lapses.

**Admins** can sweep the world clean with `/sanctuarygrave clearworld` — every loot-bearing grave's
inventory is moved into the nearest keeper's hold (reclaimable, nothing lost), empty stones
deleted. Add `includegraveyard` to also clear in-yard graves and their ground. It refuses if no
graveyard exists (nowhere to hold the loot).

Never enable the bundled VT `graves` pack — the native system replaces it.
Config knobs: `respawn.*`, `grave.*` — see the [server guide](SERVER-GUIDE.md).
