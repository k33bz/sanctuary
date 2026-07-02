# XP Vitality — Design Spec (v0.4.0)

Server-authoritative Fabric mod for Minecraft 26.1.2. XP is a consumable survival resource, and
leveling is made **visible** through vanilla-rendered attributes (hearts, armor icons, absorption).

## System 1 — Passive regen
- **Trigger:** every `regenIntervalTicks` (default 20 = 1s), via `ServerTickEvents.END_SERVER_TICK`.
- **Condition:** player is alive, `health < maxHealth`, and has any XP.
- **Effect:** heal `regenHealPerInterval` (default 1.0 = ½ heart), draining
  `ceil(heal * regenXpPerHealth)` XP points (`giveExperiencePoints(-cost)`).
- **Code:** `XPVitality.tryRegen`, math in `SurvivalLogic.regenXpCost`.

## System 2 — Armor from level (replaces the old % mitigation)
- **Value:** `min(level * armorPerLevel, armorMax)` armor points (default 0.25/level, cap 20).
- **Delivery:** a transient `Attributes.ARMOR` modifier, refreshed each interval by `LevelAttributes`.
  Shows as **armor icons** and reduces damage through vanilla's own armor curve (not a flat %).
- **Math:** `SurvivalLogic.armorForLevel`.

## System 3 — XP-funded lethal save
- **Trigger:** `ServerLivingEntityEvents.ALLOW_DEATH` on a `ServerPlayer`.
- **Cost:** `max(minLevels, ceil(hitDamage * lethalSaveLevelsPerDamage))` whole levels.
- **Effect:** if affordable, spend the levels, set health to `lethalSaveReviveHealth` (~1 heart), cancel death.
- **Code:** `XPVitality.registerLethalSave`, math in `SurvivalLogic.lethalSaveLevelCost`.
- **Parked (later):** cost on true overkill-beyond-remaining-HP (needs pre-hit health in the mixin).

## System 4 — World-danger damage scaling
- **Multiplier:** `difficultyTerm * timeTerm * distanceTerm`, clamped to `[1, maxMultiplier]`:
  - difficulty: `1 + difficultyWeight * difficultyId`
  - time: `1 + perDayWeight * (gameTime / 24000)`
  - distance: `1 + perBlockWeight * blocksBeyondNearestSafeRadius`
- **Scope:** only damage with a living attacker (mobs/players) — environmental damage is exempt.
- **Anchors:** config list of `{x, z, safeRadius}`; default spawn `(0,0)` r=128.
- **Code:** `LivingEntityDamageMixin` + `XPVitalityConfig.blocksBeyondSafe`, math in `SurvivalLogic.worldDangerMultiplier`.
- **Parked (later):** auto-detecting villages/structures as anchors (needs structure lookups).

## System 5 — Leveling made visible (hearts + shield)
- **Bonus hearts:** `milestonesReached(level) * hpPerMilestone` extra max health via a transient
  `Attributes.MAX_HEALTH` modifier. Default milestones `[10, 25, 50, 100, 250, 500, 1000, 2500, 5000]`,
  2.0 HP (one heart) each.
- **XP shield (absorption):** `min((level/lowerMilestone − 1), shieldMaxFraction) * maxHealth`, e.g.
  level 15 → 0.50, level 40 → 0.60; resets to 0 at each milestone. Delivered via the **infinite Absorption
  mob effect** (4-HP steps) — a raw `setAbsorptionAmount` is wiped by vanilla every tick.
- **Out-of-combat cooldown:** the shield only (re)fills after `max(cooldownMin, cooldownBase −
  milestones × cooldownPerMilestone)` seconds without taking damage (default 10s, −1s/milestone). In
  combat it decays and stays gone. Combat is tracked per-player via the damage mixin (`XPVitality.markCombat`).
  NOTE: vanilla removes the absorption effect at 0, so a depleted shield must not be treated as a first
  grant — refills are strictly `!correctAndFull && outOfCombat`.
- **Code:** `LevelAttributes.apply`/`applyShield`, math in `SurvivalLogic.bonusHealth` / `shieldFraction`
  / `shieldAmount` / `absorptionAmplifier` / `shieldRegenCooldownSeconds`.

## System 6 — Underwater breath
- **Value:** `min(level × oxygenPerLevel, oxygenMax)` on the vanilla `OXYGEN_BONUS` attribute (default
  1.0/level, cap 1000). Air lasts ~`(bonus + 1) × 15s`, so level 500 ≈ 2 hours underwater.
- **Delivery:** transient `Attributes.OXYGEN_BONUS` modifier in `LevelAttributes`; vanilla does the
  air-decay math. Math in `SurvivalLogic.oxygenBonusForLevel`.

## Order of operations (the key interaction)
In `LivingEntity.hurtServer(ServerLevel, DamageSource, float)`, a `@ModifyVariable` at HEAD scales
incoming mob damage **up** by the world multiplier; vanilla's armor reduction (from System 2's granted
armor) then applies **afterward** in the same method — "scale up, then reduce". So a dangerous enough
world can still out-pace a well-armored high-level player.

## System 7 — Spawn-based wild-mob difficulty
- **Trigger:** `ServerEntityEvents.ENTITY_LOAD` (in `event.lifecycle.v1`, NOT `entity.event.v1`); filter `instanceof Monster`.
- **Buff:** on first load, if not already baked, scale `MAX_HEALTH`/`ATTACK_DAMAGE`/`MOVEMENT_SPEED` by
  `min(maxMultiplier, 1 + perBlock × blocksBeyondNearestAnchor)` via **permanent** `ADD_MULTIPLIED_BASE`
  modifiers (persist in NBT → stays strong if lured into a safe zone). Idempotent: skip if the health
  modifier already exists. Mobs spawned inside a safe zone (`beyond ≤ 0`) stay vanilla-strength.
- **Telegraph:** tiered custom name (Feral/Savage/Ferocious/Nightmare, coloured) + a particle aura emitted
  each interval for buffed mobs near a player (smoke → angry → flame → soul-fire).
- **Code:** `MobDifficulty` (`onSpawn`, `tickParticles`), math in `SurvivalLogic.mobPowerMultiplier` / `mobTier`.

## Sanctuary anchor (placeable safe region)
- **What:** a custom block/item (crafted from **dragon egg + beacon**) built on **Polymer**
  (`eu.pb4:polymer-core:0.16.5+26.1.2`, bundled via `include`) so vanilla clients render it as a **beacon**
  with no client mod. `AnchorBlock implements PolymerBlock` (→ `Blocks.BEACON` state),
  `AnchorItem extends BlockItem implements PolymerItem` (→ `Items.BEACON`, EPIC, `ITEM_NAME` = "Sanctuary Anchor").
- **Registration:** plain `Registry.register(BuiltInRegistries.BLOCK/ITEM, id, ...)`; `Properties.setId(ResourceKey)`
  required in 26.x. No block entity needed.
- **Lifecycle:** `setPlacedBy` registers `{x,z,radius=128}` into `AnchorState`; `PlayerBlockBreakEvents.AFTER`
  deregisters. Persisted to `config/xpvitality_anchors.json` (plain Gson, not SavedData).
- **Integration:** `XPVitality.blocksBeyondNearestAnchor(cfg,x,z) = min(config anchors, placed anchors)` — used by
  both the world-danger mixin and System 7.
- **Recipe:** vanilla datapack JSON `data/xpvitality/recipe/sanctuary_anchor.json` (shapeless: beacon + dragon egg).
- **NOT yet verified in-game** (needs a client): item placement/`setPlacedBy`, break deregistration, the beacon
  appearance, and crafting. Server-side (registration, recipe load, distance math) is verified.
- **TODO:** wandering-trader trade for dragon eggs (so >1 anchor); `TradeOfferHelper` wasn't present in this
  Fabric API build — revisit.

## Verified 26.1.2 symbols (un-obfuscated names)
- System 7 / anchor: `ServerEntityEvents.ENTITY_LOAD` (lifecycle.v1), `Monster`/`Enemy`, `Attributes.ATTACK_DAMAGE/MOVEMENT_SPEED`,
  `Operation.ADD_MULTIPLIED_BASE`, `addOrReplacePermanentModifier`, `Entity.setCustomName`, `ServerLevel.sendParticles/getEntitiesOfClass`,
  `PolymerBlock.getPolymerBlockState`, `PolymerItem.getPolymerItem`, `PlayerBlockBreakEvents.AFTER`,
  `Item.Properties.component(DataComponents.ITEM_NAME, …)`
- `LivingEntity#hurtServer(ServerLevel, DamageSource, float):boolean`, `getHealth/setHealth/getMaxHealth/isDeadOrDying`,
  `getAbsorptionAmount/setAbsorptionAmount`, `getAttribute(Holder<Attribute>)`
- `Attributes.MAX_HEALTH/ARMOR/OXYGEN_BONUS` (`Holder<Attribute>`); `AttributeModifier(Identifier, double, Operation.ADD_VALUE)`;
  `AttributeInstance.addTransientModifier/removeModifier`; `Identifier.fromNamespaceAndPath` (**not** `ResourceLocation`)
- `MobEffects.ABSORPTION` (`Holder<MobEffect>`); `MobEffectInstance(effect, INFINITE_DURATION, amp, ambient, visible, showIcon)`,
  `isInfiniteDuration/getAmplifier`; `LivingEntity.addEffect/removeEffect/getEffect`; `Entity.level()/getUUID()`
- `Player#experienceLevel/totalExperience/experienceProgress`, `giveExperiencePoints/giveExperienceLevels`
- `MinecraftServer#getPlayerList`, `PlayerList#getPlayers`, `Difficulty#getId`, `DamageSource#getEntity`
- Fabric API: `ServerTickEvents.END_SERVER_TICK`, `ServerLivingEntityEvents.ALLOW_DEATH`,
  `CommandRegistrationCallback`; `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`
