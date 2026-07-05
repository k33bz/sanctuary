# CoE: Graves destroyed player inventories (empty-grave data loss)

**Status:** Fixed in v0.7.1 · **Severity:** Critical (data loss) · **Date:** 2026-07-04

## Summary
`Graves.capture()` cleared the dying player's inventory and created a grave, but never
assigned the captured items to the grave. Every death destroyed the player's entire inventory
and left an empty grave — while telling the player *"Your belongings rest in a grave."* Present
from the moment graves shipped (0.6.0) through 0.7.0.

## Impact
- **Who:** Any player dying on a server running Sanctuary 0.6.0–0.7.0 with a non-empty
  inventory and `keepInventory` off.
- **What:** Total inventory loss on death. The grave, and any later claim of it, returned
  nothing.
- **Where/duration:** gmc101 (private 8-player server), from the 0.6.0 grave deploy (~01:15)
  until mitigation (~20:15) — ~19 hours. Zero players were online at mitigation time.
- **Blast radius limiter:** The Modrinth listing was still under moderator review, so no
  external servers had the affected versions in production.

## Timeline (2026-07-04, local)
- **~01:15** — 0.6.0 (first with graves) deployed to gmc101. Bug live from here.
- **~11:24** — 0.7.0 deployed (same bug, unchanged).
- **~20:00** — Multi-bot faction test (F3, grave robbery) observes a grave with `items=0`
  after a bot died holding 5 golden apples. **Detection.**
- **~20:10** — Root cause confirmed by reading `capture()` verbatim at the 0.7.0 release tag.
- **~20:15** — Mitigation: graves disabled on gmc101 (`/sanctuary toggle graves`), reverting
  deaths to recoverable vanilla drops.
- **~20:25** — One-line fix committed; 0.7.1 built (both branches, CI green).
- **~20:28** — 0.7.1 deployed to gmc101, graves re-enabled; releases cut → Modrinth.

## Root cause — 5 Whys
1. **Why did players lose items?** Their inventory was cleared but the grave was empty.
2. **Why was the grave empty?** The captured items were built into a local list that was never
   assigned to `grave.items` — an orphaned local variable.
3. **Why wasn't it caught before release?** The grave-creation regression test asserted the
   grave was *created* and the store *grew*, but never that *items were preserved*.
4. **Why did the test have that gap?** It verified the feature's structure (a grave appears)
   without asserting its core value (the items survive) — a coverage blind spot.
5. **Why did that gap reach production?** No release gate: the behavioral test suite was not run
   against the release-candidate jar before publishing.

## What went right
- The multi-bot test harness caught it before any known player-reported loss.
- Mitigation → fix → deploy → release took ~15 minutes once detected.
- The bug was a single, verifiable line; the fix is low-risk.

## What went wrong
- A critical data-loss bug shipped and ran in production for ~19 hours.
- The existing regression test gave false confidence (asserted presence, not correctness).
- Releases were cut without an automated behavioral gate.

## Resolution
`grave.items = items;` in `Graves.capture()`, before the grave is stored. Shipped as the 0.7.1
hotfix to both branches and gmc101; published to Modrinth.

## Action items
- [x] **Fix `capture()`** — assign captured items to the grave (v0.7.1).
- [ ] **Proven regression test** — die with known items → assert the grave holds them → claim →
  items returned; demonstrated red on 0.7.0 / green on 0.7.1; added to the standard harness
  suite. *(in progress)*
- [ ] **Release gating** — run the harness behavioral suite against the release-candidate jar
  before `gh release create`; block on red.
- [ ] **Pattern audit** — grep the codebase for other "build a local collection, forget to
  assign it" paths (grave/postbox/anchor stores) that could hide the same class of bug.

## Prevention principle
Every fixed bug earns a permanent regression test that is *proven* to fail on the buggy code and
pass on the fix — not merely added. A test you have never seen fail is not a guard.
