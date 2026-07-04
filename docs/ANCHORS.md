# Sanctuary Anchors — carving out civilization

A sanctuary is a safe circle: no scaling, no natural hostile spawns, buffed intruders revert
to vanilla. The world ships with one at spawn; every other one is player-made.

## How to raise one

1. Slay **Ferocious+** mobs until one drops a **Sanctuary Crystal** (3% base).
2. Place it. That's the anchor — a spinning crystal head protecting r128.
3. Breaking it drops the crystal back.

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

## Integrations

- **Flan**: active anchors auto-carry an admin grief-protection claim; released on dormancy.
- **Permissions**: `sanctuary.anchor.create / .break / .admin` via fabric-permissions-api —
  LuckPerms-ready, safe defaults without it.
- **Renewable dragon eggs**: enable the bundled `dragon drops` VT pack
  ([server guide](SERVER-GUIDE.md)) so every respawned dragon drops one.

Spacing: 192 blocks minimum between anchors. Overworld only by default.
Full tables: [MECHANICS.md](MECHANICS.md).
