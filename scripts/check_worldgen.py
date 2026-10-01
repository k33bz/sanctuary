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
  python3 scripts/check_worldgen.py --write     # refresh drifted copies, copy missing ones, and
                                                # delete copies nothing reaches any more

What gets copied is the closure of ROOTS: every density function, noise and material rule the
gathering world's settings reach, found by following ids rather than by a fixed file list. A game
version that adds a new layer (26.3: `overworld/final_density`, the `material_rule` registry)
therefore gets `sanctuary:` copies instead of silently falling back to the home world's noise.

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

# The roots of the copied tree, i.e. what data/sanctuary/dimension/rssworld.json points at:
# `sanctuary:vanilla_overworld` (a copy of vanilla's `minecraft:overworld` noise settings) and the
# `sanctuary:overworld` biome-source preset. Everything else is copied because a root reaches it.
ROOTS = {("noise_settings", "overworld"): "noise_settings/vanilla_overworld.json",
         ("multi_noise_biome_source_parameter_list", "overworld"):
             "multi_noise_biome_source_parameter_list/overworld.json"}

# Registries whose entries the copies re-namespace. Minecraft seeds noise from the id string, so
# every density function, noise and material (surface) rule the overworld settings reach must be a
# `sanctuary:` copy, or the gathering world quietly shares the home overworld's terrain or surface.
NAMESPACED = ("density_function", "noise", "material_rule")

# Keys whose string value is never a worldgen id even when it looks like one: object types, blocks
# (26.3 material rules write `"result_state": "minecraft:calcite"`, and calcite, gravel, ice,
# packed_ice and powder_snow are ALSO noise ids), biome tests, the built-in biome-source `preset`,
# and `random_name`, which is a seed string rather than a reference.
NEVER = {"type", "Name", "default_block", "default_fluid", "biome_is", "biome", "biomes",
         "result_state", "preset", "random_name"}

# The only keys through which one material rule references another.
RULE_KEYS = {"material_rule", "sequence", "then_run"}

# Ids per registry in the vanilla jar ({"noise": {"calcite", ...}, ...}); set by main().
VANILLA_IDS = {reg: set() for reg in NAMESPACED}


def registry_for(rid, key):
    """Which namespaced registry `minecraft:<rid>` points into at this key, or None.

    Id-based rather than key-based: 26.3 moved density-function arguments to `left`/`right`,
    added `function`, `surface_level` and `chunk_surface_level`, and put DF ids in `spawn_target`
    map KEYS, none of which a fixed key list knew about.
    """
    if key in NEVER:
        return None
    if key in RULE_KEYS:
        order = ("material_rule",)
    elif key == "noise":
        order = ("noise", "density_function")
    else:
        order = ("density_function", "noise")
    return next((reg for reg in order if rid in VANILLA_IDS[reg]), None)


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


def namespace(node, key=None, refs=None):
    """Re-point every worldgen id at its `sanctuary:` copy; collect (registry, id) into refs."""
    if isinstance(node, dict):
        out = collections.OrderedDict()
        for k, v in node.items():
            nk = k
            if key == "spawn_target" and k.startswith("minecraft:"):
                reg = registry_for(k[len("minecraft:"):], None)
                if reg:
                    nk = "sanctuary:" + k[len("minecraft:"):]
                    if refs is not None:
                        refs.add((reg, k[len("minecraft:"):]))
            out[nk] = namespace(v, k, refs)
        return out
    if isinstance(node, list):
        return [namespace(v, key, refs) for v in node]
    if isinstance(node, str) and node.startswith("minecraft:"):
        rid = node[len("minecraft:"):]
        reg = registry_for(rid, key)
        if reg:
            if refs is not None:
                refs.add((reg, rid))
            return "sanctuary:" + rid
    return node


def closure(vanilla):
    """Every vanilla file the overworld settings reach, as {relative path: namespaced content}.

    Walks from ROOTS through each referenced density function, noise and
    material rule, so a game version that adds a new one (26.3: overworld/final_density,
    preliminary_surface_level, the material_rule registry) gets a copy instead of a dangling id.
    """
    out, todo, seen = {}, list(ROOTS), set()
    while todo:
        reg, rid = todo.pop()
        if (reg, rid) in seen:
            continue
        seen.add((reg, rid))
        rel = f"{reg}/{rid}.json"
        if rel not in vanilla:
            continue
        refs = set()
        content = namespace(vanilla[rel], None, refs)
        out[ROOTS.get((reg, rid), rel)] = content
        todo.extend(refs)
    return out


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
            for kind in NAMESPACED if (SANCT / kind).is_dir()
            for p in (SANCT / kind).rglob("*.json")}
    return sorted((rid, refs[rid]) for rid in set(refs) - have)


def main(argv):
    write = "--write" in argv
    version = minecraft_version()
    jar = find_jar(version)
    vanilla = load_vanilla(jar)
    for reg in NAMESPACED:
        VANILLA_IDS[reg] = {k[len(reg) + 1:-len(".json")] for k in vanilla if k.startswith(reg + "/")}
    print(f"checking sanctuary worldgen copies against Minecraft {version} ({jar.name})")

    wanted = closure(vanilla)
    drifted, missing, orphaned, checked = [], [], [], 0
    for rel, content in sorted(wanted.items()):
        path = SANCT / rel
        if not path.exists():
            missing.append((rel, path, content))
            continue
        checked += 1
        disk = json.loads(path.read_text(encoding="utf-8"))
        if canon(content) != canon(disk):
            drifted.append((rel, path, content, disk))
    for path in sorted(SANCT.rglob("*.json")):
        rel = path.relative_to(SANCT).as_posix()
        if rel not in wanted:
            orphaned.append(rel)

    # Unreachable copies are not harmless: Minecraft still loads every file into its registries,
    # so a leftover that uses a retired density-function type crashes boot (0.8.11.1) even though
    # nothing generates from it. --write deletes them; a check run fails on them.
    for rel in orphaned:
        print(f"  UNREACHABLE  {rel}: nothing in the gathering world uses it"
              + (" (deleted)" if write else "; delete it (--write does)"))
        if write:
            (SANCT / rel).unlink()

    if not drifted and not missing and not orphaned:
        dangling = dangling_refs()
        for rid, key in dangling:
            print(f"  DANGLING  sanctuary:{rid} (referenced as \"{key}\") has no file in the tree; "
                  f"copy data/minecraft/worldgen/*/{rid}.json out of the jar")
        if dangling:
            return 1
        print(f"  ok: all {checked} copies match vanilla {version}, no dangling references")
        return 0

    for rel, path, content in missing:
        print(f"  MISSING  {rel}")
        if write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(type_last(content), indent=2, ensure_ascii=False), encoding="utf-8")
            print(f"         copied from {jar.name}")
    for rel, path, content, disk in drifted:
        print(f"  STALE  {rel}")
        if write:
            eol = "\r\n" if b"\r\n" in path.read_bytes() else "\n"
            text = json.dumps(merge(content, disk), indent=2, ensure_ascii=False)
            with open(path, "w", encoding="utf-8", newline=eol) as f:
                f.write(text)     # these files carry no trailing newline
            print(f"         refreshed from {jar.name}")

    if write:
        print(f"\nrefreshed {len(drifted)}, copied {len(missing)}, deleted {len(orphaned)} file(s); "
              f"re-run without --write to confirm")
        return 0
    print(f"\n{len(drifted)} copy(ies) drifted, {len(missing)} missing and {len(orphaned)} unreachable "
          f"against vanilla {version}. "
          f"Refresh with: python3 scripts/check_worldgen.py --write")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
