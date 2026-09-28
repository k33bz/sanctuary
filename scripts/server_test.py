#!/usr/bin/env python3
"""Boot a real Fabric server with the freshly built Sanctuary jar and run scripted checks.

This is the CI answer to "does the jar actually load and work", which the JUnit suite (pure
logic, no game) cannot give. It needs no bots and no client: every check goes in through the
server console and reads vanilla's own answer (`execute if block ...` prints "Test passed" or
"Test failed"). Standard library only, so it runs on a stock GitHub runner.

What it covers:
  boot     the server reaches "Done", Sanctuary logs its init line, and no mixin fails to apply
           (mixins apply lazily, so each scenario below also forces its target classes to load)
  repair   System 12: a blast inside a sanctuary drops nothing and is rebuilt; a chest breaks
           as vanilla (contents drop, not rebuilt); a torch waits for its wall; an anchor set to
           OFF journals but never rebuilds; outside every sanctuary nothing changes from vanilla
  crash    0.8.11.2: a door-breaker Drowned loaded in water must not take the server down
  report   /sanctuary heal report runs

Usage: python3 scripts/server_test.py [--jar build/libs/x.jar] [--workdir build/server-test]
Downloads the Fabric server launcher (meta.fabricmc.net), fabric-api and, when a build exists
for this Minecraft version, Flan (Modrinth). Writes a Markdown summary to $GITHUB_STEP_SUMMARY
when set. Exit code 0 = every check passed.
"""
import argparse
import glob
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "k33bz/sanctuary server-test (github actions)"}

# Log lines that mean a mixin (or the mod) failed. defaultRequire=1 makes a missed injection
# throw when its target class loads, so these show up the moment a scenario touches it.
FATAL = re.compile(
    r"Mixin apply failed|InvalidInjectionException|InvalidMixinException|MixinApplyError"
    r"|Critical injection failure|Exception ticking world|Encountered an unexpected exception"
    r"|Unsupported mob type for DoorInteractGoal|Could not execute entrypoint")


# ---------------------------------------------------------------- downloads

def http_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def download(url, dest):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r, \
            open(dest, "wb") as f:
        shutil.copyfileobj(r, f)


def props():
    out = {}
    with open(os.path.join(ROOT, "gradle.properties")) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def modrinth_file(project, mc, want_version=None):
    """Primary file of a Modrinth project's Fabric build for `mc` (exact version if given)."""
    q = urllib.parse.urlencode({"game_versions": json.dumps([mc]), "loaders": json.dumps(["fabric"])})
    versions = http_json(f"https://api.modrinth.com/v2/project/{project}/version?{q}")
    if not versions:
        return None
    pick = next((v for v in versions if v["version_number"] == want_version), None) if want_version else None
    pick = pick or versions[0]
    f = next((x for x in pick["files"] if x.get("primary")), pick["files"][0])
    return pick["version_number"], f["url"], f["filename"]


# ---------------------------------------------------------------- server process

class Server:
    def __init__(self, workdir):
        self.workdir = workdir
        self.lines = queue.Queue()
        self.log = []
        self.fatal = []
        self.proc = None

    def start(self):
        self.proc = subprocess.Popen(
            ["java", "-Xms1G", "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"],
            cwd=self.workdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            self.log.append(line)
            if FATAL.search(line):
                self.fatal.append(line)
            self.lines.put(line)
        self.lines.put(None)  # process ended

    def send(self, cmd):
        self.proc.stdin.write(cmd + "\n")
        self.proc.stdin.flush()

    def wait_for(self, pattern, timeout):
        """Next line matching `pattern` (regex) within `timeout` s, else None."""
        rx = re.compile(pattern)
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.lines.get(timeout=max(0.05, end - time.time()))
            except queue.Empty:
                break
            if line is None:
                return None
            if rx.search(line):
                return line
        return None

    def drain(self):
        while True:
            try:
                self.lines.get_nowait()
            except queue.Empty:
                return

    def test(self, cmd):
        """Run an `execute if ...` command; True = passed, False = failed, None = no answer."""
        self.drain()
        self.send(cmd)
        line = self.wait_for(r"Test (passed|failed)", 10)
        if line is None:
            return None
        return "Test passed" in line

    def poll(self, cmd, want, timeout):
        """Repeat `cmd` until it answers `want` or `timeout` s pass. Returns the last answer."""
        end = time.time() + timeout
        got = None
        while time.time() < end:
            got = self.test(cmd)
            if got == want:
                return got
            time.sleep(1)
        return got

    def alive(self):
        self.drain()
        self.send("list")
        return self.wait_for(r"There are \d+ of a max", 15) is not None

    def stop(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.send("stop")
                self.proc.wait(timeout=60)
            except Exception:
                self.proc.kill()


# ---------------------------------------------------------------- setup

def setup(workdir, jar, p):
    mc = p["minecraft_version"]
    os.makedirs(os.path.join(workdir, "mods"), exist_ok=True)
    os.makedirs(os.path.join(workdir, "config"), exist_ok=True)
    notes = []

    installer = next(i["version"] for i in http_json("https://meta.fabricmc.net/v2/versions/installer") if i["stable"])
    download(f"https://meta.fabricmc.net/v2/versions/loader/{mc}/{p['loader_version']}/{installer}/server/jar",
             os.path.join(workdir, "fabric-server-launch.jar"))
    notes.append(f"Fabric loader {p['loader_version']} (installer {installer}) for Minecraft {mc}")

    # The NEWEST fabric-api for this Minecraft version, not the compile pin: a real server runs
    # a current one, and other mods need it (Flan 1.12.8 for 26.2 demands >= 0.158.0 while the
    # pin was 0.155.2, and Fabric refuses to start at all on an unmet dependency).
    api = modrinth_file("fabric-api", mc)
    if api is None:
        raise SystemExit(f"no fabric-api build on Modrinth for {mc}")
    download(api[1], os.path.join(workdir, "mods", api[2]))
    notes.append(f"fabric-api {api[0]} (newest for {mc}; compiled against {p.get('fabric_api_version')})")

    flan = modrinth_file("flan", mc)
    if flan:
        download(flan[1], os.path.join(workdir, "mods", flan[2]))
        notes.append(f"Flan {flan[0]}")
    else:
        notes.append(f"Flan: no build for {mc}, integration inert (not tested)")

    shutil.copy(jar, os.path.join(workdir, "mods", os.path.basename(jar)))
    notes.append(f"under test: {os.path.basename(jar)}")

    with open(os.path.join(workdir, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(workdir, "server.properties"), "w") as f:
        f.write("\n".join([
            "online-mode=false", "level-type=minecraft\\:flat", "spawn-protection=0",
            "difficulty=hard", "view-distance=4", "simulation-distance=4",
            # Default 60: an empty server stops ticking, and nothing here ever joins.
            "pause-when-empty-seconds=-1",
            "enable-command-block=false", "sync-chunk-writes=false", "server-port=25599", ""]))
    # Two exempt (eternal, repair-for-free) anchors: A at the origin repairs at NORMAL speed,
    # B far away is switched OFF. Seeded before boot the same way a real server persists them.
    anchors = {"defaultRadius": 128.0, "anchors": [
        {"x": 0.5, "z": 0.5, "radius": 128.0, "y": -60, "id": "00000000-0000-4000-8000-00000000000a",
         "expiry": 0, "name": "CI Sanctuary A"},
        {"x": 1000.5, "z": 0.5, "radius": 128.0, "y": -60, "id": "00000000-0000-4000-8000-00000000000b",
         "expiry": 0, "name": "CI Sanctuary B", "repairMode": "OFF"},
    ]}
    with open(os.path.join(workdir, "config", "sanctuary_anchors.json"), "w") as f:
        json.dump(anchors, f, indent=2)
    return notes


# ---------------------------------------------------------------- scenarios

def wall(s, x0, z, block="minecraft:stone_bricks"):
    s.send(f"fill {x0} -60 {z} {x0 + 4} -58 {z} {block}")


def blast(s, x, z):
    s.send(f"summon minecraft:tnt {x} -59 {z} {{fuse:0}}")


def items_near(x, z, item=None):
    sel = f"type=minecraft:item,x={x - 12},y=-70,z={z - 12},dx=24,dy=30,dz=24"
    if item:
        sel += f",nbt={{Item:{{id:\"{item}\"}}}}"
    return f"execute if entity @e[{sel}]"


def run(s, results, has_repair):
    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}", flush=True)

    if not has_repair:
        # A pre-0.8.12 jar (e.g. the 0.8.11.2 hotfix) has no System 12: skip those checks and
        # say so, rather than failing them or quietly passing.
        results.append(("repair checks", None, "skipped: this jar has no System 12 (BaseRepair)"))
        print("[SKIP] repair checks: this jar has no System 12", flush=True)

    s.send("forceload add -316 -16 -284 16")
    if has_repair:
        repair_scenarios(s, check)

    # 6. 0.8.11.2 crash: a tagged door-breaker Drowned loaded in water. Its wild_health modifier
    #    sends onSpawn down the "already baked, re-attach goals" path that used to throw.
    s.send("fill -310 -61 -6 -290 -58 6 minecraft:water")
    time.sleep(1)
    mod = ('{Tags:["sanctuary_door_breaker"],attributes:[{id:"minecraft:max_health",base:20,'
           'modifiers:[{id:"sanctuary:wild_health",amount:1.0,operation:"add_value"}]}]}')
    s.send(f"summon minecraft:drowned -300 -59 0 {mod}")
    s.send(f"summon minecraft:zombie -300 -57 10 {mod}")   # land zombie: goals DO attach
    s.send("summon minecraft:enderman 10 -60 40")            # loads the enderman mixin target
    time.sleep(5)
    check("crash fix: server survives a door-breaker Drowned in water", s.alive())

    # 7. The admin report command (System 12).
    if has_repair:
        s.drain()
        s.send("sanctuary heal report")
        check("report: /sanctuary heal report", s.wait_for(r"Base repair: ON", 10) is not None)


def repair_scenarios(s, check):
    # Keep the test areas loaded without a player, and make repairs quick.
    for area in ("-16 -16 80 32", "1012 -16 1048 32", "288 -16 320 32", "-316 -16 -284 16"):
        s.send(f"forceload add {area}")
    for t, sec in ((0, 1), (1, 3), (2, 5), (3, 6)):
        s.send(f"sanctuary set repair.tier{t}.delaySeconds {sec}")
    s.send("sanctuary set repair.maxPerTick 256")
    s.send("gamerule tnt_explodes true")
    time.sleep(3)
    s.send("kill @e[type=minecraft:item]")

    # 1. Blast inside sanctuary A: nothing drops, the wall comes back.
    wall(s, 20, 20)
    time.sleep(1)
    blast(s, 22, 18)
    time.sleep(2)
    check("repair: blast removed the wall", s.test("execute if block 22 -59 20 minecraft:air"))
    check("repair: blast inside a sanctuary drops no items", s.test(items_near(22, 20)) is False)
    check("repair: wall rebuilt", s.poll("execute if block 22 -59 20 minecraft:stone_bricks", True, 30))

    # 2. A chest is never journaled: it and its contents drop as vanilla, and it stays gone.
    s.send("kill @e[type=minecraft:item]")
    s.send('setblock 42 -60 20 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:5}]}')
    time.sleep(1)
    blast(s, 42, 18)
    time.sleep(2)
    check("repair: chest contents drop as vanilla", s.test(items_near(42, 20, "minecraft:diamond")))
    time.sleep(8)
    check("repair: chest is not rebuilt", s.test("execute if block 42 -60 20 minecraft:chest") is False)

    # 3. A torch waits for the wall it hangs on (tier 0 is due before tier 1) and never drops.
    s.send("kill @e[type=minecraft:item]")
    wall(s, 60, 20)
    time.sleep(1)
    s.send("setblock 62 -59 19 minecraft:wall_torch[facing=north]")
    time.sleep(1)
    blast(s, 62, 17)
    time.sleep(2)
    check("repair: blasted torch drops no item", s.test(items_near(62, 20, "minecraft:torch")) is False)
    check("repair: torch rebuilt on its wall",
          s.poll("execute if block 62 -59 19 minecraft:wall_torch", True, 30))

    # 4. Sanctuary B has repairs OFF: journaled (no drops) but never rebuilt. x=1030 keeps the
    #    wall outside B's 16-block Flan claim (Flan would block the blast outright on 26.1).
    s.send("kill @e[type=minecraft:item]")
    wall(s, 1028, 20)
    time.sleep(1)
    blast(s, 1030, 18)
    time.sleep(2)
    check("repair OFF: no drops", s.test(items_near(1030, 20)) is False)
    time.sleep(10)
    check("repair OFF: nothing rebuilt", s.test("execute if block 1030 -59 20 minecraft:air"))

    # 5. Outside every sanctuary nothing changes from vanilla: drops, no rebuild.
    s.send("kill @e[type=minecraft:item]")
    wall(s, 300, 20)
    time.sleep(1)
    blast(s, 302, 18)
    time.sleep(2)
    check("outside: vanilla drops", s.test(items_near(302, 20)))
    time.sleep(10)
    check("outside: not rebuilt", s.test("execute if block 302 -59 20 minecraft:air"))



def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar")
    ap.add_argument("--workdir", default=os.path.join(ROOT, "build", "server-test"))
    ap.add_argument("--boot-timeout", type=int, default=600)
    a = ap.parse_args()
    jar = a.jar or next((j for j in sorted(glob.glob(os.path.join(ROOT, "build", "libs", "*.jar")))
                         if not j.endswith(("-sources.jar", "-dev.jar"))), None)
    if not jar:
        raise SystemExit("no jar: run ./gradlew build first or pass --jar")
    shutil.rmtree(a.workdir, ignore_errors=True)
    p = props()
    notes = setup(a.workdir, jar, p)
    for n in notes:
        print("  " + n, flush=True)

    with zipfile.ZipFile(jar) as z:
        has_repair = "com/k33bz/sanctuary/anchor/BaseRepair.class" in z.namelist()
    notes.append("System 12 base repair: " + ("present, tested" if has_repair else "not in this jar, skipped"))

    s = Server(a.workdir)
    results = []
    s.start()
    try:
        init = s.wait_for(r"\[sanctuary\] v\S+ initialized", a.boot_timeout)
        results.append(("boot: Sanctuary initialized", init is not None, init or ""))
        done = s.wait_for(r"Done \(\d", a.boot_timeout) if init else None
        results.append(("boot: server reached Done", done is not None, done or ""))
        if done:
            run(s, results, has_repair)
    finally:
        s.stop()
    results.append(("no mixin / tick / entrypoint errors in the log", len(s.fatal) == 0,
                    "; ".join(s.fatal[:3])))

    with open(os.path.join(a.workdir, "console.log"), "w") as f:
        f.write("\n".join(s.log))
    failed = [r for r in results if r[1] is False]
    if failed:
        # The job log is the first place anyone looks; don't make them download an artifact.
        print("---- last 80 console lines ----")
        print("\n".join(s.log[-80:]))
        print("---- end ----", flush=True)
    md = [f"### Server test: Minecraft {p['minecraft_version']}, Sanctuary {p['mod_version']}", ""]
    md += [f"- {n}" for n in notes] + ["", "| Check | Result |", "|---|---|"]
    md += [f"| {n} | {'⏭️ ' + d if ok is None else '✅' if ok else '❌ ' + d.replace('|', '/')[:200]} |"
           for n, ok, d in results]
    ran = [r for r in results if r[1] is not None]
    md += ["", f"**{len(ran) - len(failed)}/{len(ran)} passed**"
           + (f", {len(results) - len(ran)} skipped" if len(ran) < len(results) else "")]
    print("\n".join(md))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write("\n".join(md) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
