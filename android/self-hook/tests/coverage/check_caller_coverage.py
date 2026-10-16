#!/usr/bin/env python3
"""Independent javac/D8 inputs for conservative caller coverage and loader binding."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    root = ROOT / 'android/self-hook'
    build = root / 'build/coverage-host'
    build.mkdir(parents=True, exist_ok=True)
    receipt = build / 'report.json'
    receipt.unlink(missing_ok=True)
    java, javac = (args.java_home / 'bin' / name for name in ('java', 'javac'))
    d8 = args.sdk / 'build-tools/37.0.0/lib/d8.jar'
    fixtures = sorted((root / 'coverage-fixtures').rglob('*.java'))
    sources = sorted((root / 'java/dev/tripoder/selfhook/dex').glob('*.java'))
    sources += sorted((root / 'java/dev/tripoder/selfhook/coverage').glob('*.java'))
    host = Path(__file__).with_name('CallerCoverageHostTest.java')
    inputs = [*fixtures, *sources, host, Path(__file__)]
    hashes = {str(path.relative_to(ROOT)): sha(path) for path in inputs}
    # Every run gets a clean directory; stale D8 classes cannot satisfy a test.
    with tempfile.TemporaryDirectory(prefix='oracle-', dir=build) as temporary:
        temp = Path(temporary)
        classes, first, second, harness = (temp / name for name in ('classes', 'first', 'second', 'harness'))
        for path in (classes, first, second, harness):
            path.mkdir()
        run([javac, '--release', '17', '-d', classes, *fixtures])
        bytecode = sorted(classes.rglob('*.class'))
        one = [p for p in bytecode if p.name != 'CrossDex.class']
        two = [p for p in bytecode if p.name == 'CrossDex.class']
        for paths, output in ((one, first), (two, second)):
            run([java, '-cp', d8, 'com.android.tools.r8.D8', '--min-api', '28', '--no-desugaring',
                 '--output', output, *paths])
        run([javac, '--release', '17', '-cp', args.sdk / 'platforms/android-37.0/android.jar',
             '-d', harness, *sources, host])
        report = json.loads(run([java, '-cp', harness, 'dev.tripoder.selfhook.coverage.CallerCoverageHostTest',
                                first / 'classes.dex', second / 'classes.dex', classes]))
        report['dexSha256'] = [sha(first / 'classes.dex'), sha(second / 'classes.dex')]
    if hashes != {str(path.relative_to(ROOT)): sha(path) for path in inputs}:
        raise RuntimeError('COVERAGE_SOURCES_CHANGED_DURING_TEST')
    report['sourceHashes'] = hashes
    report['toolHashes'] = {'d8': sha(d8)}
    report['scope'] = 'Host DEX candidate analysis and Java identity binding; no ART runtime acceptance'
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
