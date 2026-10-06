#!/usr/bin/env python3
"""Compare zero-demand AE2 networks with an independently compiled Git baseline.

Requires JDK 17 in JAVA_HOME and the normal Gradle dependencies. Existing
classpath exports can be reused with --classpath-file. Writes local evidence
under build/channel-idle-benchmark; no game world is started.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='c339807')
    parser.add_argument('--classpath-file', type=Path)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--oracle', type=int, default=20000)
    parser.add_argument('--repeats', type=int, default=3)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source_dir = Path(__file__).resolve().parent
    out = root / 'build/channel-idle-benchmark'
    classes = out / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    java_bin = Path(os.environ['JAVA_HOME']) / 'bin'
    if args.classpath_file is None:
        launcher = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        command = [str(launcher), '--console=plain', '-I', str(source_dir / 'classpath.init.gradle'),
                   'writeChannelBenchmarkClasspath']
        if args.offline:
            command.append('--offline')
        subprocess.run(command, cwd=root, check=True)
        args.classpath_file = root / 'build/channel-benchmark/dependencies.txt'
    dependencies = args.classpath_file.read_text().strip()
    channel = root / 'src/main/java/com/moakiee/thunderbolt/core/channel'
    names = ('BorrowedCapacityCalculator', 'BidirectionalFlowSeed', 'ChannelFlowNetwork')
    renames = {name: 'Baseline' + name for name in names}
    snapshots = []
    source_hashes = {}
    for name in names:
        path = channel / (name + '.java')
        relative = path.relative_to(root).as_posix()
        original = subprocess.check_output(['git', 'show', f'{args.baseline}:{relative}'], cwd=root).decode('utf-8')
        source_hashes[relative] = dict(before=hashlib.sha256(original.encode('utf-8')).hexdigest(),
                                      after=hashlib.sha256(path.read_bytes()).hexdigest())
        for old, new in renames.items():
            original = original.replace(old, new)
        snapshot = out / ('Baseline' + name + '.java')
        snapshot.write_text(original, encoding='utf-8')
        snapshots.append(snapshot)
    # AlgorithmComparison's older alias is needed for its correctness helpers only.
    old_alias = (out / 'BaselineBorrowedCapacityCalculator.java').read_text(encoding='utf-8')
    old_path = out / 'BaselineCapacityCalculator.java'
    old_path.write_text(old_alias.replace('BaselineBorrowedCapacityCalculator', 'BaselineCapacityCalculator'), encoding='utf-8')
    sources = [*channel.glob('*.java'), *snapshots, old_path,
               source_dir / 'ChannelStress.java', source_dir / 'AlgorithmComparison.java',
               source_dir / 'IdleChannelBenchmark.java']
    subprocess.run([str(java_bin / 'javac'), '-encoding', 'UTF-8', '-proc:none', '-cp', dependencies,
                    '-d', str(classes), *map(str, sources)], check=True)
    flags = ['-Xms256m', '-Xmx2g', '-Xss1m', '-XX:+UseG1GC']
    prefix = [str(java_bin / 'java'), *flags, '-cp', str(classes) + os.pathsep + dependencies]
    rows = []

    def run(label, main_class, arguments):
        print('RUN', label, flush=True)
        log = out / (label + '.log')
        with log.open('wb') as output:
            subprocess.run([*prefix, main_class, *map(str, arguments)], cwd=out,
                           stdout=output, stderr=subprocess.STDOUT, check=True, timeout=360)
        return log.read_text(encoding='utf-8', errors='replace')

    exact = run('exact', 'IdleChannelBenchmark', ['exact', args.oracle])
    assert f'EXACT DEMAND RESULTS PASS {args.oracle}' in exact and 'EXACT IDLE RESULTS PASS 500' in exact
    oracle = run('oracle', 'AlgorithmComparison', ['current', 'oracle', args.oracle])
    assert all(marker in oracle for marker in (f'ORACLE current PASS {args.oracle}', 'SPECIAL PASS', 'LIFECYCLE PASS'))
    print('EXACT AND ORACLE PASS', args.oracle, flush=True)
    for repeat in range(args.repeats):
        for scenario, size, warmup, samples in [('idle-chain', 20000, 128, 120),
                                                ('idle-mesh', 136192, 24, 40),
                                                ('idle-vanilla', 20000, 128, 120)]:
            output = run(f'paired-{scenario}-{repeat}', 'IdleChannelBenchmark',
                         ['paired', scenario, size, warmup, samples])
            parsed = [json.loads(line[7:]) for line in output.splitlines() if line.startswith('RESULT ')]
            assert len(parsed) == 2
            for row in parsed:
                row.update(kind='paired', repeat=repeat, warmup=warmup, samples=samples)
                rows.append(row)
                print(json.dumps({k: v for k, v in row.items() if k != 'samples_ms'}), flush=True)
            (out / 'results.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
        for variant in (('before', 'after') if repeat % 2 == 0 else ('after', 'before')):
            output = run(f'independent-idle-chain-{variant}-{repeat}', 'IdleChannelBenchmark',
                         [variant, 'idle-chain', 20000, 256, 120])
            parsed = [json.loads(line[7:]) for line in output.splitlines() if line.startswith('RESULT ')]
            assert len(parsed) == 1
            row = parsed[0]
            row.update(kind='independent', repeat=repeat, warmup=256, samples=120)
            rows.append(row)
            print(json.dumps({k: v for k, v in row.items() if k != 'samples_ms'}), flush=True)
            (out / 'results.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
    manifest = dict(baseline=args.baseline, java=subprocess.check_output(
        [str(java_bin / 'java'), '-version'], stderr=subprocess.STDOUT, text=True), flags=flags,
        source_hashes=source_hashes, oracle_graphs=args.oracle, exact_demand_graphs=args.oracle,
        exact_idle_graphs=500, jvms_per_protocol=args.repeats)
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
