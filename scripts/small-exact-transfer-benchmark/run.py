#!/usr/bin/env python3
"""Benchmark frozen IndexedStorage variants using the same public API workload (ABBA)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = 'src/main/java/com/moakiee/thunderbolt/core/storage/cell/IndexedStorage.java'
HARNESS = Path(__file__).with_name('SmallExactTransferBenchmark.java')
MAIN = 'com.moakiee.thunderbolt.core.storage.cell.SmallExactTransferBenchmark'


def run(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-ref', default='69e9af1')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--warmup-ms', type=int, default=1000)
    parser.add_argument('--sample-ms', type=int, default=150)
    parser.add_argument('--scenario', choices=[f'{kind}_{size}' for size in (1, 1024)
                        for kind in ('engine_small', 'engine_long_sized', 'engine_simulate',
                                     'inventory_small', 'engine_legacy', 'engine_wide')])
    args = parser.parse_args()
    if args.warmup_ms <= 0 or args.sample_ms <= 0:
        parser.error('Measurement durations must be positive')
    out = ROOT / 'build/small-exact-transfer-benchmark'
    out.mkdir(parents=True, exist_ok=True)
    cp_file = out / 'classpath.txt'
    if not args.skip_build:
        run('./gradlew', '--offline', '-I', 'scripts/small-exact-transfer-benchmark/classpath.init.gradle',
            'writeSmallExactTransferClasspath')
    classpath = cp_file.read_text().strip()
    if args.scenario:
        out = out / args.scenario
        out.mkdir(exist_ok=True)
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    flags = ['-Xms512m', '-Xmx512m', f'-Dbenchmark.warmupMillis={args.warmup_ms}',
             f'-Dbenchmark.sampleMillis={args.sample_ms}']
    if args.scenario:
        flags.append(f'-Dbenchmark.scenario={args.scenario}')
    ref = run('git', 'rev-parse', args.baseline_ref, capture_output=True).stdout.strip()
    order = ['baseline', 'optimized', 'optimized', 'baseline']
    manifest = {'baseline_ref': ref, 'java': run(java, '-version', capture_output=True).stderr,
                'jvm_flags': flags, 'order': order,
                'harness_sha256': hashlib.sha256(HARNESS.read_bytes()).hexdigest(), 'source_sha256': {}}
    for variant in ('baseline', 'optimized'):
        directory = out / variant
        directory.mkdir(exist_ok=True)
        source = directory / 'IndexedStorage.java'
        content = run('git', 'show', f'{ref}:{SOURCE}', capture_output=True).stdout if variant == 'baseline' else (ROOT / SOURCE).read_text()
        source.write_text(content)
        manifest['source_sha256'][variant] = hashlib.sha256(content.encode()).hexdigest()
        classes = directory / 'classes'
        classes.mkdir(exist_ok=True)
        run(javac, '-proc:none', '-cp', classpath, '-d', str(classes), str(source), str(HARNESS))
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    rows = []
    for fork, variant in enumerate(order):
        print(f'Running {variant} JVM {fork + 1}/4', flush=True)
        log = out / f'{fork}-{variant}.jsonl'
        with log.open('w') as stream:
            run(java, *flags, '-cp', str(out / variant / 'classes') + os.pathsep + classpath, MAIN, stdout=stream)
        rows.extend(dict(json.loads(line), variant=variant, fork=fork)
                    for line in log.read_text().splitlines() if line.startswith('{"scenario":'))
    summary = []
    for scenario in dict.fromkeys(row['scenario'] for row in rows):
        selected = [row for row in rows if row['scenario'] == scenario]
        signatures = {row['signature'] for row in selected}
        if len(signatures) != 1:
            raise AssertionError(f'Conservation certificate mismatch: {scenario}')
        result = {'scenario': scenario, 'signature': signatures.pop()}
        for variant in ('baseline', 'optimized'):
            samples = [row for row in selected if row['variant'] == variant]
            if len(samples) != 14:
                raise AssertionError(f'Incomplete samples: {scenario}/{variant}')
            result[variant] = {field: statistics.median(row[field] for row in samples)
                               for field in ('ns_per_op', 'cpu_ns_per_op', 'bytes_per_op')}
        for field in ('ns_per_op', 'cpu_ns_per_op'):
            result[f'{field}_change_percent'] = (result['optimized'][field] / result['baseline'][field] - 1) * 100
        summary.append(result)
        print(json.dumps(result), flush=True)
    (out / 'results.json').write_text(json.dumps({'manifest': manifest, 'summary': summary, 'samples': rows}, indent=2) + '\n')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(f'{Path(error.cmd[0]).name} failed with exit status {error.returncode}') from None
