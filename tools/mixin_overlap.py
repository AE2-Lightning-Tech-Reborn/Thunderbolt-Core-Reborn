import sys

from mixin_scan import mixin_sources, priority, target_classes

def targets(root):
    out = {}
    for name, body, _ in mixin_sources(root):
        out[name] = (target_classes(body), priority(body))
    return out

tb = targets(sys.argv[1])
gt = targets(sys.argv[2])

def simple(ts):
    return set(x.split('.')[-1] for x in ts)

tbm = {k: simple(v[0]) for k, v in tb.items()}
gtm = {k: simple(v[0]) for k, v in gt.items()}

print("=== overlapping TARGET CLASSES ===")
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
