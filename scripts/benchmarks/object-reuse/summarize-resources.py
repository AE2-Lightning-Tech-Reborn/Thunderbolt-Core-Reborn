#!/usr/bin/env python3
"""Aggregate three successful RL benchmark JVMs per mode, without mixing old harness results."""
import hashlib
import json
import re
import statistics
import sys
from pathlib import Path

log_dir, output = map(Path, sys.argv[1:])
root = Path(__file__).resolve().parents[3]
pattern = re.compile(r"RL_FACTORY mode=(\w+) scenario=(\w+) ns=([\d.]+) min=([\d.]+) max=([\d.]+) bytes=([\d.]+)")
scenarios = ("parse_shared", "pair_shared", "parse_equal_string", "parse_8192_live", "unique_pairs", "hash_code", "hash_map")
samples, summary, log_hashes = [], {}, {}
for mode in ("BASE", "LEAN", "OURS", "OURS_OFF"):
    for run in range(1, 4):
        path = log_dir / f"{mode.lower()}-{run}.log"
        data = path.read_bytes()
        text = data.decode()
        if "All 1 required tests passed" not in text or "BUILD SUCCESSFUL" not in text:
            raise ValueError(f"No successful completion: {path}")
        rows = pattern.findall(text)
        if len(rows) != 7 or {r[1] for r in rows} != set(scenarios) or {r[0] for r in rows} != {mode}:
            raise ValueError(f"Wrong measurements: {path}")
        log_hashes[path.name] = hashlib.sha256(data).hexdigest()
        for _, scenario, ns, low, high, allocated in rows:
            samples.append(dict(mode=mode, scenario=scenario, run=run, ns=float(ns),
                                min_ns=float(low), max_ns=float(high), bytes=float(allocated)))
    summary[mode] = {}
    for scenario in scenarios:
        rows = [r for r in samples if r["mode"] == mode and r["scenario"] == scenario]
        summary[mode][scenario] = {
            "ns": statistics.median(r["ns"] for r in rows),
            "bytes": statistics.median(r["bytes"] for r in rows),
            "process_medians_ns": [r["ns"] for r in rows],
            "process_bytes": [r["bytes"] for r in rows],
        }
source_paths = [
    "src/main/java/com/moakiee/thunderbolt/core/keys/ResourceConstructionCache.java",
    "src/main/java/com/moakiee/thunderbolt/core/keys/ResourceLocationCanonicalCache.java",
    "src/main/java/com/moakiee/thunderbolt/core/keys/WeakOpenHashMap.java",
    "src/main/java/com/moakiee/thunderbolt/mixin/platform/objects/ResourceLocationReuseMixin.java",
    "src/main/java/com/moakiee/thunderbolt/config/ThunderboltCommonConfig.java",
    "scripts/benchmarks/object-reuse/java/com/moakiee/thunderbolt/comparison/ResourceLocationBenchmark.java",
]
result = dict(
    baseline="3798e5b0300eddb19706cd9352ba0a29f957048b",
    lean="22d4c0930353b4904a75499f47f25d876e26b2ef",
    tb_parent="f23ef2f97ae1c904dd84d2a1bd860cea8279f7a5",
    method="3 sequential JVMs per mode; 6 warmup + 9 measured rounds; median of process medians; 200000 calls (unique_pairs 20000); cold strings generated before timing",
    source_sha256={p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in source_paths},
    log_sha256=log_hashes, summary=summary, samples=samples,
)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
for scenario in scenarios:
    print(scenario, " | ".join(f"{m}: {summary[m][scenario]['ns']:.2f} ns / {summary[m][scenario]['bytes']:.2f} B" for m in summary))
