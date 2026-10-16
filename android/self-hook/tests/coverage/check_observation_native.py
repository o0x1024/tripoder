#!/usr/bin/env python3
"""Compile the read-only collector for the pinned Android target; no device use."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    build = ROOT / 'android/self-hook/build/coverage-host'
    build.mkdir(parents=True, exist_ok=True)
    native = ROOT / 'android/self-hook/native'
    source = native / 'coverage/method_observation.c'
    compiler = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android37-clang'
    output = build / 'method_observation.o'
    receipt = build / 'native-compile-report.json'
    receipt.unlink(missing_ok=True)
    inputs = [source, native / 'art_access.h', native / 'art_framework_contract.h', Path(__file__)]
    hashes = {str(path.relative_to(ROOT)): sha(path) for path in inputs}
    command = [str(compiler), '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wconversion', '-fPIC',
               '-mbranch-protection=standard', '-c', str(source), '-o', str(output)]
    subprocess.run(command, check=True)
    if hashes != {str(path.relative_to(ROOT)): sha(path) for path in inputs}:
        raise RuntimeError('COVERAGE_COLLECTOR_SOURCES_CHANGED')
    report = {'compiled': True, 'target': 'aarch64-linux-android37', 'sourceHashes': hashes,
              'objectSha256': sha(output), 'command': command, 'deviceExecuted': False,
              'scope': 'Compile-only; no loaded inventory or runtime coverage acceptance'}
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
