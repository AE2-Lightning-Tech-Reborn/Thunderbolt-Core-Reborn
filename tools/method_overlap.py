import re
import sys

from mixin_scan import mixin_sources, named_targets, priority, simple_names, target_classes


def parse(body, s):
    targets = target_classes(body) + named_targets(body)
    if not targets:
        return None
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
    return targets, priority(body), methods


def collect(root):
    out = {}
    for name, body, source in mixin_sources(root):
        parsed = parse(body, source)
        if parsed:
            out[name] = parsed
    return out


tb = collect(sys.argv[1])
gt = collect(sys.argv[2])


for tf, (tt, tp, tm) in sorted(tb.items()):
    st = simple_names(tt)
    for gf, (gtt, gp, gm) in sorted(gt.items()):
        if not (st & simple_names(gtt)):
            continue
        overlap = (tm & gm)
        print("=" * 100)
        print("TARGET %s" % sorted(st & simple_names(gtt)))
        print("  TB  %-46s prio %-5s methods: %s" % (tf, tp, sorted(tm)))
        print("  GTL %-46s prio %-5s methods: %s" % (gf, gp, sorted(gm)))
        if overlap:
            print("  *** SHARED METHOD NAMES: %s" % sorted(overlap))
