# Changelog

All notable changes to Sanctuary (formerly XP Vitality).

## [0.6.0] — 2026-07-03

### Added — Feral Eggs (breed your way to Nightmare poultry)

Feature concept by **Zugzuggin'** — every Feral Egg carries the credit in its lore.

- **Contaminated clutches**: an egg appearing within a block of a living feral (rabid) hen
  becomes a **Feral Egg** at her tier — still a vanilla egg, renamed and colored by grade
  (Savage gold, Ferocious red, Nightmare dark red), so grades stack separately and trade
  cleanly. The tier lives in the name's *color*, which an anvil cannot forge.
- **Star quality = generations**: every Feral Egg shows 0–5 stars in its name — the
  bloodline's depth. A wild-turned hen lays 0★; each destined generation lays one star deeper
  (cap 5★). The hen herself never shows stars — the lineage hides in her blood (an invisible
  tag) and only reveals itself on her eggs.
- **The hatch gamble** (odds by stars — normal chick / tier down / same / tier up):
  0★ 90/–/9/1 · 1★ 75/5/19/1 · 2★ 66/8/24/2 · 3★ 50/10/35/5 · 4★ 33/10/47/10 ·
  5★ 25/10/45/20, clamped Savage..Nightmare. Most eggs hatch ordinary chickens — five lucky
  generations stand between a wild Savage hen and true 5★ Nightmare stock.
- **The turn**: a destined bird goes feral the moment it loads as an adult — anywhere, no zone
  or chance roll (the gamble already happened). Sanctuaries still pacify it while it stands
  inside (the revert holds); the destiny survives, and the wilds wake it again. Arena fights,
  wilderness ambushes, and gift-wrapped livestock are all now on the table.
- **Frontier ranching (by design)**: a pacified bloodline hen inside a sanctuary lays *plain*
  eggs — starred eggs only come from a hen that is actively feral, which only happens outside
  sanctuary ground. Store your prize stock safely at home; to harvest, run a coop in the wilds
  and defend it. Sanctuaries are literally that — safe. Risk is the price of Nightmare poultry.
- **Feral eggs smolder**: a dropped feral egg emits a subtle red dust mote — still a plain
  vanilla egg item, the aura sells it.
- Config: `feralEggsEnabled` under `mobscaling`; the star table lives in code.
- No new mixins — laying, hatching, and turning all ride the existing entity-load hooks, so the
  feature is identical on every supported Minecraft version.

## [0.5.1] — 2026-07-03

First multi-version release: jars for **Minecraft 26.2** (`main`) and **26.1.x** (branch
`26.1`), named `sanctuary-0.5.1+<mcversion>.jar`.

### Added
- **Native-dialog anchor menu** alongside the sgui furnace menu (A/B), with polish pass:
  text-first dialog, Owner label, compact 8-char anchor id.
- **Per-anchor UUIDs**: precise admin targeting and a filterable
  `/sanctuary anchor list [selector]` (full/8-char id, owner name, `wild*cards`).
- **Admin anchor time commands**: `/sanctuary anchor time add|set <hours> [selector]` —
  nearest anchor by default, or every anchor matching the selector.

### Changed
- Two-line anchor label: name over timer.
- Init log reports the actual running Minecraft version; the Warden anchor-cap check is
  version-stable (`instanceof`) so the same source compiles on 26.1.x and 26.2.

### 26.2 port notes
- Pins: fabric-api 0.154.0+26.2, sgui 2.1.0+26.2. **Flan integration is inert on 26.2**
  (no Flan build published yet; it activates automatically once Flan ports, no update needed).

## [0.5.0] — 2026-07-02

### Renamed
- **xpvitality → Sanctuary** (`com.k33bz.sanctuary`, `/sanctuary`, `config/sanctuary.json`).

### Anchors reimagined
- **Sanctuary Crystal replaces beacon + dragon egg**: a textured player head IS the anchor.
  Drops from tier-3+ (Ferocious+) mobs killed by players (3%); place to raise a sanctuary,
  break to recover it. `/sanctuary crystal give` for admins.
- **Upkeep & decay**: fresh crystals carry 24 h; emeralds add 2.5 h, emerald **blocks 24 h
  (1 block = 1 day, ~6.7% more efficient than loose)**, bank cap 1536 h (= one stack of
  blocks). Dry anchors go **dormant** (no safety, claim released, raidable) and only a
  **dragon egg** (+7 days) rekindles them. Decay runs on real server time — offline players'
  anchors need banked fuel, and ANY player may refuel.
- **Visual state language**: spinning shell = alive, still = dormant, campfire smoke = final
  24 h; label "Sanctuary Anchor (fuzzy timer)" in purple (heats to red), gold for eternal.
- **Furnace-style menu** (empty-hand click): fuel input (left = stack, right = one), live
  per-second countdown with owner + UUID, flame gauge = banked fraction.
- **Anchor cap progression**: start at 1; Warden kills of rising tiers raise it (any → 2,
  Feral+ → 3, … Nightmare), max 3 unless admins `/sanctuary cap set`. Min spacing 192 blocks.
- **Flan integration** (soft dep): active anchors auto-carry an admin claim (r16); released on
  dormancy/break. **fabric-permissions-api** bundled: `sanctuary.anchor.create/break/admin`.
- **Spawn suppression**: no natural hostile spawns inside active sanctuaries.
- **Sanctuary revert**: buffed mobs (and their scaled XP) revert to vanilla inside safe zones.

### World & combat
- **Dimension gating**: scaling/anchors Overworld-only (`scalingDimensions`); Nether/End vanilla.
- **Danger rebalance**: `perDayWeight` 0.02→0.0005 (was capping in ~2.5 real days),
  `perBlockWeight` →0 (double-dipped with mob attributes); `/sanctuary danger status|reset`
  re-zeroes the age pressure via a persisted epoch.
- **Enemy-wide scaling**: slimes, phantoms, ghasts now scale (were Monster-gated).
- **Indirect damage scaled**: projectiles/explosions from buffed mobs (arrows, creepers, ghast
  fireballs) now carry the attacker's multiplier; PvP excluded from world-danger scaling.
- **Fuzzy zone edges** (σ=12% per spawn) and an optional `damageCurveExponent` (damage+XP).
- **Hunters** (follow-range up to 3×), **door-breakers** (any difficulty past 1000 blocks,
  distance-scaled chance), **frame smashing** (soft blocks around player-placed doors when the
  path is blocked), **rabid wildlife** (25% of Savage+ animals hunt players).
- **Threat readout**: boundary + login actionbar messages with a personalized 5-skull scale.
- **Soul retention**: death keeps 30% of levels +5%/milestone (cap 80%) — veterans lose
  proportionally less. Vanilla recovery orb unchanged.

### Observability
- **Spawn-source tags** (invisible) on every mob; **kill-metrics ledger** (64-block cells,
  `/sanctuary metrics top`) and **NDJSON kill event log** (per-day files, SQLite-convertible).

## [0.4.0] — 2026-07-01 (in progress)
### Added
- **System 7 — spawn-based wild-mob difficulty.** When a hostile spawns, it's buffed by its distance
  from the nearest sanctuary anchor: health/damage/speed scale up (`mobscaling.*PerBlock`, capped at
  `mobscaling.maxMultiplier`, default 8×). Buffs are **permanent attribute modifiers baked into the mob's
  NBT** at spawn — so a wildlands monster stays strong even if it chases a player into a safe zone.
  Mobs inside a safe zone stay vanilla-strength.
- **Tiered threat names + particles**: buffed mobs get a coloured title by tier
  (Feral → Savage → Ferocious → Nightmare) and emit an escalating particle aura near players
  (smoke → angry → flame → soul-fire). Config: `mobscaling.*` knobs + toggle `mobscaling`.
- Verified live via console: a zombie at 2000 blocks = 76 HP "Savage Zombie", at 8000 = 160 HP (capped)
  "Nightmare Zombie", at spawn = vanilla 20 HP.
- **Sanctuary anchor = beacon + seated dragon egg** — right-click a real vanilla beacon while holding a
  dragon egg to seat the egg inside it and form a safe anchor (protects a 128-block region; wildlands stay
  lethal). The egg shows as a **shrunk `block_display`** floating in the beacon (`anchor.eggScale`/
  `anchor.eggHeight`, live-tunable). Anchor beacons suppress the vanilla beacon UI; breaking the beacon
  pops the egg back out and deactivates. Persists in `config/xpvitality_anchors.json`. Both the
  world-danger mixin and wild-mob spawn scaling use the nearest of config + placed anchors.
  **All vanilla blocks/entities → renders natively on every client (vanilla or modded), no custom content.**
- **Wild-mob XP reward** — deeper mobs drop proportionally more XP (`mobscaling.xpPerBlock`, cap
  `mobscaling.xpMax`, default 20×), via an access-widened `Mob.xpReward` set at spawn. Braving the death
  zone is a high-risk XP goldmine (and XP is the mod's survivability currency).
- **Balance:** linear frontier curve, damage **+1×/1000 blocks**. Split the single cap into
  independent caps — `damageMax` **60×** (deep wildlands one-shot fresh AND geared players),
  `speedMax` **1.4×** (fast but controllable), `healthMax` **12×**. The ≤~8k playable zone stays
  balanced (under the caps); beyond it becomes a true death zone. Verified: a Nightmare mob at
  (40000,40000) = ~172 damage, 1.4× speed, ~20× XP.

### Changed
- **Dropped Polymer.** The anchor was briefly a Polymer custom block/item, but Polymer's vanilla-disguise
  does not apply to clients that run Polymer themselves without this mod (they get raw magenta/missing-
  texture) — common on modpacks. The beacon+dragon-egg approach is bulletproof on all clients.

### Verified (server-side, via console)
- Beacon+egg → `Sanctuary anchor formed`; remove egg → `removed`; JSON updates correctly.
- Mob scaling, split caps, and XP reward all confirmed on summoned mobs at distance.

## [0.3.0] — 2026-06-30
### Added
- **Bonus max-health hearts** at level milestones (default 10, 25, 50, 100, 250, 500, …) — one heart
  each, via a transient `max_health` attribute modifier. Vanilla clients render the extra hearts.
- **Absorption "XP shield"** (yellow hearts): `(level/milestone − 1) × maxHealth`, e.g. level 15 → 50%,
  level 40 → 60%, resetting each milestone. Clamped by `shieldMaxFraction`; topped up each interval.
- **Underwater breath** (System 6): the `OXYGEN_BONUS` attribute scaled by level (`oxygen.perLevel`,
  cap `oxygen.max`) — air lasts ~`(bonus+1)×15s`, so a level-500 player is ~2 hours (effectively can't drown).
- **Shield out-of-combat cooldown**: the shield only refills after you've been clear of damage for
  `max(shield.cooldownMin, shield.cooldownBase − milestones × shield.cooldownPerMilestone)` seconds
  (default 10s, −1s/milestone) — so it no longer regenerates mid-fight.
- New config + `/xpvitality` knobs: `armor.perLevel`, `armor.max`, `hearts.hpPerMilestone`,
  `shield.maxFraction`, `oxygen.perLevel`, `oxygen.max`, `shield.cooldown{Base,PerMilestone,Min}`,
  and toggles `armor`, `hearts`, `shield`, `breath`.

### Changed
- **Damage mitigation is now real vanilla armor** (`armor.perLevel`, capped at `armor.max`), shown as
  armor icons. Replaces the flat "%-per-level" mixin math; the world-danger mixin still scales damage up
  first, so vanilla armor reduces afterward. Removed `mitigation.*` config keys.
- **Faster emergency heal**: `regenHealPerInterval` 1.0 → 2.0 (~1 heart/sec while hurt and holding XP).
- Player tick loop now refreshes attributes/shield every interval regardless of whether regen is enabled,
  and tracks a per-player last-damage time (via the damage mixin) for the shield cooldown.

### Fixed
- **Shield no longer refills mid-combat.** Vanilla deletes the absorption effect the instant it hits 0,
  which the code mistook for a first-time grant and re-applied immediately. Refills are now strictly gated
  by the out-of-combat cooldown; a depleted shield stays gone until you break contact. (Verified live via
  server-console telemetry.)

### Verified
- Compiles and unit-tested against real 26.1.2 jars; all attribute/effect API symbols confirmed via `javap`
  (note: the class is `net.minecraft.resources.Identifier`, not `ResourceLocation`, in un-obfuscated 26.x).
- Runtime-verified on a live 26.1.2 client: hearts, armor icons, shield + cooldown, breath, and the
  full damage cascade (shield → lethal save) all confirmed via server-side entity-data sampling.

## [0.2.1] — 2026-06-30
### Added
- **In-game config commands** (`/xpvitality`, op / level 2+): `list`, `get`, `set`, `toggle`,
  `save`, `reload`, and `anchor list|add|clear`. Changes apply live (the tick handler and damage
  mixin read config fresh each tick/hit); `save` persists to the json, `reload` reads it back.
- All command handlers wrapped so failures log a full stack trace instead of a bare dispatcher message.

### Verified
- Runtime-tested on a real 26.1.2 dedicated server: `set mitigation.max 0.55` applied instantly,
  `toggle danger` disabled the system live, and `save` persisted both to `config/xpvitality.json`.

## [0.2.0] — 2026-06-29
### Added
- **System 4 — world-danger damage scaling**: mob damage scales with world difficulty, world age, and
  distance from configurable safe anchors; out-paces mitigation so high-level players still face rising pressure.
- Folded world scaling into `LivingEntityDamageMixin` ahead of mitigation (no new hook).

### Changed
- Spec bumped to four systems.

### Verified
- Compiles and tests green against real Minecraft 26.1.2 jars (Loom 1.17.13, Loader 0.19.3,
  Fabric API 0.153.0+26.1.2, Java 25). All `VERIFY` placeholders from the original draft resolved against
  the actual un-obfuscated symbols.

### Known / parked for 0.3.0
- Lethal save scales on hit magnitude, not true overkill-beyond-remaining-HP.
- Danger anchors are a manual config list; no village/structure auto-detection yet.

## [0.1.0] — initial design
- System 1 (passive regen), System 2 (level mitigation), System 3 (XP-funded lethal save).
- Server-authoritative; vanilla clients can connect.
