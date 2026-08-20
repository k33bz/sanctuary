#!/usr/bin/env python3
"""Dependency freshness check for Sanctuary.

Queries the authoritative sources for each dependency, pinned to the CURRENT Minecraft version
(game updates are deliberate porting work, never auto-bumped):

  - Fabric Loader . meta.fabricmc.net (latest stable)
  - Fabric API .... Modrinth (latest for this game version)
  - sgui .......... maven.nucleoid.xyz metadata (latest for this game version line)
  - permissions-api Maven Central metadata (latest release)
  - Flan .......... Flemmli97's GitLab maven metadata (latest for this game version)

Rewrites gradle.properties / build.gradle in place when newer versions exist and prints a
summary. Exit code 0 always; CI decides what to do with the diff (build it, PR it).

Then reports NEXT-VERSION READINESS: whether the Minecraft line after the current pin can be
built against yet. Opening a new version line is gated on every dependency publishing for it,
and the long pole is sgui (bundled, so compile-time required) rather than Fabric itself, which
tracks snapshots. This only reports, never bumps: moving a game version is deliberate work.
"""
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_PROPS = ROOT / "gradle.properties"
BUILD_GRADLE = ROOT / "build.gradle"


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "sanctuary-dep-check"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def maven_versions(metadata_url: str) -> list[str]:
    root = ET.fromstring(fetch(metadata_url))
    return [v.text for v in root.findall(".//version")]


MOJANG_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"


def line_of(version: str) -> str:
    """"26.3-snapshot-9" and "26.3.1" both belong to the "26.3" line."""
    return ".".join(version.split("-")[0].split(".")[:2])


def line_key(line: str) -> tuple[int, ...]:
    try:
        return tuple(int(p) for p in line.split("."))
    except ValueError:
        return (0,)


def next_line(current_line: str) -> tuple[str, str | None, str | None]:
    """The Minecraft line after current_line, with its newest release and snapshot ids.

    Derived from Mojang's manifest rather than hardcoded, so this keeps working for 26.4 without
    anyone remembering to edit it. Returns ("", None, None) when nothing newer exists.
    """
    data = json.loads(fetch(MOJANG_MANIFEST))
    lines: dict[str, dict[str, str]] = {}
    for v in data["versions"]:
        slot = "release" if v["type"] == "release" else "snapshot"
        # versions[] is newest-first, so the first id seen per line and slot is the newest.
        lines.setdefault(line_of(v["id"]), {}).setdefault(slot, v["id"])
    newer = sorted((ln for ln in lines if line_key(ln) > line_key(current_line)), key=line_key)
    if not newer:
        return ("", None, None)
    nxt = newer[0]
    return (nxt, lines[nxt].get("release"), lines[nxt].get("snapshot"))


def readiness(current_line: str) -> None:
    """Print whether the next Minecraft line is buildable yet, and what is missing if not."""
    nxt, release, snapshot = next_line(current_line)
    if not nxt:
        print("\nNext-version readiness: nothing newer than %s exists yet." % current_line)
        return
    probe_id = release or snapshot
    print("\nNext-version readiness: Minecraft %s (%s)" % (
        nxt, "released as %s" % release if release else "snapshot only, newest %s" % snapshot))

    blocked: list[str] = []

    def report(name: str, found, required: bool = True) -> None:
        print("  %-24s %s" % (name, found or "no build yet"))
        if required and not found:
            blocked.append(name)

    loader = json.loads(fetch("https://meta.fabricmc.net/v2/versions/loader/%s" % probe_id))
    report("fabric-loader", loader[0]["loader"]["version"] if loader else None)

    api = json.loads(fetch(
        "https://api.modrinth.com/v2/project/fabric-api/version"
        "?game_versions=%%5B%%22%s%%22%%5D&loaders=%%5B%%22fabric%%22%%5D" % probe_id))
    report("fabric-api", api[0]["version_number"] if api else None)

    sgui = [v for v in maven_versions("https://maven.nucleoid.xyz/eu/pb4/sgui/maven-metadata.xml")
            if v.endswith("+%s" % nxt)]
    report("sgui", sgui[-1] if sgui else None)

    # Not tied to a game version, so it is ready as soon as it exists at all.
    perms = maven_versions(
        "https://repo1.maven.org/maven2/me/lucko/fabric-permissions-api/maven-metadata.xml")
    report("fabric-permissions-api", perms[-1] if perms else None)

    # Flan is compileOnly and gated at runtime on isModLoaded, so a missing build costs the
    # land-claim integration but does not stop the mod building or running.
    flan = [v for v in maven_versions(
        "https://gitlab.com/api/v4/projects/21830712/packages/maven"
        "/io/github/flemmli97/flan/maven-metadata.xml")
        if v.startswith(nxt) and v.endswith("-fabric")]
    report("flan (optional)", flan[-1] if flan else None, required=False)

    if blocked:
        print("  VERDICT: %s NOT READY, blocked on: %s" % (nxt, ", ".join(blocked)))
    elif not release:
        print("  VERDICT: %s dependencies all present, but it is SNAPSHOT ONLY." % nxt)
        print("           Fine to open a branch, do not put a live server on it.")
    else:
        print("  VERDICT: %s READY. Every dependency has a build and %s is released." % (nxt, release))


def main() -> None:
    props = GRADLE_PROPS.read_text()
    build = BUILD_GRADLE.read_text()
    mc = re.search(r"minecraft_version=(\S+)", props).group(1)
    mc_line = ".".join(mc.split(".")[:2])  # e.g. 26.1
    changes: list[str] = []

    def bump_prop(key: str, new: str) -> None:
        nonlocal props
        cur = re.search(rf"{key}=(\S+)", props).group(1)
        if cur != new:
            props = re.sub(rf"{key}=\S+", f"{key}={new}", props)
            changes.append(f"{key}: {cur} -> {new}")

    def bump_build(pattern: str, new: str, label: str) -> None:
        nonlocal build
        m = re.search(pattern, build)
        if m and m.group(1) != new:
            build = build[: m.start(1)] + new + build[m.end(1):]
            changes.append(f"{label}: {m.group(1)} -> {new}")

    # Fabric Loader — latest stable
    loader = json.loads(fetch("https://meta.fabricmc.net/v2/versions/loader"))
    stable = next(v["version"] for v in loader if v.get("stable"))
    bump_prop("loader_version", stable)

    # Fabric API — latest for this game version (Modrinth)
    api = json.loads(fetch(
        f'https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22{mc}%22%5D&loaders=%5B%22fabric%22%5D'))
    if api:
        bump_prop("fabric_api_version", api[0]["version_number"])

    # sgui — latest on the nucleoid maven for this game-version line
    sgui = [v for v in maven_versions("https://maven.nucleoid.xyz/eu/pb4/sgui/maven-metadata.xml")
            if v.endswith(f"+{mc_line}")]
    if sgui:
        bump_build(r'eu\.pb4:sgui:([^"\']+)', sgui[-1], "sgui")

    # fabric-permissions-api — latest release on Maven Central
    perms = maven_versions(
        "https://repo1.maven.org/maven2/me/lucko/fabric-permissions-api/maven-metadata.xml")
    if perms:
        bump_build(r'me\.lucko:fabric-permissions-api:([^"\']+)', perms[-1], "fabric-permissions-api")

    # Flan — latest fabric build for this exact game version (GitLab maven)
    flan = [v for v in maven_versions(
        "https://gitlab.com/api/v4/projects/21830712/packages/maven/io/github/flemmli97/flan/maven-metadata.xml")
        if v.startswith(f"{mc}-") and v.endswith("-fabric")]
    if flan:
        bump_build(r'io\.github\.flemmli97:flan:([^"\']+)', flan[-1], "flan")

    if changes:
        GRADLE_PROPS.write_text(props)
        BUILD_GRADLE.write_text(build)
        print("Updates found:")
        for c in changes:
            print("  " + c)
    else:
        print("All dependencies current.")

    # Reporting only. A network hiccup here must not fail the weekly dependency bump.
    try:
        readiness(mc_line)
    except Exception as exc:  # noqa: BLE001
        print("\nNext-version readiness: check failed (%s)" % exc)


if __name__ == "__main__":
    sys.exit(main())
