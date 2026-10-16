#!/usr/bin/env python3
"""Serial ABBA comparison of the interval model and its compiled predecessor.

Run testClasses, then use test-classpath.init.gradle to write a test runtime
classpath. Compiles the three old classes from Git into an isolated overlay;
production sources are never rewritten. CSV/log files remain in --output.
"""
import argparse
import csv
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline-ref", default="35ffe46f994dd2b0c922aa4bba9dd3d63f98516a")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cp = args.classpath_file.read_text().strip()
    package = "src/main/java/com/moakiee/thunderbolt/core/crafting/planner/"
    names = ["MultiRouteBatchOptimizer", "PairBatchAllocation", "TwoRouteBatchOptimizer"]
    allowed = {package + n + ".java" for n in names + ["IntegerInterval"]}
    changed = subprocess.check_output(["git", "diff", "--name-only", args.baseline_ref, "--", "src/main"],
                                      cwd=root, text=True).splitlines()
    if set(changed) - allowed:
        raise RuntimeError("Unrelated production differences require a complete matching baseline build")
    sources = []
    for name in names:
        path = output / (name + ".java")
        path.write_bytes(subprocess.check_output(["git", "show", args.baseline_ref + ":" + package + name + ".java"], cwd=root))
        sources.append(str(path))
    old_classes, harness = output / "baseline-classes", output / "harness-classes"
    subprocess.run(["javac", "-proc:none", "-cp", cp, "-d", str(old_classes), *sources], check=True)
    subprocess.run(["javac", "-proc:none", "-cp", cp, "-d", str(harness),
                    str(Path(__file__).with_name("BatchSearchAudit.java")),
                    str(Path(__file__).with_name("PlannerAblation.java"))], check=True)
    summary = {"baseline": args.baseline_ref, "sourceSha256": {
        name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sorted(allowed)}, "runs": []}
    for workload, main_class in [("batch", "BatchSearchAudit"), ("two", "BatchSearchAudit"), ("public", "PlannerAblation")]:
        baseline = None
        for index, variant in enumerate(["baseline", "candidate", "candidate", "baseline"]):
            prefixes = [str(harness)] + ([str(old_classes)] if variant == "baseline" else [])
            csv_path = output / f"{workload}-{index}-{variant}.csv"
            with csv_path.open("w") as out, csv_path.with_suffix(".log").open("w") as err:
                subprocess.run(["java", "-Xms256m", "-Xmx1g", "-Daudit.twoRoutes=" + str(workload == "two").lower(), "-cp", os.pathsep.join(prefixes + [cp]),
                                "com.moakiee.thunderbolt.core.crafting.planner." + main_class], stdout=out, stderr=err, check=True)
            rows = list(csv.DictReader(csv_path.open()))
            if baseline is None:
                baseline = {row["case"]: row for row in rows}
            fields = ["status", "executions", "stock", "counts"] if workload != "public" else ["feasible", "executions", "stock", "missing", "budget"]
            changed_cases = [r["case"] for r in rows if any(r[k] != baseline[r["case"]][k] for k in fields)]
            def regressed(row):
                old = baseline[row["case"]]
                if int(row["executions"]) > int(old["executions"]):
                    return True
                if workload == "public":
                    return old["feasible"] == "true" and row["feasible"] != "true"
                # Completed searches retain the same optimum and stable tie choice. A partial
                # result may improve, but may not trade equal execution count for more stock.
                if old["status"] == "COMPLETE":
                    return any(row[k] != old[k] for k in fields)
                stock = lambda r: sum(map(int, re.findall(r"=([0-9]+)", r["stock"])))
                return row["executions"] == old["executions"] and stock(row) > stock(old)
            worse = [r["case"] for r in rows if regressed(r)]
            invalid = [r["case"] for r in rows if r.get("balance", "pass").startswith("invalid") or r.get("replay", "pass").startswith("invalid")]
            entry = dict(workload=workload, variant=variant, rows=len(rows),
                         nanos=sum(int(r["ns"]) for r in rows), allocatedBytes=sum(int(r["allocated"]) for r in rows),
                         changedCases=changed_cases, regressions=worse, invalid=invalid)
            if workload != "public":
                entry.update(work=sum(int(r["work"]) for r in rows), complete=sum(r["status"] == "COMPLETE" for r in rows))
            summary["runs"].append(entry)
            (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
            print({k: len(v) if isinstance(v, list) else v for k, v in entry.items()}, flush=True)
            if worse or invalid:
                raise RuntimeError("Correctness/objective regression: inspect CSV before accepting this candidate")


if __name__ == "__main__":
    main()
