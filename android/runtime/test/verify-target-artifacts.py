#!/usr/bin/env python3
"""Verify original target APK artifacts with production code; never execute guest code."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--apk', required=True, action='append', type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    args.evidence.mkdir(parents=True, exist_ok=False)
    java = args.java_home / 'bin'
    jar = args.sdk / 'build-tools/37.0.0/lib/apksigner.jar'
    source = repo / 'android/runtime/src/dev/tripoder/runtime'
    sources = [source / name for name in ['ArtifactSet.java', 'ArtifactSetZip.java',
                                         'ApkManifest.java', 'BinXmlTypedValues.java']]
    sources.append(repo / 'android/runtime/test/dev/tripoder/runtime/ArtifactTargetTest.java')
    def hashes():
        return {str(p.relative_to(repo)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    before = hashes()
    root = Path(tempfile.mkdtemp(prefix='tripoder-target-artifact-'))
    classes = root / 'classes'
    classes.mkdir()
    subprocess.run([str(java/'javac'), '--release', '17', '-cp', str(jar), '-d', str(classes),
                    *map(str, sources)], check=True)
    records = []
    for index, apk in enumerate(args.apk):
        name = str(index) + '-' + apk.stem
        with (args.evidence/(name+'-verification.jsonl')).open('w') as out, \
                (args.evidence/(name+'-diagnostics.txt')).open('w') as err:
            result = subprocess.run([str(java/'java'), '-Xmx512m', '-cp', str(classes)+':'+str(jar),
                                     'dev.tripoder.runtime.ArtifactTargetTest', str(root/'work'), str(apk.resolve())],
                                    stdout=out, stderr=err)
        record = {'target': apk.name, 'exitCode': result.returncode}
        records.append(record)
        print(json.dumps(record), flush=True)
    unchanged = before == hashes()
    passed = unchanged and all(r['exitCode'] == 0 for r in records)
    (args.evidence/'receipt.json').write_text(json.dumps({
        'passed': passed, 'results': records, 'sourceSha256': before, 'sourceUnchanged': unchanged,
        'sdkVerifierSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'verificationDirectory': str(root), 'runtimeExecutionVerified': False}, indent=2)+'\n')
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
