import re, os, sys

def targets(root):
    out = {}
    for dirpath, _, files in os.walk(root):
        parts = dirpath.replace(os.sep, '/').split('/')
        if 'mixin' not in parts:
            continue
        for f in files:
            if not f.endswith('.java'):
                continue
            p = os.path.join(dirpath, f)
            s = open(p, encoding='utf-8', errors='ignore').read()
            m = re.search(r'@Mixin\s*\(([^)]*)\)', s, re.S)
            if not m:
                continue
            body = m.group(1)
            t = re.findall(r'(?:value\s*=\s*)?\{?\s*([A-Za-z0-9_.$]+)\.class', body)
            prio = re.search(r'priority\s*=\s*(\d+)', body)
            out[f] = (t, prio.group(1) if prio else '1000', p)
    return out

tb = targets(sys.argv[1])
gt = targets(sys.argv[2])

def simple(ts):
    return set(x.split('.')[-1] for x in ts)

tbm = {k: simple(v[0]) for k, v in tb.items()}
gtm = {k: simple(v[0]) for k, v in gt.items()}

print("=== overlapping TARGET CLASSES ===")
seen = set()
for mk, t in sorted(tbm.items()):
    for gk, g in sorted(gtm.items()):
        ov = t & g
        if ov:
            print("%-42s TB: %-46s prio %-5s <-> GTL: %-44s prio %s"
                  % (sorted(ov), mk, tb[mk][1], gk, gt[gk][1]))
print()
print("=== TB mixin targets (all) ===")
for mk, t in sorted(tbm.items()):
    print("%-52s %s  prio %s" % (mk, sorted(t), tb[mk][1]))
