#!/usr/bin/env python3
"""Compare the public planner with one optional stage removed at a time.

Run after ./gradlew testClasses. The supplied classpath must include main/test
classes and their runtime dependencies. All altered optimizer classes stay in
the output directory; the production sources have no ablation switches.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline-ref", default="4b59ae736f1c0a13a007b57c7b6ad87ca8e0d210")
    parser.add_argument("--variants", nargs="+", default=[
        "baseline", "mixSmallBatches", "mixTerminalBatches", "mixTerminalFixedDepthBatches",
        "probeAvailableAlternatives", "probePolicy", "ordinaryAdditional", "upstreamAdditional",
        "graft", "chains", "region", "candidate"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = "src/main/java/com/moakiee/thunderbolt/core/crafting/planner/FeasibleConsumptionOptimizer.java"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cp = args.classpath_file.read_text().strip()
    # This experiment isolates one class; do not silently compare different planners.
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", args.baseline_ref, "--", "src/main"], cwd=root, text=True).splitlines()
    if any(path != source for path in changed):
        raise RuntimeError("Other production sources differ from the baseline; build a matching baseline classpath")
    baseline = subprocess.check_output(["git", "show", f"{args.baseline_ref}:{source}"], cwd=root, text=True)

    def replace_once(old, new):
        nonlocal baseline
        if baseline.count(old) != 1:
            raise RuntimeError(f"Baseline instrumentation anchor changed: {old}")
        baseline = baseline.replace(old, new)

    for method in ["mixSmallBatches", "mixTerminalBatches", "mixTerminalFixedDepthBatches",
                   "probeAvailableAlternatives", "probePolicy"]:
        anchor = f"private void {method}() {{"
        replace_once(anchor, anchor + f'\n            if (Boolean.getBoolean("ablation.{method}")) return;')
    for method, owner in [("ordinaryAdditional", "ordinary"), ("upstreamAdditional", "upstream")]:
        anchor = f"candidate = {owner}.tryAdditional();"
        replace_once(anchor, '{ if (!Boolean.getBoolean("ablation.' + method + '")) ' + anchor + ' }')
    for stage, anchor in [
        ("graft", "if (!improved && rerouted != null) improved = graft"),
        ("chains", "if (!improved && version == regionVersion && rerouted != null"),
        ("region", "if (!extendRegion && regionExtensible)")]:
        replace_once(anchor, 'if (!Boolean.getBoolean("ablation.' + stage + '") && ' + anchor[4:])
    classes = output / "ablation-classes"
    harness_classes = output / "harness-classes"
    java = output / "FeasibleConsumptionOptimizer.java"
    java.write_text(baseline)
    harness = Path(__file__).with_name("PlannerAblation.java")
    subprocess.run(["javac", "-proc:none", "-cp", cp, "-d", str(classes), str(java)], check=True)
    subprocess.run(["javac", "-proc:none", "-cp", cp, "-d", str(harness_classes), str(harness)], check=True)
    import os
    rows_by_variant = {}
    summary = {"baseline": args.baseline_ref, "candidateSourceSha256": hashlib.sha256((root / source).read_bytes()).hexdigest(), "variants": {}}
    for variant in args.variants:
        prefixes = [str(harness_classes)] + ([] if variant == "candidate" else [str(classes)])
        command = ["java", "-Xms256m", "-Xmx1g", f"-Dablation.{variant}=true", "-cp",
                   os.pathsep.join(prefixes + [cp]), "com.moakiee.thunderbolt.core.crafting.planner.PlannerAblation"]
        started = time.time()
        with (output / f"{variant}.csv").open("w") as out, (output / f"{variant}.log").open("w") as err:
            subprocess.run(command, stdout=out, stderr=err, check=True)
        with (output / f"{variant}.csv").open() as file:
            rows = list(csv.DictReader(file))
        rows_by_variant[variant] = rows
        base = {row["case"]: row for row in rows_by_variant.get("baseline", rows)}
        fields = ["feasible", "executions", "stock", "missing", "budget"]
        worse = [row["case"] for row in rows if base[row["case"]]["feasible"] == "true" and
                 (row["feasible"] != "true" or int(row["executions"]) > int(base[row["case"]]["executions"]))]
        invalid = [row["case"] for row in rows if row["balance"].startswith("invalid") or row["replay"].startswith("invalid")]
        entry = dict(cases=len(rows), changed=[row["case"] for row in rows if any(row[key] != base[row["case"]][key] for key in fields)],
                     moreExecutionsOrInfeasible=worse, invalid=invalid,
                     measuredNanos=sum(int(row["ns"]) for row in rows),
                     allocatedBytes=sum(int(row["allocated"]) for row in rows), elapsedSeconds=time.time()-started)
        summary["variants"][variant] = entry
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(variant, {key: len(value) if isinstance(value, list) else value for key, value in entry.items()}, flush=True)
        if invalid:
            raise RuntimeError(f"Independent replay failed: {invalid[:10]}")


if __name__ == "__main__":
    main()
