#!/usr/bin/env python3
"""Compare a supplied pre-optimization ref with this checkout without modifying either ref."""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--baseline", required=True, help="Pre-optimization commit/ref")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
work = ROOT / "build" / "indexed-cell-benchmark"
work.mkdir(parents=True, exist_ok=True)


def run(command, **kwargs):
    result = subprocess.run(command, cwd=ROOT, text=True, **kwargs)
    if result.returncode:
        raise SystemExit(f"{Path(command[0]).name} failed ({result.returncode}); inspect its output/log.")
    return result


baseline = run(["git", "rev-parse", args.baseline], capture_output=True).stdout.strip()
candidate = run(["git", "rev-parse", "HEAD"], capture_output=True).stdout.strip()
run(["./gradlew", "-q", "-I", "scripts/benchmarks/cell-benchmark.init.gradle",
     f"-PcellBenchmarkDirectory={work}", "writeCellBenchmarkClasspaths"])
classpath = (work / "runtime.txt").read_text()
compile_classpath = (work / "compile.txt").read_text()
java_home = Path(os.environ["JAVA_HOME"])
sources = []
for name in ("ByteTracker", "IndexedStorage", "IndexedStorageCellInventory", "IndexedCellStorageSavedData"):
    relative = f"src/main/java/com/moakiee/thunderbolt/core/storage/cell/{name}.java"
    source = work / "baseline-src" / relative
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(run(["git", "show", f"{baseline}:{relative}"], capture_output=True).stdout)
    sources.append(str(source))
baseline_classes = work / "baseline"
benchmark_classes = work / "harness"
baseline_classes.mkdir(exist_ok=True)
benchmark_classes.mkdir(exist_ok=True)
run([str(java_home / "bin" / "javac"), "-proc:none", "-cp", compile_classpath,
     "-d", str(baseline_classes), *sources])
run([str(java_home / "bin" / "javac"), "-proc:none", "-cp", compile_classpath,
     "-d", str(benchmark_classes), "scripts/benchmarks/IndexedCellBenchmark.java"])
results = []
environments = []
# Reverse order in a second pair to expose warm filesystem / thermal ordering effects.
for round_number, order in enumerate((("baseline", "candidate"), ("candidate", "baseline")), 1):
    for variant in order:
        paths = [str(benchmark_classes)]
        if variant == "baseline":
            paths.append(str(baseline_classes))
        paths.append(classpath)
        log = work / f"{variant}-{round_number}.log"
        with log.open("w") as output:
            run([str(java_home / "bin" / "java"), "-Xms512m", "-Xmx2g", "-cp", os.pathsep.join(paths),
                 "com.moakiee.thunderbolt.core.storage.cell.IndexedCellBenchmark", variant],
                stdout=output, stderr=subprocess.STDOUT)
        for line in log.read_text().splitlines():
            if line.startswith("CELL_ENV "):
                environments.append(line.removeprefix("CELL_ENV "))
            if line.startswith("CELL_BENCH "):
                row = json.loads(line.removeprefix("CELL_BENCH "))
                row["round"] = round_number
                results.append(row)
                print(json.dumps(row), flush=True)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps({
    "baseline": baseline,
    "candidate": candidate,
    "candidate_tracked_diff": run(["git", "diff", "--stat"], capture_output=True).stdout,
    "environment": sorted(set(environments)),
    "warmups": 3,
    "samples_per_jvm": 7,
    "jvms_per_variant": 2,
    "notes": [
        "Two reversed-order JVM pairs; median/min/max per workload per JVM.",
        "Pair metrics include one insertion and one extraction.",
        "world_save_enqueue measures server-thread preparation; disk completion and validation are outside timing.",
        "Allocation counters cover only the calling thread.",
        "Baseline replaces only the four changed storage classes, using the same dependencies and test harness.",
        "This is a microbenchmark, not an in-game TPS measurement."
    ],
    "results": results
}, indent=2) + "\n")
