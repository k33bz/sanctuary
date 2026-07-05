# Sanctuary — Mechanics & Math Reference

Every formula in the mod, with defaults, tables, and worked examples. Config keys live in
`config/sanctuary.json`; all values below are the shipped defaults. Ops can change most values
live via `/sanctuary set <key> <value>` (then `/sanctuary save` to persist).

The pure math lives in [`SurvivalLogic.java`](../src/main/java/com/k33bz/sanctuary/SurvivalLogic.java)
and is unit-tested in isolation; the tables below are generated from those formulas.

---

## 1. The distance model

Everything scales from one number: **blocks beyond the nearest sanctuary's safe edge**.

```
beyond(x, z) = max(0,  min over all anchors A of:  dist2D((x,z), A) − A.safeRadius)
```

- Anchors are the **union of safe circles** — inside *any* anchor's radius, `beyond = 0`
  (absolutely safe; no stacking bonus for overlap, no penalty either).
- Between anchors, distance is measured to the **nearest safe edge**, so a chain of anchors
  forms a contiguous safe corridor, and a frontier anchor pulls the whole difficulty
  gradient down around itself.
- Two anchor sources are merged (min of both): the config list (`anchors`, default spawn
  `0,0 r=128`) and player-placed anchors (radius 128, persisted in
  `config/sanctuary_anchors.json`).

**Sanctuaries actually shelter** (`suppressHostileSpawnsInSanctuary`, default on): natural
hostile spawns are cancelled inside active safe zones — the dark corners of your town stay
empty. Spawner blocks, spawn eggs, and commands are untouched, and hostiles that wander in are
merely vanilla-strength (and de-buffed by the revert pass if they were wild).

**The Sanctuary Crystal.** A placed anchor is a **Sanctuary Crystal** — a player head wearing the
crystal skin (pure vanilla block; renders on every client). Placing one forms the anchor
(activation flash + floating label + recurring particle pulse and sonic-boom accent); breaking it
drops the crystal back (identity rides on the head's profile, which survives the place/break
round-trip). As of **0.8.0 the crystal is craft-only** — there is **no mob-drop path**; it is made
through the Wild Membrane crafting chain below. Ops can still spawn one with `/sanctuary crystal give`.

**Wild Essence** (`wildEssenceEnabled`). A textured player head — a glowing ender-eye, anvil-proof
by profile name like the crystal — but a **crafting reagent, not an anchor** (placing it does
nothing). It is the raw material of the chain, dropped only by **player-attributed** kills:

| Source | Wild Essence drop |
|---|---|
| **Warden** (any tier) | **guaranteed** (`wardenEssenceChance` = 1.0; pairs with the Warden→cap attunement — one fight, both rewards) |
| **Nightmare** (tier 4, the top scaling tier) | `nightmareEssenceChance` = 3% |
| anything below Nightmare | never |

Ops give one with `/sanctuary essence give`; both rates are live in config.

**The crafting chain — a built Sanctuary Crystal.** The crystal is now made at a crafting table
through two component-aware **special recipes** plus a lava temper. Because the reagents are
textured player heads, the recipes are `CustomRecipe` subclasses (the mechanism vanilla uses for
map-cloning / firework crafting) that inspect stack *components*, not just item ids — a plain or mob
player head is rejected. Every result is computed server-side, so **vanilla clients craft it
unmodified**.

1. **Raw Wild Membrane** (shapeless): **1 Wild Essence + 2 Phantom Membrane + 2 Sponge** (dry or
   wet), nothing else in the grid → **Wild Membrane (Raw)**. The raw membrane is **fire-resistant**
   (the vanilla `minecraft:damage_resistant` component, `is_fire` tag, like netherite gear) so its
   item entity survives lava.
2. **The lava temper** (server-side, no mixin, no furnace): drop a **Raw Wild Membrane into a
   `lava_cauldron`** (strictly lava — not water, not powder snow). After a few seconds of bubbling
   (lava/smoke particles + bubble/extinguish sounds) the raw item is consumed, the **lava cauldron
   empties to a plain cauldron** (lava spent; `beaconLavaConsumed` = true by default), and a
   finished **Wild Membrane** pops up out of the cauldron.
3. **Sanctuary Crystal** (shapeless, full 3×3): **1 Wild Membrane + 1 Conduit + 1 Dragon Egg +
   3 Bottle o' Enchanting + 1 Ominous Bottle + 1 Rabbit's Foot + 1 Poisonous Potato** (9 items,
   no free slots) → the **Sanctuary Crystal**.

`wildEssenceEnabled` gates the whole chain (drops, both recipes, and the lava temper).

**Upkeep & decay** (`anchorUpkeepEnabled`). Player-raised sanctuaries burn fuel measured in
**real hours of server uptime** (72,000 ticks/hour):

```
fresh crystal        = anchorStartHours (24 h)
+1 emerald           = anchorHoursPerEmerald (2.5 h)
+1 emerald block     = anchorHoursPerEmeraldBlock (24 h = 1 day)
+1 dragon egg        = anchorHoursPerEgg (168 h = 7 days)
bank cap             = anchorMaxFuelHours (1536 h = 64 days — exactly one stack of blocks)
```

Blocks are deliberately ~6.7% more efficient than nine loose emeralds (24 h vs 22.5 h) —
compressed vitality keeps better, so serious upkeep is done in blocks: **one block = one day**,
a full stack of 64 blocks = 64 days = the entire bank. Feed by right-clicking the crystal (sneak = whole stack) or via
the furnace-style menu (empty-hand click: left = cursor stack, right = one).

The anchor's floating label shows **owner + remaining time**: `SA [k33bz] (> 7 days remaining)` —
fuzzy buckets (> 1 year / > 30 days / > 7 days / > 1 day) while healthy, then exact hours under
24 h, heating up yellow (12–24 h) → gold (6–12 h) → red (< 6 h). Right-clicking the crystal
empty-handed shows the same readout; `/sanctuary anchor list` gives ops every anchor's (x, y, z),
owner, and precise hours (config anchors are listed as *global, server-owned*).

A dry anchor goes **dormant**: the crystal stays placed, but the zone reverts to wildlands
(players inside see the boundary message fire naturally), the label shows *dormant — needs a
dragon egg* in dark red, and its Flan claim is released. **Emeralds cannot relight a dormant
anchor — only a dragon egg can** (which also works as a +7-day top-up on a living one). Pairs
well with the Vanilla Tweaks *dragon drops* datapack, which makes the ender dragon drop an egg
on every kill — so rekindling a sanctuary means someone has to go slay the dragon. **Exempt anchors never decay**: creative-mode
placement, placers with `sanctuary.anchor.admin` (default: op), legacy pre-upkeep anchors, and
anything toggled with `/sanctuary anchor exempt` (nearest anchor within 16 blocks).

**Flan integration** (`flanIntegration`, soft dependency). When the [Flan](https://modrinth.com/mod/flan)
mod is installed, every **active** anchor automatically carries a Flan **admin claim** of
`flanClaimRadius` (16) around the crystal — real block/container grief protection for the anchor
and its town core, created on activation and released on dormancy or break. Without Flan
installed the integration silently does nothing.

**Anchor cap — Warden attunement** (`anchorCapBase` 1, `anchorCapMax` 3): every player starts
able to bind **one** sanctuary. Each raise demands a player-kill of a Warden of the next tier up:

| Raise | Requirement |
|---|---|
| cap 1 → 2 | any Warden |
| cap 2 → 3 | **Feral+** Warden (spawned 500+ blocks out) |
| cap 3 → 4 (admin-raised max) | Savage+ Warden |
| … | Ferocious+, then **Nightmare** Wardens |

Wardens scale like everything else — a Nightmare Warden is ~6,000 HP hitting at 60×: a raid
boss. Admins can set any player's cap directly (`/sanctuary cap set <player> <n>`, may exceed
the max); `/sanctuary cap get <player>` shows bound/cap/next requirement. Creative placement
ignores the cap. Breaking an anchor frees its slot.

**Dormant anchors are raidable — by design.** A dry anchor releases its Flan claim, so anyone
may break the crystal and take it. Keeping the flame lit IS the defense.

**Decay runs on real server time, not playtime.** Offline players' anchors keep burning —
bank fuel before a trip (one emerald-block stack = 64 days), or accept the risk. **Any player
can refuel any anchor** (upkeep is communal); restricting refueling to owners/claim members is
possible via a future permission node if it's ever abused.

**Permissions (LuckPerms-compatible).** Anchor actions check
[fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) nodes (bundled; LuckPerms
implements them when installed, otherwise the defaults apply):

| Node | Default | Gates |
|------|---------|-------|
| `sanctuary.anchor.create` | allow | placing a crystal (forming an anchor) |
| `sanctuary.anchor.break`  | allow | breaking an existing anchor |
| `sanctuary.anchor.admin`  | deny (grant via LuckPerms) | placing an **eternal** anchor in survival; creative placement is always eternal |

**Dimension gating** (`scalingDimensions`, default `["minecraft:overworld"]`): distance-based
systems (mob scaling, world-danger scaling, anchors) exist **only** in listed dimensions.
The Nether (coordinates are ⅛ scale) and the End stay fully vanilla. Anchor creation outside a
scaling dimension is refused with a message.

---

## 2. Wild-mob scaling (System 7)

When a hostile spawns at `beyond > 0`, permanent attribute modifiers are baked in (they persist
in the mob's NBT and survive chunk reloads — a deep mob stays strong even if it wanders):

```
multiplier(beyond, perBlock, cap)           = min(cap, 1 + beyond·perBlock)          (linear stats)
multiplier(beyond, perBlock, cap, exponent) = min(cap, 1 + (beyond·perBlock)^exponent) (damage & XP)
```

| Stat         | perBlock  | Cap  | Curve                       | Cap reached at |
|--------------|-----------|------|-----------------------------|----------------|
| Damage       | `0.001`   | 60×  | exponent (default 1.0)      | 59,000 blocks  |
| Health       | `0.0015`  | 12×  | linear                      | ~7,333 blocks  |
| Speed        | `0.00003` | 1.4× | linear                      | ~13,333 blocks |
| XP reward    | `0.0015`  | 20×  | exponent (same as damage)   | ~12,667 blocks |
| Follow range | `0.0001`  | 3×   | linear                      | 20,000 blocks  |

### 2.1 Fuzzy zone edges (`edgeFuzz`, default `0.12`)

Each spawn's **effective distance** is jittered once, and all of its stats share that roll:

```
effectiveBeyond = max(0, beyond · (1 + clamp(N(0,1), −3, 3) · edgeFuzz))
```

- σ = 12% of distance → ~68% of spawns land within ±12%, ~95% within ±24%, hard-clamped at ±36%.
- Tier boundaries become **bands**: a mob on the Savage line rolls Savage-or-better 50/50;
  one 10% short of the line still comes out Savage ~18% of the time; 20% short, ~5%.
- The floor at 0 means **inside a sanctuary is never fuzzed into danger**.
- Set `edgeFuzz: 0` for exact, deterministic boundaries.

### 2.2 The curve exponent (`damageCurveExponent`, default `1.0`)

Applies to **damage and XP only** (health/speed/follow stay linear so deep mobs hit
disproportionately harder without becoming damage sponges). With exponent *e*:

```
damageMult = 1 + (0.001 · beyond)^e     (capped at 60×)
```

| beyond | e = 1.0 (default) | e = 1.5 | e = 2.0 |
|--------|-------------------|---------|---------|
| 1,000  | 2.0×              | 2.0×    | 2.0×    |
| 2,000  | 3.0×              | 3.8×    | 5.0×    |
| 4,000  | 5.0×              | 9.0×    | 17×     |
| 8,000  | 9.0×              | 23.6×   | 60× (cap) |
| 16,000 | 17×               | 60× (cap) | 60× (cap) |

The default stays linear because the level-milestone pacing (§7) was tuned against it; raise the
exponent to compress the "deadly frontier" closer to home. The XP curve uses the same exponent so
risk and reward stay proportional. All inverse calculations (sanctuary revert, rabid damage)
invert the curve exactly: `beyond = (damageBonus)^(1/e) / perBlock`.

### 2.3 Threat tiers

Tier is derived from the mob's **damage bonus** (= damageMult − 1), so with fuzz a tier tells you
what the mob actually rolled, not just where it stands:

| Tier | Name       | Color     | Damage bonus | Nominal distance (e=1) | Particles       |
|------|------------|-----------|--------------|------------------------|-----------------|
| 0    | *(none)*   | —         | < 0.5        | 0 – 500                | none            |
| 1    | Feral      | yellow    | ≥ 0.5        | 500+                   | smoke           |
| 2    | Savage     | gold      | ≥ 1.5        | 1,500+                 | angry villager  |
| 3    | Ferocious  | red       | ≥ 3.0        | 3,000+                 | flame           |
| 4    | Nightmare  | dark red  | ≥ 5.0        | 5,000+                 | soul-fire flame |

Named mobs carry a colored custom name (visible on look, not always-on). Particles emit near
players within `particleRange` (48) once per second.

### 2.4 Worked example — zombie (20 HP, 3 base attack), fuzz at mean, e = 1.0

| beyond | Damage | Health | Speed | XP  | Follow | Tier |
|--------|--------|--------|-------|-----|--------|------|
| 0      | 3      | 20     | 1.0×  | 1×  | 35     | —    |
| 500    | 4.5    | 35     | 1.02× | 1.75× | 37   | Feral |
| 1,500  | 7.5    | 65     | 1.05× | 3.25× | 40   | Savage |
| 3,000  | 12     | 110    | 1.09× | 5.5×  | 45   | Ferocious |
| 5,000  | 18     | 170    | 1.15× | 8.5×  | 52.5 | Nightmare |
| 10,000 | 33     | 240 (cap) | 1.3× | 16×  | 70  | Nightmare |
| 59,000+| 180 (cap) | 240 (cap) | 1.4× (cap) | 20× (cap) | 105 (cap) | Nightmare |

---

## 3. Hunters, door-breakers, frame smashing

**Hunters** — the follow-range multiplier above: deep mobs notice players from up to 3× vanilla
range (a 10k-blocks zombie sees ~70–84 blocks vs vanilla ~35–54).

**Door-breakers** (zombies only) — rolled once at spawn:

```
P(door-breaker) = clamp((beyond − doorBreakStartBlocks) · doorBreakChancePerBlock, 0, 1)
                = clamp((beyond − 1000) · 0.00015, 0, 1)
```

| beyond | 1,000 | 2,000 | 3,000 | 5,000 | 7,667+ |
|--------|-------|-------|-------|-------|--------|
| Chance | 0%    | 15%   | 30%   | 60%   | 100%   |

A door-breaker gets vanilla's `BreakDoorGoal` **without the Hard-difficulty gate** (works on any
difficulty; still requires `mobGriefing`). Iron doors are never breakable — that's the intended
counterplay. Status persists via the `sanctuary_door_breaker` entity tag (goals are transient and
re-attached every chunk load).

**Frame smashing** (`frameSmashEnabled`) — a door-breaker whose target is **unreachable**
(its current path can't reach) and who stands within `frameSearchRange` (4) of a
**player-placed** wooden door may smash one frame block at a time:

- Eligible blocks: within `frameRadius` (1) of the door, hardness in (0, `frameMaxHardness`=2.0],
  no block entity, not a door itself. Planks/glass/wool/dirt/cobble qualify; obsidian, iron
  blocks, chests never.
- Takes `frameSmashTimeTicks` (160 ≈ 8 s) per block with the vanilla crack animation and
  door-pounding sounds; the block drops as an item.
- **Player-placed** is tracked by a `BlockItem.place` hook recording wooden doors placed by
  players (scaling dimensions only) into `config/sanctuary_doors.json` (pruned automatically when
  a door is gone). World-generated structures are never recorded, so villages are never at risk.

---

## 4. Rabid wildlife

In **Savage+ zones (tier ≥ 2)**, each animal spawning rolls `rabidChance` (25%) to turn rabid:

- Buffed like monsters (health/speed/follow, same fuzzed distance), tier-colored name.
- Hunts players via a custom attack goal (most passive mobs have **no attack-damage attribute**,
  so vanilla melee AI can't drive them). Damage per hit:

```
rabidDamage = rabidBaseDamage · damageMult(spawn) = 2.0 · damageMult
```

(the spawn distance is recovered from the health buff, then run through the damage curve).

- Exempt: tamed animals (wolves/cats/etc.), tamed horses, babies.
- Attack cooldown 1 s; goals gate on the persistent `sanctuary_rabid` tag.

---

## 5. Sanctuary revert (anti-farming)

With `revertInSanctuary` (default on), any buffed mob (monster or rabid animal) standing
**inside a safe zone** is stripped back to vanilla during the once-per-second near-player pass:

1. All four attribute modifiers removed; health clamped to the new max.
2. **XP reward un-scaled**: spawn distance recovered by inverting the damage curve, then
   `xpReward /= xpMult(spawnBeyond)` — so dragging a 20×-XP Nightmare mob home pays vanilla XP.
3. Door-breaker and rabid tags removed (rabid AI shuts off instantly; player targets cleared).
4. The mod's tier name is cleared **only if unchanged** — a name given with a real name tag is
   different text and survives. Trophy pets are safe.

---

## 6. World-danger scaling (System 4)

Multiplies damage **players take from living attackers** (never environmental damage), applied
*before* armor so the world can outpace mitigation:

```
days   = max(0, gameTime − epochTick) / 24000
danger = clamp( (1 + 0.15·difficultyId) · (1 + perDayWeight·days) · (1 + perBlockWeight·beyond),
                1, maxMultiplier=4 )
```

The age is measured from a persisted **epoch**, not from tick 0: `/sanctuary danger reset`
re-zeroes the pressure without touching the world clock (and saves immediately), and
`/sanctuary danger status` shows the days accrued and the multiplier they currently produce.

| Knob | Default | Note |
|------|---------|------|
| `difficultyWeight` | 0.15 | peaceful=0 … hard=3 → up to +45% |
| `perDayWeight` | **0.0005** | per *in-game* day. An always-on server burns ~72 in-game days per real day → ≈ +3.6%/real-day, cap after ~3 months. (0.02 hit the cap in 2.5 real days — do not raise casually.) |
| `perBlockWeight` | **0** | mob attributes already scale with distance; a nonzero value **multiplies** with them (0.0005 made 5k blocks ~24× instead of 6×). Kept as a knob for experiments. |
| `maxMultiplier` | 4.0 | hard ceiling |
| `epochTick` | 0 | set by `/sanctuary danger reset`; age counts from here |

Only applies in scaling dimensions.

---

## 7. Player survivability (XP as life force)

| System | Formula (defaults) | Notes |
|--------|--------------------|-------|
| **Regen** (1) | heal 2 HP/s while hurt; cost = `ceil(heal · 2)` XP points | drains XP, never levels below 0; vanilla food regen still applies |
| **Armor** (2) | `min(0.25 · level, 20)` armor points | real armor icons; vanilla reduction ≈ `armor/25` → 80% at the level-80 cap |
| **Hearts** (5a) | +2 HP (1 heart) per milestone reached | milestones: 10, 25, 50, 100, 250, 500, 1000, 2500, 5000 |
| **Shield** (5b) | absorption = `min((level − M)/M, 2.0) · maxHealth`, where M = highest milestone ≤ level | delivered as an infinite Absorption effect (4-HP steps); refills only after `max(1, 10 − milestones)` s out of combat |
| **Breath** (6) | `OXYGEN_BONUS = min(1.0 · level, 1000)` | air lasts ≈ `(bonus+1)·15 s` — level 500 effectively cannot drown |
| **Lethal save** (3) | survive a killing hit for `max(1, ceil(0.5 · damage))` levels, revive at 1 heart | only if affordable; otherwise death proceeds |

### 7.1 Player power snapshot

| Level | Armor (reduction) | Bonus hearts | Shield (of max HP) | Milestones |
|-------|-------------------|--------------|--------------------|-----------| 
| 5     | 1.25 (5%)         | 0            | ~50% (5/10 ramp)   | 0 |
| 15    | 3.75 (15%)        | +1           | 50% (15/10 − 1)    | 1 |
| 40    | 10 (40%)          | +2           | 60% (40/25 − 1)    | 2 |
| 80    | 20 (80%, cap)     | +3           | 60% (80/50 − 1)    | 3 |
| 150   | 20 (cap)          | +4           | 50% (150/100 − 1)  | 4 |
| 300   | 20 (cap)          | +5           | 20% (300/250 − 1)  | 5 |

(The shield's saw-tooth is intentional: passing a milestone banks a permanent heart but resets
shield progress against the next, bigger milestone.)

---

## 8. Equipment, armor & enchantments

The mod adds no gear mechanics of its own — it slots into vanilla's damage pipeline, which
produces sharp emergent rules. Pipeline order for a player taking a hit:

```
danger multiplier (§6) → armor reduction → Protection (EPF) → Resistance → absorption (shield) → health
```

### 8.1 Armor and the penetration formula

Vanilla armor is not a flat percentage — big hits punch through:

```
reduction = min(20, max(armor/5, armor − damage/(2 + toughness/4))) / 25
```

Sanctuary's level-armor (§7) grants **armor points but zero toughness**, so it collapses
against exactly the huge hits the deep wildlands deal:

| Scenario (60-damage Nightmare hit) | armor | toughness | effective reduction |
|---|---|---|---|
| naked level-80 player | 20 | 0 | `20 − 60/2 = −10` → floor `armor/5` → **16%** |
| full netherite, level 0 | 20 | 12 | `20 − 60/5 = 8` → **32%** |
| full netherite + level 80 | 40 | 12 | `40 − 60/5 = 28` → clamp 20 → **80%** |

Against a small hit (≤ ~10 damage) all three sit at ~80%. **Conclusion: levels protect you
where you live; gear is what lets you leave.** The two stack additively in armor points, and
the toughness only comes from worn armor.

### 8.2 Protection enchantments

Protection applies AFTER armor, at 4%/EPF capped at EPF 20 (80%): full Prot IV (EPF 16) = 64%.
Multiplied with capped armor: `0.20 × 0.36 ≈ 7%` of the original hit — ~93% total reduction.
Since the absorption shield soaks post-reduction damage, every reduction layer effectively
multiplies the shield's size. Gear tiers translate directly into survivable depth.

### 8.3 Weapons, Mending, totems

- **Player damage output is untouched** — deep mobs have up to 12× health, so Sharpness,
  crits, and potions are the offense ladder while XP is the defense ladder.
- **Mending** repairs from XP orbs *before* they become levels: durability is literally paid
  in life force. Affordable out deep (16–20× XP mobs); noticeable drag near home.
- **Totem of Undying** triggers before the lethal save — the totem is consumed first, levels
  are only spent as the fallback. Carrying totems is level-insurance.
- **Fall/environmental damage** is never scaled by the mod (danger scaling requires a living
  attacker), so Feather Falling and MLG tricks work at vanilla rates everywhere.

---

## 9. Threat readout (boundary messages & skulls)

Zone = tier of the **exact** local damage multiplier (no fuzz — the readout describes the zone;
individual mobs vary around it). On crossing a zone edge (and ~3 s after every login), the
actionbar shows the zone with a **personalized 5-skull scale**:

```
playerPower = 1 + level / skullLevelsPerMultiplier      (default: 15 levels per +1×)
skulls      = clamp(round(3 · zoneMult / playerPower), 0, 5)
```

| | zone 2× (Savage edge) | zone 4× (Ferocious) | zone 6× (Nightmare) |
|--------------|------|------|------|
| **Level 5**  | ☠☠☠☠☠ (5) | ☠☠☠☠☠ (5) | ☠☠☠☠☠ (5) |
| **Level 25** | ☠☠ (2) | ☠☠☠☠☠ (5) | ☠☠☠☠☠ (5) |
| **Level 50** | ☠ (1) | ☠☠☠ (3) | ☠☠☠☠ (4) |
| **Level 100**| ☠ (1) | ☠☠ (2) | ☠☠ (2) |

3 skulls = even fight; 5 = near-certain death; 0–1 = farmland. Message color follows the skull
count (green → yellow → gold → red → dark red); unfilled skulls render dark gray. Messages are
rate-limited per player (`boundaryMessageCooldownSeconds`, 10 s) — bouncing on a boundary updates
state silently. The glyph is `☠` (U+2620); emoji are not in Minecraft's font.

---

## 10. Config reference

| Key | Default | Section |
|-----|---------|---------|
| `regenEnabled` / `regenIntervalTicks` / `regenHealPerInterval` / `regenXpPerHealth` | true / 20 / 2.0 / 2.0 | §7 |
| `armorEnabled` / `armorPerLevel` / `armorMax` | true / 0.25 / 20 | §7 |
| `heartsEnabled` / `milestones` / `hpPerMilestone` | true / [10…5000] / 2.0 | §7 |
| `shieldEnabled` / `shieldMaxFraction` / `shieldRegenCooldown{Base,PerMilestone,Min}` | true / 2.0 / 10 / 1 / 1 | §7 |
| `breathEnabled` / `oxygenPerLevel` / `oxygenMax` | true / 1.0 / 1000 | §7 |
| `lethalSaveEnabled` / `lethalSaveLevelsPerDamage` / `lethalSaveMinLevels` / `lethalSaveReviveHealth` | true / 0.5 / 1 / 2.0 | §7 |
| `danger.*` | see §6 | §6 |
| `anchors` | spawn 0,0 r128 | §1 |
| `scalingDimensions` | overworld | §1 |
| `anchorShowLabel` / `anchorLabelHeight` | true / 1.6 | cosmetic |
| `wildEssenceEnabled` | true | §1 — gates Wild Essence drops + the whole crafting chain |
| `wardenEssenceChance` / `nightmareEssenceChance` | 1.0 / 0.03 | §1 — Warden=guaranteed, Nightmare(tier 4)=3%, else none |
| `beaconLavaConsumed` | true | §1 — lava temper empties the lava cauldron to a plain cauldron |
| `anchorMinSpacing` | 192 | §1 — min center distance between anchors (creative bypasses) |
| `anchorCapBase` / `anchorCapMax` | 1 / 3 | §1 — per-player anchor cap, raised by Warden kills |
| `suppressHostileSpawnsInSanctuary` | true | §1 — no natural hostile spawns in active zones |
| `anchorUpkeepEnabled` / `anchorStartHours` | true / 24 | §1 |
| `anchorHoursPerEmerald` / `anchorHoursPerEmeraldBlock` / `anchorHoursPerEgg` / `anchorMaxFuelHours` | 2.5 / 24 / 168 / 1536 | §1 |
| `flanIntegration` / `flanClaimRadius` | true / 16 | §1 |
| `mobScaling.enabled` | true | §2 |
| `mobScaling.{health,damage,speed,xp,follow}PerBlock` + `…MaxMultiplier` | see §2 table | §2 |
| `mobScaling.damageCurveExponent` | 1.0 | §2.2 |
| `mobScaling.edgeFuzz` | 0.12 | §2.1 |
| `mobScaling.particleRange` | 48 | §2.3 |
| `mobScaling.doorBreakStartBlocks` / `doorBreakChancePerBlock` | 1000 / 0.00015 | §3 |
| `mobScaling.frameSmash{Enabled,TimeTicks}` / `frameRadius` / `frameSearchRange` / `frameMaxHardness` | true / 160 / 1 / 4 / 2.0 | §3 |
| `mobScaling.rabidEnabled` / `rabidChance` / `rabidBaseDamage` | true / 0.25 / 2.0 | §4 |
| `mobScaling.revertInSanctuary` | true | §5 |
| `mobScaling.boundaryMessages` / `boundaryMessageCooldownSeconds` / `skullLevelsPerMultiplier` | true / 10 / 15 | §8 |

---

## 11. Implementation notes (for contributors)

- **Server-authoritative**: no client entrypoint (`environment: server`); vanilla clients connect.
  All visuals are vanilla-renderable: attributes, effects, display entities, actionbar text.
- Mob buffs are `ADD_MULTIPLIED_BASE` **permanent** modifiers (persist in NBT, idempotent by
  modifier-presence check). Player buffs are **transient** modifiers re-applied every second.
- Goals do not persist — door-breaker/rabid status rides on entity tags and goals are re-attached
  on every `ENTITY_LOAD`.
- The shield must be the Absorption *effect* (infinite duration): vanilla wipes raw
  `setAbsorptionAmount` each tick and deletes the effect at 0 absorption (which must not be
  mistaken for a fresh grant, or it refills mid-combat).
- Access wideners: `Mob.xpReward`, `Mob.goalSelector`, `Mob.targetSelector` (namespace
  `official` — 26.x is un-obfuscated).
- 26.x API notes: `ResourceKey#identifier()`, `Player#sendOverlayMessage`, `Entity#entityTags()`,
  `GameRules` at `world.level.gamerules` read via `level.getGameRules().get(GameRules.MOB_GRIEFING)`,
  `Zombie` at `entity.monster.zombie`.
