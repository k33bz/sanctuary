# Sanctuary — Design Spec (v0.5.0)

Server-authoritative Fabric mod for Minecraft 26.1.2. Two intertwined economies:
**XP is a life force** (it heals, armors, shields, and can buy back your life), and
**distance is danger** (everything scales with how far you are from the nearest sanctuary).

This file is the design intent; the exact formulas, tables, and worked examples live in
[docs/MECHANICS.md](docs/MECHANICS.md). History in [CHANGELOG.md](CHANGELOG.md).

## The loop

1. Spawn is one small safe circle in a hostile world. Mobs get stronger, faster, richer in XP,
   and smarter (hunters, door-breakers, rabid wildlife) the farther you go.
2. Leveling makes you visibly tougher (armor icons, bonus hearts, an absorption shield, breath,
   a lethal save) — and death only costs a level-scaled *fraction* of it (soul retention).
3. The frontier's strongest monsters (Ferocious+) rarely drop **Sanctuary Crystals**. Placing one
   raises a new safe zone — with a floating status label, a spinning shell while alive, an
   auto-created Flan admin claim, and suppressed hostile spawns.
4. Sanctuaries **burn fuel** (real hours). Emeralds maintain them (blocks are the efficient
   rate); a dry one goes dormant — no safety, no claim, raidable — and only a **dragon egg**
   rekindles it. The Vanilla Tweaks *dragon drops* pack makes eggs renewable: reviving a
   sanctuary means someone slays the dragon.
5. How MANY sanctuaries you may bind is earned: everyone starts at 1; **Warden kills of rising
   tiers** raise the cap (any Warden → 2, Feral+ → 3, … Nightmare), bounded by config unless an
   admin blesses more.

## Design rules

- **Vanilla clients always work.** No client mod, no resource pack: attributes, effects,
  display entities, actionbar text, container GUIs (sgui), textured player heads.
- **Only the Overworld scales** (`scalingDimensions`); the Nether/End stay vanilla.
- **Communication is layered**: tier-colored mob names → threat particles → boundary/login
  actionbar messages with a personalized ☠-scale → anchor labels → the furnace menu.
- **No silent economies.** Anti-farming is explicit (sanctuary revert un-buffs and un-XPs
  dragged mobs); deliberate farm engineering is a *rewarded* playstyle, observed via the
  kill-metrics ledger + NDJSON event log rather than nerfed.
- **Admin ≠ player.** Creative placement makes eternal, cap-free, spacing-free anchors;
  survival placement (even by ops) pays upkeep and progression. LuckPerms nodes
  (`sanctuary.anchor.create/break/admin`) refine this when installed.
- **Everything is a config knob**, live-tunable via `/sanctuary set` and persisted as JSON.

## Non-goals

- Client-side rendering of zone borders (would need a client mod).
- Hard region protection beyond the anchor's Flan claim — that's Flan's job.
- Punishing automated farms; the ledger observes, players build.

## 0.6.1 candidates (noted 2026-07-04)

Dialog input controls (26.x native dialogs: text fields incl. multiline, single_option
dropdown-cycles, checkboxes, number_range sliders — already proven by the anchor/respawn/
gravekeeper dialogs) open UI upgrades that container menus could not do:

- Anchor rename: text input on the anchor dialog ("Name this sanctuary"), name shown on the
  floating label and in /sanctuary anchor list.
- Gravekeeper ledger search: text-input filter + re-open when a keeper holds many estates.
- Feral egg wagers: number_range slider for arena/market bets (pairs with custom villager
  shop stalls).
- Respawn dialog: single_option to pick among multiple owned sanctuaries instead of nearest.

Non-goals (decided 2026-07-04): NO teleport systems in Sanctuary — gmc101 runs sswaystones
and the VT teleport packs were deliberately excluded from the bundle; travel friction is the
point, and waystones are the server's sanctioned exception.
