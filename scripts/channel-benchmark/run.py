#!/usr/bin/env python3
"""Compile the real AE2 fixture; compare this checkout with the named Git baseline.

Requires JDK 17 (JAVA_HOME) and the normal Gradle dependencies. No game world is
started. Timings cover assignChannels, excluding graph creation and assertions.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='49233dceba0f0d34b0b7baf78e66b41fc42d3138')
    parser.add_argument('--quick', action='store_true')
    parser.add_argument('--oracle', type=int, default=5000)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source_dir = root / 'scripts/channel-benchmark'
    out = root / 'build/channel-benchmark'
    classes = out / 'fixtures'
    classes.mkdir(parents=True, exist_ok=True)
    java_bin = Path(os.environ['JAVA_HOME']) / 'bin'
    gradle = [str(root / 'gradlew'), '--console=plain', '-I', str(source_dir / 'classpath.init.gradle'),
              'writeChannelBenchmarkClasspath']
    if args.offline:
        gradle.append('--offline')
    subprocess.run(gradle, cwd=root, check=True)
    baseline_path = 'src/main/java/com/moakiee/thunderbolt/core/channel/BorrowedCapacityCalculator.java'
    original = subprocess.check_output(['git', 'show', f'{args.baseline}:{baseline_path}'], cwd=root)
    baseline = out / 'BaselineCapacityCalculator.java'
    baseline.write_text(original.decode().replace('BorrowedCapacityCalculator', 'BaselineCapacityCalculator'))
    dependencies = (out / 'dependencies.txt').read_text().strip()
    subprocess.run([str(java_bin / 'javac'), '-proc:none', '-cp', dependencies, '-d', str(classes),
                    str(baseline), str(source_dir / 'ChannelStress.java'),
                    str(source_dir / 'AlgorithmComparison.java')], check=True)
    cp = str(classes) + os.pathsep + dependencies
    prefix = [str(java_bin / 'java'), '-Xss1m', '-Xms512m', '-Xmx2g', '-XX:+UseG1GC',
              '-cp', cp, 'AlgorithmComparison']
    sources = [root / baseline_path, *sorted((root / 'src/main/java/com/moakiee/thunderbolt/core/channel').glob('*Flow*.java')),
               source_dir / 'ChannelStress.java', source_dir / 'AlgorithmComparison.java']
    manifest = {'baseline': args.baseline, 'baseline_source_sha256': hashlib.sha256(original).hexdigest(),
                'java': subprocess.check_output([str(java_bin / 'java'), '-version'], stderr=subprocess.STDOUT, text=True),
                'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    with (out / 'correctness.log').open('w') as log:
        subprocess.run(prefix + ['current', 'oracle', str(args.oracle)], stdout=log, stderr=subprocess.STDOUT,
                       check=True, timeout=120, cwd=out)
    print((out / 'correctness.log').read_text(), flush=True)
    cases = [('tree', 1000, 15, 20), ('comb', 1000, 20, 20), ('chain', 20000, 5, 7)]
    if not args.quick:
        cases += [('tree', 100000, 3, 7), ('tree', 136192, 3, 7), ('mesh', 136192, 3, 7),
                  ('finite', 136192, 3, 7), ('dense', 136192, 3, 7), ('tree', 200000, 3, 7),
                  ('vanilla', 136192, 3, 7), ('weighted', 512, 15, 20)]
    rows = []
    for kind, number, warmup, samples in cases:
        for algorithm in ['baseline', 'current']:
            name = f'{algorithm}-{kind}-{number}'
            print('RUN', name, flush=True)
            with (out / f'{name}.log').open('w') as log:
                result = subprocess.run(prefix + [algorithm, kind, str(number), str(warmup), str(samples)],
                                        stdout=log, stderr=subprocess.STDOUT, timeout=240, cwd=out)
            output = (out / f'{name}.log').read_text()
            results = [json.loads(line[7:]) for line in output.splitlines() if line.startswith('RESULT ')]
            if results:
                row = results[-1]
            elif algorithm == 'baseline' and 'StackOverflowError' in output:
                row = {'algorithm': algorithm, 'scenario': kind, 'parameter': number, 'status': 'STACK_OVERFLOW'}
            else:
                raise RuntimeError(f'{name} exit={result.returncode}\n{output[-4000:]}')
            rows.append(row)
            print(json.dumps(row), flush=True)
            (out / 'results.json').write_text(json.dumps(rows, indent=2))


if __name__ == '__main__':
    main()
