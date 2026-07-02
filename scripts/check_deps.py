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


if __name__ == "__main__":
    sys.exit(main())
