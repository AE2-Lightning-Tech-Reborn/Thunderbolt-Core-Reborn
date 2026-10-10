import re
import sys

from mixin_scan import mixin_sources, named_targets, simple_names, target_classes


def gen_names(s):
    """Generated members use the Java declaration name, not the annotation's target name."""
    names = set()
    for ann in re.finditer(r'@(?:Accessor|Invoker)\s*(?:\([^)]*\))?', s):
        # The declaration ends at the first ';'; inside it the identifier before '(' is the method.
        declaration = s[ann.end():].split(';', 1)[0]
        m = re.search(r'([\w$]+)\s*\(', declaration)
        if m:
            names.add(m.group(1))
    return names


def parse(body, s):
    targets = target_classes(body) + named_targets(body)
    if not targets:
        return None
    imports = {}
    for im in re.findall(r'^\s*import\s+(?!static\s)([A-Za-z0-9_.]+)\s*;', s, re.M):
        imports[im.split('.')[-1]] = im
    # Resolve imports so AE2 and AdvancedAE's equally named classes remain distinct.
    resolved = []
    for t in targets:
        head, _, tail = t.partition('$')
        resolved.append(imports.get(head, head) + ('$' + tail if tail else ''))
    return resolved, gen_names(s)


def collect(root):
    out = {}
    for name, body, source in mixin_sources(root):
        parsed = parse(body, source)
        if parsed:
            out[name] = parsed
    return out


tb = collect(sys.argv[1])
gt = collect(sys.argv[2])


print("Definitive generated-method collisions (same target class, same generated method name):")
found = False
for tf, (tt, tm) in sorted(tb.items()):
    for gf, (gtt, gm) in sorted(gt.items()):
        shared = set(tt) & set(gtt)
        if not shared:
            continue
        coll = tm & gm
        if coll:
            found = True
            print("  %-34s %-40s <-> %-40s  %s"
                  % (sorted(simple_names(shared)), tf, gf, sorted(coll)))
if not found:
    print("  (none)")
