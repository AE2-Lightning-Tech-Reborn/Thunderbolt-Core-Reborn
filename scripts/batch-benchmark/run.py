"""Run independent JVMs for exact batch inventory and prepared-provider oracles."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', type=Path,
                        default=root / 'build/batch-benchmark/runtime-classpath.txt')
    parser.add_argument('--seeds', type=int, nargs='+', default=[20261008, 20261009, 20261010])
    args = parser.parse_args()
    out = root / 'build/batch-benchmark'
    classes = out / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    if not args.classpath.is_file():
        parser.error('Generate the classpath with writeBatchBenchmarkClasspath first; see README.md')
    jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
    suffix = '.exe' if os.name == 'nt' else ''
    java = str(jdk / ('java' + suffix)) if jdk else shutil.which('java')
    javac = str(jdk / ('javac' + suffix)) if jdk else shutil.which('javac')
    if not java or not javac:
        parser.error('A Java 17 JDK is required; set JAVA_HOME or put java/javac on PATH')
    runtime = args.classpath.read_text(encoding='utf-8').strip()
    subprocess.run([javac, '-encoding', 'UTF-8', '-proc:none', '-implicit:none', '-cp', runtime,
                    '-d', str(classes), str(root / 'scripts/batch-benchmark/BatchStockPressure.java')],
                   cwd=root, check=True)
    records = []
    for index, seed in enumerate(args.seeds):
        destination = out / f'seed-{seed}-{index}.json'
        destination.unlink(missing_ok=True)
        with (out / f'seed-{seed}-{index}.log').open('wb') as log:
            subprocess.run([java, '-Xms256m', '-Xmx2g', '-XX:ActiveProcessorCount=4',
                '-cp', os.pathsep.join([str(classes), runtime]),
                'com.moakiee.thunderbolt.core.crafting.batch.BatchStockPressure', str(destination), str(seed)],
                cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=120, check=True)
        result = json.loads(destination.read_text(encoding='utf-8'))
        if result.get('status') != 'PASS' or result.get('cases') != 20_000 or result.get('provider_cases') != 2_000:
            raise RuntimeError(f'Incomplete oracle result for seed {seed}')
        records.append(result)
        print(json.dumps(result), flush=True)
    (out / 'results.json').write_text(json.dumps(records, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
