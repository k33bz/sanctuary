#!/usr/bin/env python3
"""Write shields.io "endpoint" badge files: live version badges for the README.

The README can't read gradle.properties, so CI writes one tiny JSON file per badge onto the
orphan `badges` branch on GitHub and shields.io renders them. Two sources:

  build        from build/server-test/versions.json (written by scripts/server_test.py): per
               release line, the mod + Minecraft versions, Fabric loader, fabric-api compiled
               against vs. booted with, the Flan the server test ran, and its pass count.
               Files land in <out>/<branch>/*.json.
  modpack      from check_modpack.py --json: can the gmc101 modpack move to the target Minecraft
               yet? "ready" or "N blockers". File: <out>/modpack-<target>.json.
  deployment   from a GitHub deployment of the given commit (recorded by Forgejo when it deploys
               to gmc101): the mod + Minecraft version live on the server, read from that
               commit's gradle.properties. File: <out>/<environment>.json.

Standard library only. Endpoint schema: https://shields.io/badges/endpoint-badge
"""
import argparse
import json
import os


def badge(path, label, message, color):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"schemaVersion": 1, "label": label, "message": message or "none", "color": color}, f)
        f.write("\n")


def props(path):
    out = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def from_build(versions, out, branch):
    with open(versions) as f:
        v = json.load(f)
    d = os.path.join(out, branch)
    badge(f"{d}/mod.json", f"sanctuary ({branch})", v.get("mod"), "blueviolet")
    badge(f"{d}/minecraft.json", f"minecraft ({branch})", v.get("minecraft"), "brightgreen")
    badge(f"{d}/loader.json", f"fabric loader ({branch})", v.get("loader"), "informational")
    tested, compiled = v.get("fabric_api_tested"), v.get("fabric_api_compiled")
    same = tested and compiled and tested == compiled
    api = tested if same else f"{tested or '?'} (built {compiled or '?'})"
    badge(f"{d}/fabric-api.json", f"fabric-api ({branch})", api, "informational")
    badge(f"{d}/flan.json", f"flan ({branch})", v.get("flan_tested") or "no build",
          "informational" if v.get("flan_tested") else "lightgrey")
    passed, total = v.get("passed", 0), v.get("total", 0)
    badge(f"{d}/server-test.json", f"server test ({branch})", f"{passed}/{total} passed",
          "brightgreen" if total and passed == total else "red")


def from_deployment(properties, out, environment, state):
    p = props(properties)
    version = f"{p.get('mod_version', '?')}+{p.get('minecraft_version', '?')}"
    ok = state == "success"
    badge(f"{out}/{environment}.json", f"live on {environment}",
          version if ok else f"{version} ({state})", "brightgreen" if ok else "red")


def from_modpack(summary, out):
    with open(summary) as f:
        m = json.load(f)
    missing, errors = m.get("missing", []), m.get("errors", [])
    if errors:
        msg, color = f"{len(errors)} lookup(s) failed", "lightgrey"
    elif missing:
        msg, color = f"{len(missing)} blocker{'s' if len(missing) != 1 else ''}", "orange"
    else:
        msg, color = "ready", "brightgreen"
    badge(f"{out}/modpack-{m['target']}.json", f"gmc101 → {m['target']}", msg, color)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--versions", default="build/server-test/versions.json")
    b.add_argument("--branch", required=True)
    b.add_argument("--out", required=True)
    m = sub.add_parser("modpack")
    m.add_argument("--summary", required=True)
    m.add_argument("--out", required=True)
    d = sub.add_parser("deployment")
    d.add_argument("--properties", default="gradle.properties")
    d.add_argument("--environment", required=True)
    d.add_argument("--state", required=True)
    d.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.cmd == "build":
        from_build(a.versions, a.out, a.branch)
    elif a.cmd == "modpack":
        from_modpack(a.summary, a.out)
    else:
        from_deployment(a.properties, a.out, a.environment, a.state)


if __name__ == "__main__":
    main()
