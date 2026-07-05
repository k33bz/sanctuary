# Changelog

All notable changes to Sanctuary (formerly XP Vitality).

## [0.8.2.1] — 2026-07-05

### Fixed — the nature-reclaims flora was applied to WILD graves too (should be graveyard-only)
The 0.8.2 podzol → grass → flower aging ran for every grave, including wild ones at the death site,
replacing whatever block was already underneath (k33bz saw podzol appear under wild graves on
gmc101). Flora is now **graveyard-only**: a grave with `inGraveyard == false` gets no
podzol/grass/flower and no `floraStage` — its headstone renders on the untouched original ground.
The gate is `GraveFlora.appliesTo(inGraveyard)` (unit-tested, red-proven).

- Graves record the plot's **`originalGround`** block (optional codec) before the first flora
  replacement, so it can be restored. `Graves.restoreGround` puts it back and clears the flower when
  a graveyard grave is looted, evicted, relocated (resize re-layout), or force-cleared.
- **Migration on `SERVER_STARTED`** (`migrateWildFlora`): for any wild grave (`inGraveyard == false`)
  with a `floraStage`, clear the stage and restore the ground — `originalGround` if recorded, else a
  best-effort restore by sampling the most common solid non-podzol/grass surface among the plot's 8
  horizontal neighbours; if nothing sensible, the flora is left and the coord logged for manual fix.
  This reverts the known gmc101 wild-podzol at (16.5, 2.5) and (8.5, -10.5) on the first fixed boot.

New Grave field `originalGround` (optional, legacy default null). mod_version 0.8.2 → 0.8.2.1.

## [0.8.2] — 2026-07-05

### Graveyard visual + protection overhaul

**(a) Age-fuzzy headstone epitaph.** Line 1 is now just the player NAME (dropped "Here lies").
Line 2 is an epitaph that blurs BOTH cause and time as the grave ages. At death, `capture()` records
the cause category (named-mob killer, fall, fire/lava burn, drown, wither, void, or generic) from
the `DamageSource` plus the in-game death day. Rendered by REAL age (config-tunable):
`< epitaphExactDays` (7) exact — "Slain by a skeleton · Day 16"; `< epitaphVagueDays` (28) — "Slain
by a skeleton, some weeks past"; `< epitaphGenericDays` (90) cause collapses — "Fell in the wilds,
long ago"; beyond — "Lost to time". The sweep re-renders as the tier changes.

**(b) Nature reclaims the plot.** The ground under each grave ages podzol → grass → grass+flower
(`graveFloraGrassDays` 3, `graveFloraFlowerDays` 7). Flora is weighted — common lily-of-the-valley /
oxeye daisy / white tulip, rare wither rose (`graveWitherRoseChance` 0.05, keeps its real wither
hazard). Driven by the grave sweep, save-on-mutation.

**(c) Grave protection.** `PlayerBlockBreakEvents.BEFORE` now cancels breaking a grave's plot
ground (podzol/grass) and the grave block for EVERYONE incl. the owner (Flan only stopped
non-owners — the reason the gravel under a grave could be dug out). Applies to graveyard and wild
markers. The FLORA on top stays harvestable by anyone. The fence is left editable (needed to
resize); a clean seam remains for the 0.9.0 night-only dig-up exception.

**(d) Resizable graveyard.** Re-running the effigy ritual as the SAME owner now RESIZES the yard
if the new flood-filled bounds are strictly larger and still contain every resting grave: the yard
bounds/center/radius update and its graves are re-laid into fresh plots. A smaller pen, or one that
would strand a grave, is rejected. Still one yard per sanctuary.

**(e) Admin force-clear.** `/sanctuarygrave clearworld [includegraveyard]` (op level 2): every
loot-bearing grave's headstone is removed and its inventory moved into the nearest gravekeeper's
hold (`heldByKeeper`, reclaimable via the keeper dialog — nothing lost); empty/looted graves have
their marker + entry deleted. Default = wild graves only; `includegraveyard` also clears in-yard
graves and their plot ground. Refuses if no graveyard exists (nowhere to hold loot). Also added a
debug `/sanctuarygrave setage <days> [id]` (op level 2) to drive the epitaph/flora aging in tests.

**(f) Default gravekeeper.** On `SERVER_STARTED`, if no graveyard has been consecrated yet but at
least one sanctuary anchor exists, a stationary keeper (NoAI, per 0.8.1) is raised beside the OLDEST
anchor as a HOLD-ONLY yard (`radius 0`, no physical plots — a pure reclaim/hold hub). So drift,
`clearworld`, and "nearest keeper hold" always have a target even before anyone builds a graveyard;
`clearworld` no longer hits its "no graveyard" path in practice (the guard stays for
`graveDefaultKeeper=false`). Consecrating a real graveyard UPGRADES the default in place — the
keeper moves to the consecrated ground, held graves carry over, and the "already has a graveyard"
check excludes the auto/default yard. Gated by `graveDefaultKeeper` (default true). Yards gain an
`auto` flag (legacy default false). Also added debug `/sanctuarygrave defaultkeeper` (op level 2)
to drive the check without a restart.

The public-grave status no longer HIDES the epitaph — a public grave keeps its fuzzy epitaph and
shows its status via the red label color instead.

Grave records gain `deathCause` / `killerName` / `deathDay` (optional, legacy defaults) plus
rendered-stage markers. New config: `epitaphExactDays`, `epitaphVagueDays`, `epitaphGenericDays`,
`graveFloraGrassDays`, `graveFloraFlowerDays`, `graveWitherRoseChance`, `graveDefaultKeeper`.
mod_version 0.8.1 → 0.8.2.

## [0.8.1] — 2026-07-05

### Fixed — Gravekeeper wandered out of its pen
Ritual-consecrated keepers were spawned with full villager AI (`NoAI:0b`), so they constantly
pathfound toward the fence and looked broken — contradicting the "still cleric" intent. ALL keepers
now spawn stationary (`NoAI:1b`); the `wander` code path is removed. A NoAI villager still fires the
right-click use handler, so the summon dialog opens unchanged. (`GraveyardRitual` now calls the
plain `spawnKeeper`.)

### Fixed — allay courier never flew; grave "returned" with no visible courier
`spawnCourier` command-summoned the allay, then re-found it with
`getEntitiesOfClass(..., m -> m.entityTags().contains(COURIER_TAG))`. That lookup raced (the freshly
command-summoned entity wasn't reliably in the entity index in the same call), so it returned null:
`run.allay` was null → the outbound/inbound lerp was skipped (no fly-away) and `discard()` was
skipped (the allay stood orphaned in the yard) while only the grave-move completed.

NOTE on the root cause: `entityTags()` is NOT the entity-type registry tags — in 26.x it reads the
same `tags` field `addTag()` writes (the command/scoreboard `Tags:[...]` set), so the tag match
itself was correct. The failure was the command-summon-then-search race, not the accessor. Fixed by
spawning the courier through the entity API (`EntityType<Allay>` from the registry →
`create(level, COMMAND)` → `addFreshEntity`) so the animation holds a DIRECT reference — no lookup.
The courier is now also `NoGravity` (won't sink between teleports) plus NoAI/Invulnerable/
PersistenceRequired/Silent/`COURIER_TAG`.

Added `Gravekeeper.sweepOrphanCouriers`, run at courier-run start, at completion, and on
`SERVER_STARTED` — the last reaps couriers already orphaned in gmc101 yards by the buggy build the
first time 0.8.1 boots.

## [0.8.0] — 2026-07-05

### Changed — the Sanctuary Crystal is now a crafting chain, not a placed altar
The old placed-altar ritual (`SanctuaryRitual` — beacon + conduit + dragon egg + 2 sponge +
inventory reagents, fired by a block-place) is **retired**. The crystal is now made at a crafting
table through two **component-aware special recipes** plus a lava temper. The recipes are
`CustomRecipe` subclasses with registered `RecipeSerializer`s (the mechanism vanilla uses for
map-cloning / firework crafting) so `matches()` inspects stack *components* — a vanilla JSON recipe
would accept any player head, which is wrong. Results are computed server-side, so vanilla clients
craft everything unmodified.

- **New items** (textured player heads, profile-identified, anvil-proof — the `WildEssence`
  pattern): **Wild Membrane (Raw)** (`WildMembraneRaw`, fire-resistant via the vanilla
  `minecraft:damage_resistant` / `is_fire` component so it survives lava) and **Wild Membrane**
  (`WildMembrane`).
- **Raw Wild Membrane** (shapeless): 1 Wild Essence + 2 Phantom Membrane + 2 Sponge → Wild
  Membrane (Raw).
- **Lava temper** (server-side, no mixin, no furnace): a Raw Wild Membrane item entity resting in a
  `lava_cauldron` (strictly lava) is tempered after ~4s of bubbling into a Wild Membrane; the lava
  cauldron empties to a plain cauldron (`beaconLavaConsumed`, default true).
- **Sanctuary Crystal** (shapeless, full 3×3): 1 Wild Membrane + 1 Conduit + 1 Dragon Egg +
  3 Bottle o' Enchanting + 1 Ominous Bottle + 1 Rabbit's Foot + 1 Poisonous Potato → the unchanged
  Sanctuary Crystal. (The crystal item, its profile identity, and all anchor behavior are
  untouched.)

### Changed — Wild Essence drops simplified; crystal mob-drop removed
Wild Essence now drops only from **player-attributed** kills of a **Warden** (guaranteed,
`wardenEssenceChance` = 1.0) or the top scaling tier **Nightmare** (tier 4, `nightmareEssenceChance`
= 3%); nothing below Nightmare drops it. The old **Sanctuary Crystal mob-drop** (`crystalDropMinTier`
/ `crystalDropChance`, 3% from Ferocious+ kills) is **removed** — the crystal is craft-only now.

### Added — debug command backends for the bot harness
`/sanctuary membrane give raw|cooked` and `/sanctuary testcook <x> <y> <z>` (force-temper a raw
membrane over a lava cauldron), alongside the existing `/sanctuary crystal give` and
`/sanctuary essence give`.

## [0.7.1] — 2026-07-04

### Fixed — CRITICAL: graves no longer destroy your inventory
`Graves.capture()` cleared the dying player.s inventory and created the grave but never
assigned the captured items to `grave.items` — the items were orphaned and lost, and the grave
came up empty (you were told "Your belongings rest in a grave" but claiming it gave nothing).
Present since graves shipped. One-line fix: the captured items are now stored on the grave.
Deaths preserve your inventory as intended. (Caught by the multi-bot faction test harness.)

### Testing — expanded pure-logic unit coverage (no behavior change)
Extracted the game-independent time/string/int math still trapped in Minecraft-coupled classes
into standalone, testable helpers (same pattern as `SurvivalLogic`), then had the coupled classes
call the helpers. Pure refactor + tests — the jar behaves identically; each extraction is a
call-through verified to return exactly what the inline code did.

- **`grave/GraveLifecycle`** — the grave-timing predicates lifted out of `Graves`: `isPublic`,
  memorial-decay eligibility, and drift eligibility (millis + config-value in, boolean out).
- **`FeralEggNames`** — Feral Egg name/star-lore parsing lifted out of `FeralEgg`: `parseStars`,
  the star-glyph line detector, and the lore-line star counter.
- **`anchor/AnchorCapRules`** — the Warden-tier → anchor-cap progression lifted out of
  `PlayerProgress`: required-tier-for-next-raise and the bounded one-step raise decision.
- **`anchor/AnchorFuel`** — the fuel/upkeep math lifted out of `AnchorUpkeep` and
  `AnchorState.PlacedAnchor`: exempt/active/hours-left, the bank cap, fed-expiry, and accepted-item
  count.
- **`RespawnLedger`** — the remaining death-ledger update rule lifted out of `RespawnChoice`:
  decay (via `SurvivalLogic`) → milestone reset → accumulate the per-death surcharge.

32 new boundary-focused JUnit tests across `GraveLifecycleTest`, `FeralEggNamesTest`,
`AnchorCapRulesTest`, `AnchorFuelTest`, and `RespawnLedgerTest`.

## [0.7.0] — 2026-07-04

### Added — The crafted sanctuary
A **build-it ritual** alternative to lucky Sanctuary Crystal drops, in the mod's established
ritual idiom (structure + trigger block + validity check + consumed components + dramatic
effect + spawned result). It feeds the existing anchor system — the result is a normal Sanctuary
Crystal, so both paths converge.

- **Wild Essence** — a new textured player head (a glowing ender-eye), anvil-proof by profile
  name exactly like the Sanctuary Crystal. A **ritual reagent**, not an anchor. Give with
  `/sanctuary essence give`.
- **Drops.** A **Warden** kill now *guarantees* 1 Wild Essence (pairs with the existing
  Warden→anchor-cap attunement — one fight, both rewards). Other hostiles drop it by a
  tier-scaled chance, tier 2+ (Savage+) only: `wildEssenceChanceSavage` / `Ferocious` /
  `Nightmare` (defaults **0.005 / 0.02 / 0.08**). Toggle `wildEssence`; disable all of it with
  the same toggle.
- **The ritual.** Place a **conduit on a beacon**, a **dragon egg on the conduit**, and **2
  sponges** within 2 blocks — while holding **1 Wild Essence + 2 phantom membranes** in your
  inventory. Placing the conduit *or* the capstone dragon egg fires the check. On success the
  beacon, conduit, dragon egg, and 2 sponges vanish, the reagents are consumed, a flash +
  end-rod + beacon/conduit sound plays, and you receive a **Sanctuary Crystal**. Missing a piece
  shows an actionbar hint and touches nothing.
- **Crafting-only servers.** The drop path stays (`crystalDropChance`); set `crystalDropChance=0`
  to make the ritual the **only** route to new sanctuaries.

New config: `wildEssenceEnabled`, `wildEssenceChanceSavage/Ferocious/Nightmare`
(live-tunable: `essence.chanceSavage` etc.). Docs: ANCHORS.md gains "Crafting a sanctuary";
MECHANICS.md gains Wild Essence, the ritual recipe, and the drop tables.

## [0.6.1] — 2026-07-04

### Added — Dialog input controls (26.x native)
The anchor, respawn, and Gravekeeper dialogs gain **text fields and pickers**, built on the
26.x server-dialog INPUT system (`net.minecraft.server.dialog.input.*`) — values submit into
permission-0 backend commands via `$(key)` template substitution. No client mod.

- **Name your sanctuary.** The anchor menu gains a *Name this sanctuary* button (owner or
  creative only) opening a text input (up to 24 chars). The name shows on the floating anchor
  label — above the fuzzy upkeep timer — and in `/sanctuary anchor list`. Backed by the new
  permission-0 `/sanctuaryrename` command, acting on the nearest owned anchor within 6 blocks.
  Section signs are stripped so names can't inject colour codes.
- **Choose where you wake.** A player who owns **two or more active sanctuaries** gets a
  single-option picker on the respawn dialog to choose which one to wake at (free — it just
  redirects the free respawn); the nearest-to-death is pre-selected as the default. Single-anchor
  players are unaffected. Backed by permission-0 `/sanctuarywake`.
- **Search the Gravekeeper's ledger.** When the keeper holds or can summon many estates, a
  *Search owner* text field + *Filter by owner* button re-opens his dialog filtered to matching
  owner names (case-insensitive substring), with a *Show all* reset. Backed by
  `/sanctuarygrave search`.

Feral-egg wager sliders (a `number_range` use) are **deferred** — see `SPEC.md`: they need a
betting/market mechanic that does not exist yet.

## [0.6.0] — 2026-07-03

### Added — Graves & Graveyards (System 10)
Grave lifecycle events stream to `config/sanctuary_grave_logs/*.ndjson` (created / drifted /
held / claimed / summoned / decayed, with actor + `robbery:true` on non-owner claims), and two
new scoreboard objectives feed the stat boards and external dashboards: `sanct_graves` (made)
and `sanct_robbed` (robbed, credited to the robber). `/sanctuarydanger status|reset` (ops)
finally exists — reset re-zeroes the persisted world-age epoch, which dashboards can watch to
lap leaderboard seasons.
Death seals your inventory into a **grave** at the death site — JSON-backed (despawn/hopper
proof), headstone of block-display stone with your own shrunk head affixed on top, owner-only.
After `grave.driftHours` (24h real time) it **drifts** to the nearest sanctuary graveyard;
after `grave.publicHours` (48h) the label turns red and anyone may rob it. Looted stones crack.
Ops consecrate yards with `/sanctuarygraveyard set [radius]` — grid plots, plus the
**Gravekeeper**: a still cleric whose dialog summons your loot-bearing graves from the wild or
rival cemeteries (for a fee) — delivered by an **allay courier** that flies off, vanishes, and
returns to settle the stone into the next open plot. Claiming from a yard costs a small toll;
the free option is the walk. Plots are **1x3** -- headstone plus two open blocks fronting a
walkable lane, stones shoulder-to-shoulder (display slabs never connect). A **full yard makes
room**: oldest looted memorial cleared first; failing that the oldest unlooted grave enters the
**Gravekeeper's hold** -- off the lawn, loot intact, reclaimable via his menu with fees and the
48h public timer still applying. Overflow abuse is visual, never a broken mechanic.
**Wild** looted memorials crumble to ash after `grave.memorialDecayDays` (14 real days, 0 =
never); cemetery stones never decay — eviction is the yard's only groundskeeper. Unlooted
wild graves stand where they fell (public after 48h) and drift to the nearest graveyard only
after `grave.driftHours` (default 21 days). Loot never decays.
**Survival consecration ritual**: no command needed — inside your own sanctuary, fence a pen
(9x9 up to 81x81, gate allowed), build an iron-golem body and crown it with a skeleton or
wither skeleton skull. The effigy is consumed and the Gravekeeper rises — wandering his
grounds freely, never past the fence. One graveyard per sanctuary, owner-bound.

### Added — Native [AFK] tag
Idle players (no movement or camera turn for `afk.minutes`, default 5) get a gray **[AFK]**
prefix in the tab list and a quiet chat notice; any input clears it. No scoreboard teams
touched, so it stacks cleanly with the bundled *name colors* pack — the reason VT's own afk
display pack couldn't be used. Toggle `afkTag`.

### Added — Mob-griefing refinements
- **Creeper terrain mercy** (`creeperTerrainProtection`, on): creeper blasts hurt entities at
  full strength, but the only blocks that ever break are **player-placed doors and their
  threshold** (`frameRadius`) — same registry as frame smashing. The world never craters;
  your doorstep still breaches. TNT/ghasts/beds/crystals untouched.
- **Endermen clone, don't steal** (`endermanCloneNotSteal`, on): the world keeps its block;
  the enderman carries away a copy (worst case it plants a stray mundane block later).
  Both replace the blunt VT anti-grief packs with siege-aware versions — the VT packs remain
  bundled for servers that want total protection instead.

### Added — Stat boards (holographic leaderboards)
`/sanctuaryboard add <objective> [title]` (ops) mounts a fixed text-display leaderboard facing
you — top 10 of ANY scoreboard objective, refreshed every 5s, persisted across restarts
(`/sanctuaryboard remove` deletes the nearest). Pairs with the bundled VT stat packs: enable
`track_raw_statistics` and any vanilla stat becomes wall art. Sanctuary maintains its own
objectives: `sanct_hatched` (destined chicks hatched), `sanct_gen_best` (deepest bloodline
generation — the server's breeding crown), `sanct_toll` (levels paid to the death toll).

### Added — Respawn choice: your soul settles (System 9)
Death sends you to the **nearest active sanctuary, free** — then a native dialog offers paid
upgrades: **return to bed** (5% of the level you died with) or **resurrect where you fell**
(15% — the corpse-run skip). No sanctuary anywhere? Vanilla's spot stands and only resurrect
is offered. Costs come on top of normal soul retention; the free option never charges, so a
bad streak can't spiral you to zero.

**The death toll**: every death raises your personal surcharge (+25% of the base price,
configurable). It decays with time *played* (default 1% per 10 minutes — the PLAY_TIME stat,
so it can't be waited out offline) and **clears completely when you reach a new XP milestone**.
Config: `respawnChoice` toggle, `respawn.bedCost` / `respawn.backCost` /
`respawn.escalationPerDeath` / `respawn.escalationDecayPer10Min` / `respawn.minCostLevels`.
Ledger persists in `config/sanctuary_deaths.json`. Vanilla clients get the full dialog —
same 26.x server-dialog system as the anchor menu.

### Added — Bundled Vanilla Tweaks (all opt-in)
59 [Vanilla Tweaks](https://vanillatweaks.net/) datapacks + crafting tweaks now ship inside the
jar as built-in datapacks — **all disabled by default**, enabled individually and per world with
vanilla `/datapack enable "sanctuary:vt/<name>"` (list them with `/datapack list available`).
No separate downloads, no extra jars to keep updated.

Datapacks: anti creeper/enderman grief, armor statues, coordinates hud, double shulker shells,
dragon drops, fast leaf decay, more mob heads, multiplayer sleep, player head drops, silence
mobs, xp bottling, track statistics + track raw statistics (all vanilla stats as scoreboards). Crafting tweaks: back to blocks, craftable gravel, double slabs, dropper to
dispenser, universal dyeing, unpackable ice.

Notable synergies: **dragon drops** makes the dragon egg renewable (each respawned dragon drops
one), and **xp bottling** lets veterans bottle their life force — both deliberate fits for the
sanctuary economy. Attribution in `credits.txt`; bundled per the Vanilla Tweaks terms.

### Added — Feral Eggs (breed your way to Nightmare poultry)

Feature concept by **Zugzuggin'** — every Feral Egg carries the credit in its lore.

- **Contaminated clutches**: an egg appearing within a block of a living feral (rabid) hen
  becomes a **Feral Egg** at her tier — still a vanilla egg, always named plainly "Feral Egg";
  the tier lives in the name's *color* (Savage gold, Ferocious red, Nightmare dark red), which
  an anvil cannot forge. Grades stack separately and trade cleanly.
- **Star quality = generations**: every Feral Egg carries 0–5 stars as a gold line atop its
  tooltip — the bloodline's depth. A wild-turned hen lays 0★; each destined generation lays one star deeper
  (cap 5★). The hen herself never shows stars — the lineage hides in her blood (an invisible
  tag) and only reveals itself on her eggs.
- **The hatch gamble** (odds by stars — normal chick / tier down / same / tier up):
  0★ 90/–/9/1 · 1★ 75/5/19/1 · 2★ 66/8/24/2 · 3★ 50/10/35/5 · 4★ 33/10/47/10 ·
  5★ 25/10/45/20, clamped Savage..Nightmare. Most eggs hatch ordinary chickens — five lucky
  generations stand between a wild Savage hen and true 5★ Nightmare stock.
- **The turn**: a destined bird goes feral the moment it loads as an adult — anywhere, no zone
  or chance roll (the gamble already happened). Sanctuaries still pacify it while it stands
  inside (the revert holds); the destiny survives, and the wilds wake it again. Turned animals all read **Feral <name>** — color alone tells the tier. Arena fights,
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
