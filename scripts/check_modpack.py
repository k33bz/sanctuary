#!/usr/bin/env python3
"""Can the gmc101 modpack move to a newer Minecraft version yet?

Sanctuary building for 26.2 says nothing about whether the SERVER can run 26.2: gmc101 loads about
forty mods, and the upgrade is gated by the slowest one. This asks Modrinth for each mod in
scripts/gmc101-modpack.json and reports what is missing, so the question answers itself on a
schedule instead of by hand.

  python3 scripts/check_modpack.py                       # target = minecraft_version in gradle.properties
  python3 scripts/check_modpack.py --target 26.3
  python3 scripts/check_modpack.py --refresh-from <mods-dir>   # re-capture the installed jar list

Mods k33bz maintains are listed separately: those are porting work in their own repos, not a wait
on a third party, so they never make the verdict say "blocked".

Exit code is 0 when every third-party mod has a build, 1 otherwise, so CI can gate on it.
"""
import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts" / "gmc101-modpack.json"
GRADLE_PROPS = ROOT / "gradle.properties"


def fetch(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "sanctuary-modpack-check"})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read())


def newest_for(slug: str, version: str):
    """(version_number, date) of the newest Fabric build of slug for version, or (None, None)."""
    q = urllib.parse.urlencode({
        "game_versions": json.dumps([version]), "loaders": json.dumps(["fabric"])})
    try:
        versions = fetch("https://api.modrinth.com/v2/project/%s/version?%s" % (slug, q))
    except Exception as exc:  # noqa: BLE001
        return ("ERROR", str(exc)[:38])
    if not versions:
        return (None, None)
    return (versions[0]["version_number"], versions[0]["date_published"][:10])


def refresh(mods_dir: Path) -> None:
    """Re-capture the installed jar list, so drift on the server shows up as a manifest diff."""
    jars = sorted(p.name for p in mods_dir.glob("*.jar"))
    print("Found %d jars in %s" % (len(jars), mods_dir))
    print("Update the slug lists in %s by hand: jar names are not Modrinth slugs, and the mapping\n"
          "is the part worth reviewing rather than guessing." % MANIFEST.name)
    for j in jars:
        print("  " + j)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--target", help="Minecraft version to test (default: gradle.properties)")
    ap.add_argument("--refresh-from", metavar="MODS_DIR",
                    help="print the jar list from a mods directory, to update the manifest")
    args = ap.parse_args()

    if args.refresh_from:
        refresh(Path(args.refresh_from))
        return 0

    target = args.target or re.search(
        r"minecraft_version=(\S+)", GRADLE_PROPS.read_text()).group(1)
    data = json.loads(MANIFEST.read_text())

    print("gmc101 modpack readiness for Minecraft %s" % target)
    print("  (installed set captured %s, server currently on %s)"
          % (data["captured"], data["current_minecraft"]))
    print()

    ready, missing, errors = [], [], []
    for mod in sorted(data["third_party"], key=lambda m: m["name"].lower()):
        ver, date = newest_for(mod["slug"], target)
        if ver == "ERROR":
            errors.append(mod["name"])
            print("  %-32s ?? lookup failed (%s)" % (mod["name"], date))
        elif ver:
            ready.append(mod["name"])
            print("  %-32s ok   %s  (%s)" % (mod["name"], ver, date))
        else:
            missing.append(mod)
            print("  %-32s --   no %s build" % (mod["name"], target))
        time.sleep(0.12)  # stay inside Modrinth's rate limit

    print()
    print("  third-party: %d ready, %d missing%s"
          % (len(ready), len(missing), ", %d lookup failed" % len(errors) if errors else ""))
    print("  self-maintained (port in their own repos): %s"
          % ", ".join(m["name"] for m in data["mine"]))

    if missing:
        print()
        print("BLOCKED on %d third-party mod(s):" % len(missing))
        for mod in missing:
            note = "  (%s)" % mod["note"] if mod.get("note") else ""
            print("  - %-30s modrinth.com/mod/%s%s" % (mod["name"], mod["slug"], note))
        return 1

    if errors:
        print("\nInconclusive: %d lookup(s) failed, rerun before trusting this." % len(errors))
        return 1

    print("\nEvery third-party mod has a %s build. Remaining work is the self-maintained list." % target)
    return 0


if __name__ == "__main__":
    sys.exit(main())
