#!/usr/bin/env python3
"""Compile production hot paths from each revision and run separate ABBA JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SUITES = {
    'runtime': ('RuntimeHotpathBenchmark', 'runtime-hotpath-benchmark', [
        'src/main/java/com/moakiee/thunderbolt/core/crafting/support/CraftingPatternDelegates.java',
        'src/main/java/com/moakiee/thunderbolt/core/crafting/cpu/DynamicCraftingCpuClusterIndex.java',
    ]),
    'big-execution': ('BigExecutionBenchmark', 'big-execution-benchmark', [
        'src/main/java/com/moakiee/thunderbolt/core/crafting/big/BigExecutionProgram.java',
    ]),
    'batch': ('com.moakiee.thunderbolt.core.crafting.batch.BatchHotpathBenchmark', 'batch-hotpath-benchmark', [
        'src/main/java/com/moakiee/thunderbolt/core/crafting/batch/BatchExecutor.java',
        'src/main/java/com/moakiee/thunderbolt/core/crafting/batch/SharedBatchInputs.java',
    ]),
}


def run(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-ref', default='HEAD')
    parser.add_argument('--suite', choices=SUITES, default='runtime')
    parser.add_argument('--skip-build', action='store_true', help='Use an already exported classpath')
    parser.add_argument('--warmup-ms', type=int, default=1_000)
    parser.add_argument('--sample-ms', type=int, default=100)
    args = parser.parse_args()
    if args.warmup_ms <= 0 or args.sample_ms <= 0:
        parser.error('Measurement durations must be positive')
    main_class, output_name, sources = SUITES[args.suite]
    out = ROOT / 'build' / output_name
    out.mkdir(parents=True, exist_ok=True)
    if not args.skip_build:
        run('./gradlew', '--offline', '-I', 'scripts/runtime-hotpath-benchmark/classpath.init.gradle',
            'writeRuntimeBenchmarkClasspath')
    classpath = (ROOT / 'build/runtime-hotpath-benchmark/classpath.txt').read_text().strip()
    baseline = run('git', 'rev-parse', args.baseline_ref, capture_output=True).stdout.strip()
    java = shutil.which('java')
    javac = shutil.which('javac')
    flags = ['-Xms512m', '-Xmx512m', f'-Dbenchmark.warmupMillis={args.warmup_ms}',
             f'-Dbenchmark.sampleMillis={args.sample_ms}']
    manifest = {'suite': args.suite, 'baseline_ref': baseline,
                'java': run(java, '-version', capture_output=True).stderr,
                'jvm_flags': flags,
                'order': ['baseline', 'optimized', 'optimized', 'baseline'], 'sha256': {}}
    shared = {
        'big-execution': ['core/crafting/big/BigCraftingPlanner', 'core/crafting/planner/CraftPlannerV2',
                          'core/crafting/planner/ExactDiagnosticPlanner', 'core/crafting/planner/ExactCraftPlan'],
        'batch': ['core/crafting/batch/ParallelBatchCpuHelper', 'core/crafting/pattern/PlannedInputPattern',
                  'core/crafting/batch/TickProviderDispatchSchedule', 'core/crafting/batch/IdentityCandidateQueue',
                  'core/crafting/support/CraftingPatternDelegates'],
    }.get(args.suite, [])
    bytecode_root = ROOT / 'build/classes/java/main/com/moakiee/thunderbolt'
    manifest['shared_class_sha256'] = {}
    for name in shared:
        stem = bytecode_root / name
        for path in sorted(stem.parent.glob(stem.name + '*.class')):
            manifest['shared_class_sha256'][str(path.relative_to(bytecode_root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    harness = ROOT / 'scripts/runtime-hotpath-benchmark' / f'{main_class.rsplit(".", 1)[-1]}.java'
    manifest['harness_sha256'] = hashlib.sha256(harness.read_bytes()).hexdigest()
    for variant in ('baseline', 'optimized'):
        source_dir = out / variant / 'src'
        classes = out / variant / 'classes'
        source_dir.mkdir(parents=True, exist_ok=True)
        classes.mkdir(parents=True, exist_ok=True)
        paths = []
        for relative in sources:
            content = (run('git', 'show', f'{baseline}:{relative}', capture_output=True).stdout
                       if variant == 'baseline' else (ROOT / relative).read_text())
            path = source_dir / Path(relative).name
            path.write_text(content)
            paths.append(str(path))
            manifest['sha256'][f'{variant}/{relative}'] = hashlib.sha256(content.encode()).hexdigest()
        run(javac, '-proc:none', '-cp', classpath, '-d', str(classes), *paths, str(harness))
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    rows = []
    for fork, variant in enumerate(manifest['order']):
        print(f'Running {variant} JVM {fork + 1}/4', flush=True)
        log = out / f'{fork}-{variant}.jsonl'
        with log.open('w') as stream:
            run(java, *flags, '-cp',
                str(out / variant / 'classes') + os.pathsep + classpath,
                main_class, stdout=stream)
        samples = [json.loads(line) for line in log.read_text().splitlines()
                   if line.startswith('{"scenario":')]
        if not samples:
            raise AssertionError(f'No measurements in {log}')
        rows.extend(dict(sample, variant=variant, fork=fork) for sample in samples)
    summary = []
    for scenario in dict.fromkeys(row['scenario'] for row in rows):
        result = {'scenario': scenario}
        proofs = {row['signature'] for row in rows if row['scenario'] == scenario and 'signature' in row}
        if len(proofs) > 1:
            raise AssertionError(f'Baseline/optimized certificate mismatch in {scenario}: {proofs}')
        if proofs:
            result['signature'] = proofs.pop()
        for variant in ('baseline', 'optimized'):
            samples = [row for row in rows if row['scenario'] == scenario and row['variant'] == variant]
            if len(samples) != 14:
                raise AssertionError(f'Incomplete measurements for {scenario}/{variant}')
            result[variant] = {field: statistics.median(row[field] for row in samples)
                               for field in ('ns_per_op', 'bytes_per_op')}
        result['time_change_percent'] = (result['optimized']['ns_per_op']
                                        / result['baseline']['ns_per_op'] - 1) * 100
        summary.append(result)
        print(json.dumps(result), flush=True)
    (out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(f'{Path(error.cmd[0]).name} failed with exit status {error.returncode}') from None
