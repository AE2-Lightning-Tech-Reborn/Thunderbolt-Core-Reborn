#!/usr/bin/env python3
"""Summarize matched cold-path measurements, retaining all per-process medians and log hashes."""
import hashlib
import json
import re
import statistics
import sys
from pathlib import Path

logs, output = map(Path, sys.argv[1:3])
root = Path(__file__).resolve().parents[3]
scenarios = ("pair_discarded", "pair_retained", "parse_discarded", "parse_retained")
pattern = re.compile(r"RL_COLD mode=(\w+) scenario=(\w+) ns=([\d.]+) min=([\d.]+) max=([\d.]+) bytes=([\d.]+)")
samples, summary, log_hashes = [], {}, {}
for mode in ("BASE", "LEAN", "OURS_BEFORE", "OURS_AFTER"):
    for run in range(1, 4):
        path = logs / f"{mode.lower()}-{run}.log"
        data = path.read_bytes()
        text = data.decode()
        if "All 1 required tests passed" not in text or "BUILD SUCCESSFUL" not in text:
            raise ValueError(f"Run did not succeed: {path}")
        rows = pattern.findall(text)
        if len(rows) != 4 or {r[1] for r in rows} != set(scenarios) or {r[0] for r in rows} != {mode}:
            raise ValueError(f"Incomplete measurements: {path}")
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
paths = [
    "src/main/java/com/moakiee/thunderbolt/core/keys/WeakCanonicalSet.java",
    "src/main/java/com/moakiee/thunderbolt/core/keys/ResourceLocationCanonicalCache.java",
    "src/main/java/com/moakiee/thunderbolt/core/keys/ResourceConstructionCache.java",
    "src/main/java/com/moakiee/thunderbolt/mixin/platform/objects/ResourceLocationReuseMixin.java",
    "scripts/benchmarks/object-reuse/java/com/moakiee/thunderbolt/comparison/ResourceLocationColdBenchmark.java",
]
result = dict(
    baseline="3798e5b0300eddb19706cd9352ba0a29f957048b",
    lean="22d4c0930353b4904a75499f47f25d876e26b2ef",
    method="3 sequential JVMs per mode; 6 warmup + 9 measured rounds; 20000 calls per scenario/round; cold input strings and output arrays created before timing; retained outputs stay live across all rounds",
    before_source_sha256=json.loads((logs / "before-source/sha256.json").read_text()),
    after_source_sha256={p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in paths},
    log_sha256=log_hashes, summary=summary, samples=samples,
)
if len(sys.argv) > 3:
    hot_logs = Path(sys.argv[3])
    hot_samples = {}
    hot_pattern = re.compile(r"RL_FACTORY mode=OURS scenario=(\w+) ns=([\d.]+) min=([\d.]+) max=([\d.]+) bytes=([\d.]+)")
    for run in range(1, 4):
        path = hot_logs / f"ours-{run}.log"
        data = path.read_bytes(); text = data.decode()
        rows = hot_pattern.findall(text)
        if len(rows) != 7 or "All 1 required tests passed" not in text or "BUILD SUCCESSFUL" not in text:
            raise ValueError(f"Invalid original-harness verification: {path}")
        log_hashes[f"original-harness/{path.name}"] = hashlib.sha256(data).hexdigest()
        for scenario, ns, low, high, allocated in rows:
            hot_samples.setdefault(scenario, []).append(dict(run=run, ns=float(ns), min_ns=float(low),
                                                              max_ns=float(high), bytes=float(allocated)))
    result["original_harness_verification"] = {
        scenario: dict(ns=statistics.median(r["ns"] for r in rows),
                       bytes=statistics.median(r["bytes"] for r in rows), samples=rows)
        for scenario, rows in hot_samples.items()
    }
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
for scenario in scenarios:
    print(scenario, " | ".join(f"{m}: {summary[m][scenario]['ns']:.2f} ns / {summary[m][scenario]['bytes']:.0f} B" for m in summary))
