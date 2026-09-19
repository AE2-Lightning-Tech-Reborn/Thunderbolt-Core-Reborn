#!/usr/bin/env python3
"""Aggregate successful common-harness JVM runs; preserve semantic observations separately."""
import json
import re
import statistics
import sys
from pathlib import Path

log_dir, output = map(Path, sys.argv[1:])
result = {"modes": {}}
for mode in ("BASE", "LEAN", "OURS"):
    groups = {"direct": {}, "mixed": {}}
    observations = []
    for run in range(1, 4):
        for kind in groups:
            path = log_dir / f"{mode}-{kind}-{run}.log"
            text = path.read_text(errors="replace")
            if "All 1 required tests passed" not in text:
                raise ValueError(f"No successful GameTest completion: {path.name}")
            if kind == "direct":
                pattern = rf"DIRECT_FACTORY mode={mode} count=(\d+) ns=([\d.]+).*?bytes=([\d.]+)"
            else:
                pattern = rf"COMPARE_BENCH mode={mode} name=(\S+) ns=([\d.]+) bytes=([\d.]+)"
                observations.append(dict(re.findall(
                    rf"^COMPARE_PROBE mode={mode} name=(\S+) result=(.*)$", text, re.MULTILINE)))
            samples = re.findall(pattern, text)
            if len(samples) < (2 if kind == "direct" else 14):
                raise ValueError(f"Incomplete measurements: {path.name}")
            for name, ns, allocated in samples:
                groups[kind].setdefault(name, []).append({"run": run, "ns": float(ns), "bytes": float(allocated)})
    summary = {}
    for kind, rows in groups.items():
        summary[kind] = {}
        for name, samples in rows.items():
            if len(samples) != 3:
                raise ValueError(f"Need three JVM samples: {mode}/{kind}/{name}")
            times = [s["ns"] for s in samples]
            allocated = [s["bytes"] for s in samples]
            summary[kind][name] = {
                "median_ns": statistics.median(times), "range_ns": [min(times), max(times)],
                "median_bytes": statistics.median(allocated), "range_bytes": [min(allocated), max(allocated)],
                "runs": samples,
            }
    summary["semantic_observations"] = observations
    result["modes"][mode] = summary
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(output)
