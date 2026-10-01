import json, subprocess, sys, re
J = sys.argv[1]
def javap(cls):
    r = subprocess.run(["javap", "-p", "-s", "-cp", J, cls], capture_output=True, text=True)
    return r.stdout
cache = {}
def members(cls):
    if cls not in cache:
        out = javap(cls); names = {}
        lines = out.splitlines()
        for i, l in enumerate(lines):
            m = re.search(r'\s([\w$<>]+)\(', l)
            if m and i + 1 < len(lines) and "descriptor:" in lines[i+1]:
                names.setdefault(m.group(1), []).append(lines[i+1].split("descriptor:")[1].strip())
            f = re.search(r'\s([\w$]+);$', l)
            if f and "(" not in l:
                names.setdefault(f.group(1), []).append("field")
        cache[cls] = (out != "", names)
    return cache[cls]
bad = 0
for m in json.load(open(sys.argv[2])):
    for cls in m["classes"]:
        cls = cls.replace("/", ".")
        ok, names = members(cls)
        if not ok:
            print(f"MISSING CLASS {m['mixin']}: {cls}"); bad += 1; continue
        for meth in m["methods"] + (["level"] if m["mixin"] in ("CreeperExplosionMixin","ExplosionRepairMixin") else []):
            name, desc = (meth.split("(", 1)[0], "(" + meth.split("(", 1)[1]) if "(" in meth else (meth, None)
            if name not in names or (desc and desc not in names[name]):
                bad += 1
                print(f"MISSING {m['mixin']}: {cls}.{meth}  (have: {names.get(name)})")
                hint = [k for k in names if any(w in k.lower() for w in re.findall('[a-z]+', name.lower()) if len(w) > 3)]
                print(f"    similar: {hint[:15]}")
    for t in m["targets"]:
        owner, rest = t[1:].split(";", 1)
        name, desc = rest.split("(", 1)
        ok, names = members(owner.replace("/", "."))
        if name not in names or "(" + desc not in names[name]:
            bad += 1; print(f"MISSING INVOKE {m['mixin']}: {t}  (have: {names.get(name)})")
print("mixin targets missing:", bad)
for c in ("net.minecraft.world.level.block.FarmlandBlock",):
    print(javap(c))
