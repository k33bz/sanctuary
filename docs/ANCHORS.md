# Sanctuary Anchors — carving out civilization

A sanctuary is a safe circle: no scaling, no natural hostile spawns, buffed intruders revert
to vanilla. The world ships with one at spawn; every other one is player-made.

## How to raise one

1. Gather **Wild Essence** from the frontier and **craft** a **Sanctuary Crystal** through the
   chain below. (As of 0.8.0 there is no crystal mob-drop — the crystal is craft-only.)
2. Place it. That's the anchor — a spinning crystal head protecting r128.
3. Breaking it drops the crystal back.

## Crafting a sanctuary

The crystal is built through two **special crafting recipes** (component-aware `CustomRecipe`s, so
the textured reagent heads are matched by identity, not just item id) plus a lava temper. All
server-side — vanilla clients craft it unmodified.

**The raw material — Wild Essence.** A glowing ender-eye head dropped by **player-attributed**
kills: a **Warden always yields one** (`wardenEssenceChance` = 1.0); the top scaling tier
**Nightmare** (tier 4) drops it at `nightmareEssenceChance` = 3%; nothing below Nightmare drops it.
It is a reagent only — placing it does nothing.

**The chain:**

1. **Craft Raw Wild Membrane** — shapeless: **1 Wild Essence + 2 Phantom Membrane + 2 Sponge**
   (dry or wet), nothing else → **Wild Membrane (Raw)**. It is fire-resistant so it survives lava.
2. **Temper it** — drop the Raw Wild Membrane into a **cauldron of lava** (strictly lava). After a
   few seconds of bubbling the raw item is consumed, the **lava cauldron empties to a plain
   cauldron**, and a finished **Wild Membrane** pops out.
3. **Craft the crystal** — shapeless, full 3×3 (9 items): **1 Wild Membrane + 1 Conduit +
   1 Dragon Egg + 3 Bottle o' Enchanting + 1 Ominous Bottle + 1 Rabbit's Foot + 1 Poisonous
   Potato** → the **Sanctuary Crystal**.

> `wildEssenceEnabled` gates the whole chain; `beaconLavaConsumed` controls whether the temper
> spends the lava. Full recipe details and drop tables: [MECHANICS.md](MECHANICS.md).

## Anchor cap — Warden attunement

Everyone starts able to bind **1** sanctuary. Each additional binding demands a Warden kill of
rising tier: any Warden → 2, Feral+ → 3, up to Nightmare (config caps at 3 unless admins
bless more). A Warden kill also **guarantees** rare drops where lesser mobs only roll chances.

## Upkeep — sanctuaries burn fuel

| Fuel | Time |
|---|---|
| Fresh crystal | 24 h |
| Emerald | +2.5 h |
| Emerald block | +24 h (the efficient rate) |
| Dragon egg | +7 days, and the only way to rekindle a **dormant** anchor |

Clock runs on **real time**, online or not; bank cap 1536 h. Empty anchors go dormant: shell
stops spinning, safety ends, the Flan claim releases, the region is raidable. Click the
crystal empty-handed for the fuel menu (dialog or furnace-style, config). Admin anchors are
eternal and gold-labeled.

## Naming a sanctuary

Open the anchor's dialog menu (click the crystal empty-handed) and press **Name this
sanctuary** — a text input takes up to 24 characters. Only the anchor's **owner** (or a creative
admin) sees the button and may rename. The name replaces "Sanctuary Anchor" on the floating
label (the fuzzy upkeep timer stays on the line below) and appears in `/sanctuary anchor list`.
Naming is free and can be changed or cleared (submit blank) any time. Backed by the
permission-0 `/sanctuaryrename` command.

When you die owning **two or more active sanctuaries**, the respawn dialog offers a picker to
choose which one to wake at (free); the nearest to where you fell is the default. See
[DEATH-AND-GRAVES.md](DEATH-AND-GRAVES.md).

## Self-repair — the sanctuary mends itself

An active sanctuary rebuilds damage you didn't cause: creeper and TNT blasts, withers, ravagers
and siege mobs breaking blocks, zombies breaking doors, trampled farmland, fire. The broken blocks
**drop nothing** (so nobody can farm your walls) and come back on their own, cheapest first: dirt
and stone in minutes, iron and diamond blocks in hours. Every rebuilt block costs a sliver of
fuel.

The **owner** picks the speed: **Off, Slow, Normal, Fast, Turbo**. Use the *Repair speed* button in
the dialog menu, click the clock in the furnace menu, or run `/sanctuaryrepair <speed>` next to the
crystal. Faster burns more fuel per block. Repairs never spend the last hour of fuel, and a dormant
sanctuary repairs nothing.

Not repaired: chests and other blocks that hold things (they break normally, contents and all),
blocks other players break (logged for admins), and leaf decay or melting ice. Full formulas:
[MECHANICS.md §12](MECHANICS.md).

## Integrations

- **Flan**: active anchors auto-carry a grief-protection claim over the core (`flanClaimRadius`,
  16 by default), **owned by the anchor's owner**, so you can build there and use Flan's own trust
  menu to give friends granular access (build, doors, containers, redstone...). Released on
  dormancy. If the core overlaps another claim, Flan refuses it and the server log says so.
- **Permissions**: `sanctuary.anchor.create / .break / .admin` via fabric-permissions-api —
  LuckPerms-ready, safe defaults without it.
- **Renewable dragon eggs**: enable the bundled `dragon drops` VT pack
  ([server guide](SERVER-GUIDE.md)) so every respawned dragon drops one.

Spacing: 192 blocks minimum between anchors. Overworld only by default.
Full tables: [MECHANICS.md](MECHANICS.md).
