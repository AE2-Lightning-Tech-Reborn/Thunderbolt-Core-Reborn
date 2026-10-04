#!/usr/bin/env python3
"""Run TB v2 against AW's own prepared AWR graph and saved query manifests."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MAX_LONG = (1 << 63) - 1


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def source(path):
    return {"path": str(path), "sha256": sha256(path)}


def tb_source_hash():
    paths = sorted((REPO / "src" / "main" / "java").rglob("*.java"))
    paths += [p for name in ("build.gradle", "gradle.properties", "settings.gradle")
              if (p := REPO / name).is_file()]
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.relative_to(REPO).as_posix().encode("utf-8") + b"\0")
        digest.update(bytes.fromhex(sha256(path)))
    return {"sha256": digest.hexdigest(), "files": len(paths),
            "scope": "src/main/java/**/*.java and Gradle build/property/settings files",
            "encoding": "sorted relative UTF-8 path + NUL + binary file SHA-256"}


def git_state(root):
    def git(*args):
        result = subprocess.run(["git", "-C", str(root), *args], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        return result.stdout.strip() if result.returncode == 0 else None
    return {"commit": git("rev-parse", "HEAD"), "status": git("status", "--porcelain")}


def checked_run(command, **kwargs):
    print("$ " + shlex.join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def load_reader(path):
    # Use the selected checkout's implementation; never vendor or approximate its cleanup.
    spec = importlib.util.spec_from_file_location("tb_awr_benchmark_reader", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"cannot import {path}")
    module = importlib.util.module_from_spec(spec)
    old_path = sys.path[:]
    old_bytecode = sys.dont_write_bytecode
    sys.path.insert(0, str(path.parent))
    sys.dont_write_bytecode = True
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path[:] = old_path
        sys.dont_write_bytecode = old_bytecode
    return module.Graph


def positive_long(value, label):
    if type(value) is not int or not 0 < value <= MAX_LONG:
        raise ValueError(f"{label}: expected a positive signed-long amount, got {value!r}")


def normalize(Graph, awr, meta_path, destination):
    metadata = {}
    for line in meta_path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("\t", 1)
            if key in metadata:
                raise ValueError(f"duplicate manifest key: {key}")
            metadata[key] = value
    digest = sha256(awr)
    if metadata.get("awr_sha256") != digest:
        raise ValueError(f"{awr.name}: manifest awr_sha256 mismatch or missing")
    started = time.perf_counter()
    graph = Graph(str(awr)).prepare()
    counts = graph.counts()
    for key, value in counts.items():
        if key in metadata and int(metadata[key]) != value:
            raise ValueError(f"{awr.name}: manifest {key}={metadata[key]}, prepared={value}")
    if not 0 < graph.n_real <= graph.n_item < (1 << 31):
        raise ValueError("invalid real/item counts")
    if not 0 <= len(graph.recipes) < (1 << 31):
        raise ValueError("invalid recipe count")
    # Validate the entire graph before emitting a file. Tags are an explicit AWR boundary,
    # never inferred from the shape of a real recipe, and never expanded into combinations.
    for rid, rec in enumerate(graph.recipes):
        fields = set(getattr(rec, "__dict__", {})) | set(getattr(type(rec), "__slots__", ()))
        if fields - {"out", "out_amt", "inputs", "ws"}:
            raise ValueError(f"recipe {rid}: unsupported AWR recipe metadata {fields}")
        if type(rec.out) is not int or not 0 <= rec.out < graph.n_item:
            raise ValueError(f"recipe {rid}: output outside item domain")
        positive_long(rec.out_amt, f"recipe {rid} output")
        seen = set()
        for item, amount in rec.inputs:
            if type(item) is not int or not 0 <= item < graph.n_item or item in seen:
                raise ValueError(f"recipe {rid}: duplicate/out-of-range input {item}")
            seen.add(item)
            positive_long(amount, f"recipe {rid} input {item}")
        if rec.out >= graph.n_real:
            if (rec.out_amt != 1 or len(rec.inputs) != 1 or rec.inputs[0][1] != 1
                    or rec.inputs[0][0] >= graph.n_real or rec.ws):
                raise ValueError(f"recipe {rid}: pseudo output is not a pure real-member-to-tag 1:1 edge")
    with destination.open("wb") as out:
        out.write(struct.pack(">iii", graph.n_real, graph.n_item, len(graph.recipes)))
        for rec in graph.recipes:
            out.write(struct.pack(">iqi", rec.out, rec.out_amt, len(rec.inputs)))
            for item, amount in rec.inputs:
                out.write(struct.pack(">iq", item, amount))
    return counts, (time.perf_counter() - started) * 1000


def java_environment(java_home):
    home = Path(java_home or os.environ.get("JAVA_HOME", "")).expanduser()
    release = home / "release"
    version = release.read_text(encoding="utf-8") if release.is_file() else ""
    if not re.search(r'^JAVA_VERSION="21(?:[.\-+"_])', version, re.MULTILINE):
        raise ValueError("set JAVA_HOME or --java-home to a JDK 21 installation")
    home = home.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    java, javac = home / "bin" / ("java" + suffix), home / "bin" / ("javac" + suffix)
    if not java.is_file() or not javac.is_file():
        raise ValueError("JDK 21 must contain both java and javac")
    env = dict(os.environ, JAVA_HOME=str(home))
    env["PATH"] = str(home / "bin") + os.pathsep + env.get("PATH", "")
    return java, javac, env


def build_classpath(out, env):
    # Use both source-set paths: the planner can refer to compileOnly API classes.
    init = out / "classpath.init.gradle"
    cp_file = out / "classpath.txt"
    init.write_text("""allprojects {
    afterEvaluate { project ->
        if (project == rootProject) {
            tasks.register('awrBenchmarkClasspath') {
                dependsOn tasks.named('classes')
                doLast {
                    def cp = sourceSets.main.runtimeClasspath + sourceSets.main.compileClasspath
                    new File(System.getProperty('awrBenchmark.classpathFile')).text = cp.asPath
                }
            }
        }
    }
}
""", encoding="utf-8")
    wrapper = REPO / ("gradlew.bat" if os.name == "nt" else "gradlew")
    checked_run([wrapper, "--no-daemon", "-I", init,
                 "-DawrBenchmark.classpathFile=" + str(cp_file), "awrBenchmarkClasspath"],
                cwd=REPO, env=env)
    return cp_file.read_text(encoding="utf-8").strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aw-root", type=Path, required=True, help="AW checkout containing bench/awrlib.py")
    parser.add_argument("--datasets", default="recipes-vanilla", help="comma-separated manifest/data basenames")
    parser.add_argument("--data-dir", type=Path, help="AWR directory; default AW_ROOT/temp")
    parser.add_argument("--plan-dir", type=Path, help="saved AW manifests; default AW_ROOT/bench/plans")
    parser.add_argument("--out-dir", type=Path, help="default build/awr-benchmark/<timestamp>")
    parser.add_argument("--java-home", help="JDK 21; defaults to JAVA_HOME")
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--time-limit", type=float, default=40, help="cooperative planning deadline in seconds per query")
    parser.add_argument("--target-stock", choices=("additional", "available"), default="additional")
    args = parser.parse_args()
    if args.warmup < 0 or args.repeats < 1 or not math.isfinite(args.time_limit) or not 0 < args.time_limit <= 3600:
        parser.error("warmup must be >=0, repeats >=1, and time-limit in (0, 3600]")
    datasets = args.datasets.split(",")
    if not datasets or len(set(datasets)) != len(datasets) or any(not re.fullmatch(r"[A-Za-z0-9_-]+", d) for d in datasets):
        parser.error("datasets must be unique, comma-separated basenames")
    aw = args.aw_root.resolve()
    data_dir = (args.data_dir or aw / "temp").resolve()
    plan_dir = (args.plan_dir or aw / "bench" / "plans").resolve()
    out = (args.out_dir or REPO / "build" / "awr-benchmark" / time.strftime("%Y%m%d-%H%M%S")).resolve()
    out.mkdir(parents=True, exist_ok=True)
    reader_path = aw / "bench" / "awrlib.py"
    Graph = load_reader(reader_path)
    java, javac, env = java_environment(args.java_home)
    versions = {"tb": git_state(REPO), "aw": git_state(aw)}
    sources = {"normalizer": source(reader_path), "runner_python": source(HERE / "run.py"),
               "runner_java": source(HERE / "AwrBench.java"), "tb_source_tree": tb_source_hash()}
    configs = []
    for dataset in datasets:
        run_dir = out / dataset
        run_dir.mkdir(parents=True, exist_ok=True)
        awr = data_dir / (dataset + ".awr")
        meta = plan_dir / (dataset + ".meta.tsv")
        targets = plan_dir / (dataset + ".targets.tsv")
        stocks = {name: plan_dir / (dataset + ".stock." + name + ".tsv")
                  for name in ("none", "leaves", "random20")}
        normalized = run_dir / "normalized.bin"
        counts, normalize_ms = normalize(Graph, awr, meta, normalized)
        manifest = {"awr": source(awr), "meta": source(meta), "targets": source(targets),
                    "stocks": {name: source(path) for name, path in stocks.items()},
                    "normalized": source(normalized)}
        config = {"type": "config", "dataset": dataset, "profile": "tb-v2", "config": "tb-v2",
                  "target_stock_policy": args.target_stock, "warmup_passes": args.warmup,
                  "repeats": args.repeats, "time_limit_seconds": args.time_limit,
                  "counts": counts, "normalize_ms": normalize_ms, "versions": versions,
                  "sources": sources, "manifest": manifest, "input_path": str(normalized),
                  "targets_path": str(targets), "stock_paths": {name: str(p) for name, p in stocks.items()},
                  "output_path": str(out / (dataset + ".tb-v2.jsonl")),
                  "item_id_base": 1, "recipe_id_base": 0,
                  "recipe_ids": "AW Graph.prepare() order", "tag_policy": "explicit nReal, pure 1:1, cost=0",
                  "workstations": "recipe registry metadata; no capacity or inventory charge",
                  "firings_include_tag_transfers": True, "jvm_arguments": ["-Xmx3g", "-Xss16m"]}
        path = run_dir / "config.json"
        path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        configs.append(path)
    cp = build_classpath(out, env)
    classes = out / "classes"
    classes.mkdir(exist_ok=True)
    checked_run([javac, "--release", "21", "-cp", cp, "-d", classes, HERE / "AwrBench.java"], cwd=REPO, env=env)
    for config in configs:
        checked_run([java, "-Xmx3g", "-Xss16m", "-cp", str(classes) + os.pathsep + cp,
                     "com.moakiee.thunderbolt.core.crafting.planner.AwrBench", config], cwd=REPO, env=env)
    print("Results: " + str(out), flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("error: " + str(error), file=sys.stderr)
        sys.exit(1)
