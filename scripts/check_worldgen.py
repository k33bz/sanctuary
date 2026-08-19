#!/usr/bin/env python3
"""Worldgen copy freshness check for Sanctuary.

The gathering world (`sanctuary:rssworld`) generates from `sanctuary:vanilla_overworld`, a
namespaced copy of the vanilla overworld worldgen tree under
`src/main/resources/data/sanctuary/worldgen/`.

The copies are NOT redundant. Minecraft seeds every noise field from the *id string* of its
noise parameters (`Noises` -> `PositionalRandomFactory.fromHashOf(identifier)`), so
`sanctuary:cave_entrance` and `minecraft:cave_entrance` are different noise. Pointing rssworld
straight at `minecraft:overworld` would make the gathering world a terrain-identical mirror of
the home overworld at the same world seed. The namespace IS the de-correlation.

The cost of that trick is drift: the copies are frozen at whatever Minecraft version they were
taken from, and a game update that retires a density-function type turns into an unbound-registry
crash at boot (0.8.11.1: 26.2 dropped `minecraft:weird_scaled_sampler`). This script diffs the
copies against the vanilla files inside the Minecraft jar so a game bump surfaces the drift
immediately instead of on the next `runServer`.

  python3 scripts/check_worldgen.py            # report drift, exit 1 if any
  python3 scripts/check_worldgen.py --write     # refresh the drifted copies in place

Comparison is structural: JSON key order is not meaningful to Minecraft, and the copies were
serialised by a tool that ordered keys differently to Mojang's data generator.
"""
import collections
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SANCT = ROOT / "src" / "main" / "resources" / "data" / "sanctuary" / "worldgen"
GRADLE_PROPS = ROOT / "gradle.properties"

# `sanctuary:vanilla_overworld` is a copy of vanilla's `minecraft:overworld` noise settings.
ALIASES = {"noise_settings/vanilla_overworld.json": "noise_settings/overworld.json"}

# Keys whose string value is a density-function or noise-parameter id, i.e. the ids the copies
# re-namespace. Everything else (notably "type", and block/biome ids) stays `minecraft:`.
RENAME_KEYS = {
    "noise", "shift_x", "shift_y", "shift_z",
    "argument", "argument1", "argument2", "input",
    "when_in_range", "when_out_of_range", "fallback", "coordinate", "density",
    "barrier", "fluid_level_floodedness", "fluid_level_spread", "lava",
    "temperature", "vegetation", "continents", "erosion", "depth", "ridges",
    "initial_density_without_jaggedness", "final_density",
    "vein_toggle", "vein_ridged", "vein_gap",
}


def minecraft_version() -> str:
    m = re.search(r"^minecraft_version=(.+)$", GRADLE_PROPS.read_text(encoding="utf-8"), re.M)
    if not m:
        sys.exit("could not read minecraft_version from gradle.properties")
    return m.group(1).strip()


def find_jar(version: str) -> Path:
    """The loom cache holds the un-obfuscated merged jar, which carries vanilla's data/ tree."""
    roots = [Path.home() / ".gradle" / "caches" / "fabric-loom" / version]
    candidates = [p for r in roots if r.is_dir() for p in r.glob("*.jar")
                  if "sources" not in p.name and "javadoc" not in p.name]
    merged = [p for p in candidates if "merged" in p.name]
    if not (merged or candidates):
        sys.exit(f"no Minecraft {version} jar in the loom cache; run ./gradlew build first")
    return (merged or candidates)[0]


def load_vanilla(jar: Path) -> dict:
    prefix = "data/minecraft/worldgen/"
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith(prefix) and name.endswith(".json"):
                out[name[len(prefix):]] = json.loads(z.read(name).decode("utf-8"))
    return out


def namespace(node, key=None):
    if isinstance(node, dict):
        return collections.OrderedDict((k, namespace(v, k)) for k, v in node.items())
    if isinstance(node, list):
        return [namespace(v, key) for v in node]
    if isinstance(node, str) and node.startswith("minecraft:") and key in RENAME_KEYS:
        return "sanctuary:" + node[len("minecraft:"):]
    return node


def canon(node):
    if isinstance(node, dict):
        return {k: canon(v) for k, v in sorted(node.items())}
    if isinstance(node, list):
        return [canon(v) for v in node]
    return node


def type_last(node):
    """House style for these files: "type" is the last key of every object."""
    if isinstance(node, dict):
        items = [(k, type_last(v)) for k, v in node.items() if k != "type"]
        if "type" in node:
            items.append(("type", type_last(node["type"])))
        return collections.OrderedDict(items)
    if isinstance(node, list):
        return [type_last(v) for v in node]
    return node


def merge(van, disk):
    """Vanilla supplies the content; the existing file supplies the key order.

    Keeping the on-disk ordering wherever content is unchanged holds the refresh diff down to
    the subtrees Mojang actually altered, which is the difference between a reviewable change
    and a 600-line reformat.
    """
    if isinstance(van, dict) and isinstance(disk, dict):
        kids = [k for k in van if k != "type"]
        # a new single-child wrapper (flat_cache, interpolated, ...) around an otherwise
        # unchanged subtree: recurse through it so the inner ordering survives
        if len(kids) == 1 and isinstance(van[kids[0]], dict) and not set(van) & (set(disk) - {"type"}):
            inner = [(kids[0], merge(van[kids[0]], disk))]
            if "type" in van:
                inner.append(("type", van["type"]))
            return collections.OrderedDict(inner)
        order = [k for k in disk if k in van] + [k for k in van if k not in disk]
        order = [k for k in order if k != "type"] + (["type"] if "type" in van else [])
        return collections.OrderedDict((k, merge(van[k], disk.get(k))) for k in order)
    if isinstance(van, list) and isinstance(disk, list):
        if len(van) == len(disk):
            return [merge(v, d) for v, d in zip(van, disk)]
        pool = list(disk)
        out = []
        for v in van:                       # Mojang inserted/removed an entry: pair up the
            match = next((d for d in pool if canon(d) == canon(v)), None)   # unchanged ones
            if match is not None:
                pool.remove(match)
            out.append(merge(v, match) if match is not None else type_last(v))
        return out
    if isinstance(van, (dict, list)):
        return type_last(van)
    return van


def dangling_refs() -> list:
    """Every `sanctuary:` id the tree references must resolve to a file in the tree.

    Refreshing a copy can pull in a reference to a noise Mojang added in the new version, which
    has no `sanctuary:` counterpart yet. That is not a boot failure: it throws later, per chunk,
    as `Missing element ResourceKey[...]` while generating (0.8.11.1: `sulfur_cave_gradient`).
    """
    refs = {}

    def walk(node, key=None):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, key)
        elif isinstance(node, str) and node.startswith("sanctuary:"):
            refs.setdefault(node[len("sanctuary:"):], key)

    for path in SANCT.rglob("*.json"):
        walk(json.loads(path.read_text(encoding="utf-8")))

    have = {p.relative_to(SANCT / kind).with_suffix("").as_posix()
            for kind in ("noise", "density_function")
            for p in (SANCT / kind).rglob("*.json")}
    return sorted((rid, refs[rid]) for rid in set(refs) - have)


def main(argv):
    write = "--write" in argv
    version = minecraft_version()
    jar = find_jar(version)
    vanilla = load_vanilla(jar)
    print(f"checking sanctuary worldgen copies against Minecraft {version} ({jar.name})")

    drifted, orphaned, checked = [], [], 0
    for path in sorted(SANCT.rglob("*.json")):
        rel = path.relative_to(SANCT).as_posix()
        van = vanilla.get(ALIASES.get(rel, rel))
        if van is None:
            orphaned.append(rel)
            continue
        checked += 1
        disk = json.loads(path.read_text(encoding="utf-8"))
        if canon(namespace(van)) != canon(disk):
            drifted.append((rel, path, van, disk))

    for rel in orphaned:
        print(f"  ?  {rel}: no vanilla counterpart, not checked")

    if not drifted:
        dangling = dangling_refs()
        for rid, key in dangling:
            print(f"  DANGLING  sanctuary:{rid} (referenced as \"{key}\") has no file in the tree; "
                  f"copy data/minecraft/worldgen/*/{rid}.json out of the jar")
        if dangling:
            return 1
        print(f"  ok: all {checked} copies match vanilla {version}, no dangling references")
        return 0

    for rel, path, van, disk in drifted:
        print(f"  STALE  {rel}")
        if write:
            eol = "\r\n" if b"\r\n" in path.read_bytes() else "\n"
            text = json.dumps(merge(namespace(van), disk), indent=2, ensure_ascii=False)
            with open(path, "w", encoding="utf-8", newline=eol) as f:
                f.write(text)     # these files carry no trailing newline
            print(f"         refreshed from {jar.name}")

    if write:
        print(f"\nrefreshed {len(drifted)} file(s); re-run without --write to confirm")
        return 0
    print(f"\n{len(drifted)} copy(ies) drifted from vanilla {version}. "
          f"Refresh with: python3 scripts/check_worldgen.py --write")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
