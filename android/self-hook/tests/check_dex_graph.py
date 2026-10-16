#!/usr/bin/env python3
"""Compile an independent D8 oracle, then exercise the same parser used on device."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    root = ROOT / 'android/self-hook'
    build = root / 'build/dex-host'
    oracle, dex, host = (build / name for name in ('oracle', 'dex', 'host'))
    for directory in (oracle, dex, host):
        directory.mkdir(parents=True, exist_ok=True)
    receipt = build / 'report.json'
    receipt.unlink(missing_ok=True)
    source_root = root / 'tests/java/dev/tripoder/selfhook/dex'
    sources = sorted((root / 'java/dev/tripoder/selfhook/dex').glob('*.java'))
    inputs = [*sources, source_root / 'DexOracle.java', source_root / 'DexGraphHostTest.java', Path(__file__)]
    hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    java, javac = (args.java_home / 'bin' / name for name in ('java', 'javac'))
    subprocess.run([str(javac), '--release', '17', '-d', str(oracle), str(source_root / 'DexOracle.java')], check=True)
    subprocess.run([str(java), '-cp', str(args.sdk / 'build-tools/37.0.0/lib/d8.jar'),
                    'com.android.tools.r8.D8', '--min-api', '28', '--output', str(dex),
                    str(oracle / 'dev/tripoder/selfhook/dex/DexOracle.class')], check=True)
    subprocess.run([str(javac), '--release', '17', '-d', str(host), *map(str, sources),
                    str(source_root / 'DexGraphHostTest.java')], check=True)
    run = subprocess.run([str(java), '-cp', str(host), 'dev.tripoder.selfhook.dex.DexGraphHostTest',
                          str(dex / 'classes.dex'), str(args.apk)], capture_output=True, text=True)
    if run.returncode != 0:
        raise RuntimeError('DEX_GRAPH_TEST_FAILED:\n' + run.stdout + run.stderr)
    if hashes != {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}:
        raise RuntimeError('DEX_GRAPH_SOURCES_CHANGED')
    report = {**json.loads(run.stdout), 'sourceHashes': hashes,
              'apkSha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              'scope': 'DEX 039 static edges only; no virtual/ClassLoader/future-load coverage'}
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
