import re, os, sys, collections

MIXIN_RE = re.compile(r'@Mixin\s*\(([^)]*)\)', re.S)


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
    prio = re.search(r'priority\s*=\s*(\d+)', body)
    methods = set()
    # injectors: method = "..."  or method = { "...", "..." }
    for mm in re.finditer(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', s, re.S):
        for name in re.findall(r'"([^"]+)"', mm.group(1)):
            methods.add(name.split('(')[0])
    # overwrites
    for mm in re.finditer(r'@Overwrite[^\n]*\n\s*(?:public|protected|private)?[^;{]*?\b(\w+)\s*\(', s):
        methods.add(mm.group(1))
    # accessors / invokers
    for mm in re.finditer(r'@(Accessor|Invoker)\s*\(\s*(?:value\s*=\s*)?"([^"]+)"', s):
        methods.add('@' + mm.group(2))
    return targets, (prio.group(1) if prio else '1000'), methods


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


for tf, (tt, tp, tm) in sorted(tb.items()):
    st = simple(tt)
    for gf, (gtt, gp, gm) in sorted(gt.items()):
        if not (st & simple(gtt)):
            continue
        overlap = (tm & gm)
        print("=" * 100)
        print("TARGET %s" % sorted(st & simple(gtt)))
        print("  TB  %-46s prio %-5s methods: %s" % (tf, tp, sorted(tm)))
        print("  GTL %-46s prio %-5s methods: %s" % (gf, gp, sorted(gm)))
        if overlap:
            print("  *** SHARED METHOD NAMES: %s" % sorted(overlap))
