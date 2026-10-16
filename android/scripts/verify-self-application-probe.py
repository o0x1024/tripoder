#!/usr/bin/env python3
"""Bind the owned logical Application lifecycle and actual compiled callers."""
import argparse
import hashlib
import importlib.util
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
    parser.add_argument('--expected', choices=['jit', 'aot'], required=True)
    args = parser.parse_args()
    build = json.loads((args.evidence / 'build-receipt.json').read_text())
    result_path = args.evidence / 'device/result.json'
    result = json.loads(result_path.read_text())
    require(result['passed'] is True and result['phase'] == 'H3f-application'
            and result['runId'] == str(uuid.UUID(args.run_id)) and result['artCompilation'] == args.expected, 'APPLICATION_RUN_NOT_PASSED')
    require(result['package'] == 'dev.tripoder.selfhookprobe' and result['debuggable'] is False
            and result['uid'] >= 10000 and result['pid'] > 0, 'APPLICATION_PROCESS_IDENTITY')
    require(result['apkSha256'] == build['apkSha256'] == sha(Path(build['apk']))
            and result['build']['buildId'] == build['buildId'] and result['build']['sourceDigest'] == build['sourceDigest']
            and result['nativeSha256'] == build['nativeSha256'], 'APPLICATION_BUILD_IDENTITY')
    for name, digest in build['sources'].items():
        require(sha(ROOT / name) == digest, 'SOURCE_CHANGED:' + name)
    require(hashlib.sha256(json.dumps(build['sources'], sort_keys=True).encode()).hexdigest() == build['sourceDigest'], 'SOURCE_MANIFEST_DIGEST')
    expected = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())
    require(result['platformGatePassed'] is True and result['platform']['process64Bit'] is True, 'APPLICATION_PLATFORM_GATE')
    for name, value in expected.items():
        require(result['platform'][name] == value, 'APPLICATION_PLATFORM_IDENTITY:' + name)
    contract = json.loads((args.evidence / 'art-framework-contract.json').read_text())
    require(contract['passed'] is True and len(contract['entries']) == 8
            and contract['artSha256'] == build['platformInputs']['artSha256'] == expected['binaries']['/apex/com.android.art/lib64/libart.so']
            and contract['contractSha256'] == sha(ROOT / 'android/self-hook/native/art_framework_contract.h')
            and contract['checkerSha256'] == sha(ROOT / 'android/self-hook/tests/check_art_framework_contract.py'), 'APPLICATION_ART_CONTRACT')
    access = result['frameworkAccess']
    require(access['ready'] is True and access['stage'] == 'READY' and access['jniIdType'] == 0
            and 11 < access['calls'] <= 8192 and 1 <= access['readableRanges'] <= 2048
            and len(access['fastFlags']) == 3 and all(flags & 0x80108 == 0x80108 for flags in access['fastFlags']), 'APPLICATION_FRAMEWORK_ACCESS')
    fixture = result['fixture']
    require(fixture == {'constructors': 1, 'attaches': 1, 'creates': 1, 'denials': 2,
                        'package': 'dev.tripoder.selfhookprobe.logical'} and result['factoryConstructors'] == 1, 'APPLICATION_SINGLE_LIFECYCLE')
    for name in ['logicalContextCreated', 'applicationAttached', 'onCreateReturned', 'physicalOwnershipPreserved', 'viewRestored']:
        require(result[name] is True, 'APPLICATION_IDENTITY:' + name)
    require(result['compiledGetCalls'] == result['compiledSetCalls'] == 1000000
            and result['workerReads'] == 40000 and result['workerErrors'] == 0 and 1 <= result['primeRounds'] <= 1000, 'APPLICATION_VIEW_CALLS')
    require(result['delegatingSelectedLoader'] is True and result['physicalWorkerChecks'] == 400
            and result['physicalApplication'] == {'ready': True, 'retainedGlobalRefs': 4, 'checks': 409, 'stage': 'READY'},
            'FIXED_JNI_PHYSICAL_IDENTITY')
    state, prime = result['application'], result['primeRounds'] * 10000
    require(state['stage'] == 'ATTACHED' and state['constructs'] == state['attaches'] == 1 and state['physicalChecks'] == 12
            and state['retainedGlobalRefs'] == 18 and state['viewRoots'] == 9 and state['viewReady'] is True
            and state['viewEnabled'] is False and state['viewTransitions'] == 2 and state['denials'] == 2
            and state['mappedGets'] == 1040006 + prime and state['mappedSets'] == 1000003 + prime, 'APPLICATION_NATIVE_LIFETIME_AND_EXACT_COUNTS')
    maps_path = args.evidence / 'device/maps.txt'
    maps = maps_path.read_text()
    require(sha(maps_path) == result['mapsSha256'] and result['forbiddenMappings'] == [] and 'libtripoderselfhook.so' in maps
            and not any(word in maps.lower() for word in ['frida', 'gadget', 'libgum']), 'APPLICATION_MAPS')
    spec = importlib.util.spec_from_file_location('art_compilation', ROOT / 'android/scripts/verify-self-art-compilation.py')
    classifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(classifier)
    before, after = (classifier.entry(result[key], maps) for key in ['compilationBefore', 'compilationAfter'])
    require(before['kind'] == after['kind'] == args.expected and before['dexIndex'] == after['dexIndex'], 'APPLICATION_COMPILED_ENTRY')
    if args.expected == 'aot':
        compile_log = (args.evidence / 'aot-compile.txt').read_text()
        require('actualCompilerFilter=speed' in compile_log and 'Final Status: PERFORMED' in compile_log, 'APPLICATION_AOT_COMPILATION')
    summary = {'passed': True, 'phase': result['phase'], 'runId': result['runId'], 'buildId': build['buildId'],
               'apkSha256': build['apkSha256'], 'sourceDigest': build['sourceDigest'], 'kind': args.expected,
               'before': before, 'after': after, 'physicalChecks': 12, 'compiledGets': 1000000, 'compiledSets': 1000000,
               'fixedJniPhysicalChecks': 409, 'physicalWorkerChecks': 400, 'delegatingSelectedLoader': True,
               'scope': 'Owned second-loader Application and real framework ownership only; no product bootstrap or component acceptance',
               'resultSha256': sha(result_path), 'verifierSha256': sha(Path(__file__).resolve())}
    with (args.evidence / 'verification.json').open('x') as output:
        output.write(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
