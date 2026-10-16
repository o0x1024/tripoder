#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    core = ROOT / 'android/self-hook'
    build = core / 'build/caller-closure-host'
    oracle, dex, host = [build / name for name in ['oracle', 'dex', 'host']]
    for p in [oracle, dex, host]:
        p.mkdir(parents=True, exist_ok=True)
    java, javac = [str(args.java_home / 'bin' / name) for name in ['java', 'javac']]
    test_dir = core / 'tests/java/dev/tripoder/selfhook/dex'
    subprocess.run([javac, '--release', '17', '-d', str(oracle), str(test_dir / 'ClosureOracle.java')], check=True)
    subprocess.run([java, '-cp', str(args.sdk / 'build-tools/37.0.0/lib/d8.jar'), 'com.android.tools.r8.D8',
                    '--min-api', '28', '--output', str(dex), *map(str, sorted(oracle.rglob('*.class')))], check=True)
    sources = sorted((core / 'java/dev/tripoder/selfhook/dex').glob('*.java'))
    subprocess.run([javac, '--release', '17', '-d', str(host), *map(str, sources), str(test_dir / 'ClosureHostTest.java')], check=True)
    r = subprocess.run([java, '-cp', str(host), 'dev.tripoder.selfhook.dex.ClosureHostTest', str(dex / 'classes.dex')], capture_output=True, text=True, check=True)
    report = json.loads(r.stdout)
    inputs = [*sources, test_dir / 'ClosureOracle.java', test_dir / 'ClosureHostTest.java', Path(__file__)]
    report.update({'sourceHashes': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
                   'oracleDexSha256': hashlib.sha256((dex / 'classes.dex').read_bytes()).hexdigest()})
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(r.stdout.strip())


if __name__ == '__main__':
    main()
