#!/usr/bin/env python3
"""Descriptor tests plus independent Android dexdump decoding for every helper."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import shutil

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    core = ROOT / 'android/self-hook'
    build = core / 'build/helper-dex-host'
    classes = build / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    generated=build/'generated'
    # Only this checker's generated output. A changed capacity must never
    # decode stale helpers from an earlier boundary alongside the new set.
    if generated.exists():shutil.rmtree(generated)
    generated.mkdir()
    sources = sorted((core / 'java/dev/tripoder/selfhook/dex').glob('*.java'))
    sources.append(core / 'tests/java/dev/tripoder/selfhook/dex/HelperDexHostTest.java')
    subprocess.run([str(args.java_home / 'bin/javac'), '-d', str(classes), *map(str, sources)], check=True)
    result = subprocess.run([str(args.java_home / 'bin/java'), '-cp', str(classes),
                             'dev.tripoder.selfhook.dex.HelperDexHostTest', str(generated)], capture_output=True, text=True, check=True)
    report = json.loads(result.stdout)
    decoded = []
    boundaries={'helper-9-true.dex':63,'helper-10-false.dex':64,'helper-10-true.dex':4095}
    checked_slots=[]
    for file in sorted([*generated.glob('helper-*.dex'),*generated.glob('cached-*.dex')]):
        parsed = subprocess.run([str(args.sdk / 'build-tools/37.0.0/dexdump'), '-d', str(file)], capture_output=True, text=True, check=True)
        if 'invoke-static/range' not in parsed.stdout or 'Ldev/tripoder/selfhook/ManagedHook;.dispatch' not in parsed.stdout:
            raise RuntimeError('INDEPENDENT_HELPER_DECODE:' + file.name)
        if file.name in boundaries:
            slot=boundaries[file.name]
            if f'Ldev/tripoder/selfhook/generated/Entry{slot};' not in parsed.stdout or not re.search(r'const/16 v2, #int '+str(slot)+r'\b',parsed.stdout):
                raise RuntimeError('INDEPENDENT_HELPER_SLOT_ENCODING:'+file.name)
            checked_slots.append(slot)
        if file.name.startswith('cached-'):
            slot=int(file.stem.split('-')[1])
            if f'Ldev/tripoder/selfhook/generated/Entry{slot};' not in parsed.stdout or not re.search(r'const/16 v2, #int '+str(slot)+r'\b',parsed.stdout):
                raise RuntimeError('INDEPENDENT_CACHED_SLOT_ENCODING:'+file.name)
        (generated / (file.stem + '.txt')).write_text(parsed.stdout)
        decoded.append({'file': file.name, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
    if len(decoded) != 36:
        raise RuntimeError('HELPER_ORACLE_COUNT')
    if sorted(checked_slots)!=[63,64,4095]:raise RuntimeError('HELPER_BOUNDARY_COVERAGE')
    report.update(slotCapacity=4096,independentlyDecodedSlots=sorted(checked_slots))
    report.update({'decoded': decoded, 'sources': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
                   'checkerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   'scope': 'Descriptors and independent instruction decoding; Android execution acceptance is separate'})
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': True, 'checks': report['checks'], 'independentDexDecodes': len(decoded)}))


if __name__ == '__main__':
    main()
