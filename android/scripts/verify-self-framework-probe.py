#!/usr/bin/env python3
"""Bind the separate cold-start, fixed-framework JNI lookup observation."""
import argparse
import hashlib
import json
from pathlib import Path
import uuid

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    args = parser.parse_args()
    build = json.loads((args.evidence / 'build-receipt.json').read_text())
    path = args.evidence / 'device/result.json'
    result = json.loads(path.read_text())
    require(result['passed'] is True and result['phase'] == 'H3f-fixed-access'
            and result['runId'] == str(uuid.UUID(args.run_id)), 'FRAMEWORK_RUN_NOT_PASSED')
    require(result['package'] == 'dev.tripoder.selfhookprobe' and result['debuggable'] is False
            and result['uid'] >= 10000 and result['pid'] > 0, 'FRAMEWORK_PROCESS_IDENTITY')
    require(result['apkSha256'] == build['apkSha256'] == sha(Path(build['apk']))
            and result['build']['buildId'] == build['buildId']
            and result['build']['sourceDigest'] == build['sourceDigest']
            and result['nativeSha256'] == build['nativeSha256'], 'FRAMEWORK_BUILD_IDENTITY')
    for name, digest in build['sources'].items():
        require(sha(ROOT / name) == digest, 'SOURCE_CHANGED:' + name)
    require(hashlib.sha256(json.dumps(build['sources'], sort_keys=True).encode()).hexdigest() == build['sourceDigest'], 'SOURCE_MANIFEST_DIGEST')
    expected = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())
    require(result['platformGatePassed'] is True and result['platform']['process64Bit'] is True, 'FRAMEWORK_PLATFORM_GATE')
    for name, value in expected.items():
        require(result['platform'][name] == value, 'FRAMEWORK_PLATFORM_IDENTITY:' + name)
    framework, status = result['framework'], result['nativeStatus']
    require(framework['passed'] is True and framework['classes'] == status['classes'] == 7
            and framework['members'] == status['members'] == 21 and status['stage'] == 'COMPLETE'
            and framework['physicalOwnership'] is True, 'FRAMEWORK_EXACT_JNI_LOOKUPS')
    require(framework['frameworkFieldsWritten'] is False and framework['methodBindingsChanged'] is False
            and framework['globalRefsRetained'] == 7 and framework['hiddenApiExemptionsSet'] is False
            and framework['accessKind'] == 'fixed-art-registry' and framework['ordinaryJniCalibrations'] == 17
            and 1 <= result['reflectedMethods'] <= 5120, 'FRAMEWORK_LOOKUP_SCOPE')
    contract = json.loads((args.evidence / 'art-framework-contract.json').read_text())
    require(contract['passed'] is True and contract['artSha256'] == build['platformInputs']['artSha256']
            == expected['binaries']['/apex/com.android.art/lib64/libart.so']
            and contract['contractSha256'] == sha(ROOT / 'android/self-hook/native/art_framework_contract.h')
            and contract['rootBarrierContractSha256'] == sha(ROOT / 'android/self-hook/native/art_storage_contract.h')
            and len(contract['entries']) == 10 and {'READ_ROOT', 'MARK_ROOT'} <= {e['name'] for e in contract['entries']}
            and contract['checkerSha256'] == sha(ROOT / 'android/self-hook/tests/check_art_framework_contract.py'), 'FRAMEWORK_ART_CONTRACT')
    maps_path = args.evidence / 'device/maps.txt'
    maps = maps_path.read_text().lower()
    require(sha(maps_path) == result['mapsSha256'] and result['forbiddenMappings'] == []
            and 'libtripoderselfhook.so' in maps and not any(word in maps for word in ('frida', 'gadget', 'libgum')),
            'FRAMEWORK_MAPS')
    summary = {'passed': True, 'phase': result['phase'], 'runId': result['runId'], 'buildId': build['buildId'],
               'apkSha256': build['apkSha256'], 'sourceDigest': build['sourceDigest'],
               'classes': 7, 'members': 21, 'physicalOwnership': True,
               'scope': 'Fixed ART registry, real JNI ID calibration and physical ownership; no logical context/application construction',
               'resultSha256': sha(path), 'verifierSha256': sha(Path(__file__).resolve())}
    with (args.evidence / 'verification.json').open('x') as output:
        output.write(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
