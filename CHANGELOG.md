# Changelog

All notable changes to XP Vitality.

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
