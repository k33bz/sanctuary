# Vitality — XP as life force

Your XP level is your survivability. Every system below reads it live.

| System | Effect | Math |
|---|---|---|
| XP regen | While hurt, XP points drain to heal you | ~1 heart/s (`regen.healPerInterval`, `regen.xpPerHealth`) |
| Level armor | Real vanilla armor points from levels | `+0.25/level`, cap 20 (= 80% reduction at level 80) |
| Milestone hearts | +1 max heart per milestone reached | milestones: 10, 25, 50, 100, 250, 500, 1000, 2500, 5000 |
| XP shield | Absorption hearts that rebuild out of combat | `(level / lastMilestone − 1) × maxHealth`, capped; cooldown shrinks 1s per milestone |
| Breath | Oxygen bonus per level | level 500 ≈ 2h underwater |
| Lethal save | A killing blow costs levels instead of your life | `ceil(damage × 0.5)` levels, min 1 — you survive at ~1 heart |
| Soul retention | Death keeps part of your levels | `30% + 5%/milestone`, cap 80% — level 1000 keeps 650 |

**The loop**: XP is simultaneously your health pool, your armor, your death insurance, and the
currency for [respawn choices](DEATH-AND-GRAVES.md) — so the deep-wilds XP goldmine
([the Wilds](WILDS.md)) is always worth the risk, and dying is a setback, never a reset.

Full formulas and config reference: [MECHANICS.md](MECHANICS.md).
