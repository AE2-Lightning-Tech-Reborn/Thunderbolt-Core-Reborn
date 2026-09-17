import re, os, sys

MIXIN_RE = re.compile(r'@Mixin\s*\(([^)]*)\)', re.S)


def gen_names(s):
    """Java method names generated onto the target class by @Accessor/@Invoker.

    The collision-relevant fact is the method the mixin adds to the target class, which is the
    interface method's own name regardless of whether the annotation names the target field/method
    explicitly (`@Accessor("tasks")`) or infers it (`@Invoker(remap = false)`).
    """
    names = set()
    for ann in re.finditer(r'@(?:Accessor|Invoker)\s*(?:\([^)]*\))?', s):
        # The declaration ends at the first ';'; inside it the identifier before '(' is the method.
        declaration = s[ann.end():].split(';', 1)[0]
        m = re.search(r'([\w$]+)\s*\(', declaration)
        if m:
            names.add(m.group(1))
    return names


def parse(path):
    s = open(path, encoding='utf-8', errors='ignore').read()
    m = MIXIN_RE.search(s)
    if not m:
        return None
    body = m.group(1)
    targets = re.findall(r'(?:value\s*=\s*)?\{?\s*([A-Za-z0-9_.$]+)\.class', body)
    targets += re.findall(r'targets\s*=\s*\{?\s*"([^"]+)"', body)
    if not targets:
        return None
    imports = {}
    for im in re.findall(r'^\s*import\s+(?!static\s)([A-Za-z0-9_.]+)\s*;', s, re.M):
        imports[im.split('.')[-1]] = im
    # Same simple name in a different package is not a collision: AdvancedAE ships its own
    # ExecutingCraftingJob/TaskProgress next to AE2's, and Thunderbolt mixes into both.
    resolved = []
    for t in targets:
        head, _, tail = t.partition('$')
        resolved.append(imports.get(head, head) + ('$' + tail if tail else ''))
    return resolved, gen_names(s)


def collect(root):
    out = {}
    for dirpath, _, files in os.walk(root):
        if 'mixin' not in dirpath.replace(os.sep, '/').split('/'):
            continue
        for f in files:
            if f.endswith('.java'):
                r = parse(os.path.join(dirpath, f))
                if r:
                    out[f] = r
    return out


tb = collect(sys.argv[1])
gt = collect(sys.argv[2])


def simple(ts):
    return set(x.split('.')[-1].split('$')[-1] for x in ts)


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
                  % (sorted(simple(shared)), tf, gf, sorted(coll)))
if not found:
    print("  (none)")
