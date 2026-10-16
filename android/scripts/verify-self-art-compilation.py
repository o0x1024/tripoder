#!/usr/bin/env python3
"""Classify actual method entry mappings; warm-up counts alone are not evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def owned_aot(path, text):
    for line in text.splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) != 6 or not fields[1].startswith('r') or fields[4] == '0':
            continue
        apk = fields[5]
        if not re.fullmatch(r'/data/app/[^/\s]+/dev\.tripoder\.selfhookprobe-[^/\s]+/base\.apk', apk):
            continue
        if path in (apk.rsplit('/', 1)[0] + '/oat/arm64/base.odex',
                    '/data/dalvik-cache/arm64/' + apk[1:].replace('/', '@') + '@classes.dex'):
            return True
    return False


def entry(snapshot, text):
    require(snapshot['bound'] and snapshot['methodReadable'] and snapshot['threadPresent'], 'METHOD_UNREADABLE')
    words = snapshot['words']
    require(len(words) == 8 and all(isinstance(x, int) and 0 <= x <= 0xffffffff for x in words), 'METHOD_WORDS')
    address = words[6] | (words[7] << 32)
    for line in text.splitlines():
        fields = line.split(maxsplit=5)
        start, end = (int(x, 16) for x in fields[0].split('-'))
        if start <= address and address + 4 <= end:
            require(fields[1].startswith('r-x'), 'ENTRY_NOT_EXECUTABLE')
            path = fields[5] if len(fields) == 6 else ''
            kind = 'jit' if 'dalvik-jit-code-cache' in path else (
                'aot' if fields[4] != '0' and owned_aot(path, text) else (
                    'runtime' if path == '/apex/com.android.art/lib64/libart.so' else 'unknown'))
            return {'address': hex(address), 'kind': kind, 'mapping': line, 'dexIndex': words[2]}
    raise RuntimeError('ENTRY_NOT_MAPPED')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--expected', choices=['jit', 'aot'], required=True)
    args = parser.parse_args()
    result_path = args.evidence / 'device/result.json'
    result = json.loads(result_path.read_text())
    verified = json.loads((args.evidence / 'verification.json').read_text())
    require(verified['passed'] and verified['runId'] == result['runId']
            and verified['resultSha256'] == hashlib.sha256(result_path.read_bytes()).hexdigest(), 'BASE_VERIFICATION')
    maps = (args.evidence / 'device/maps.txt').read_text()
    require(hashlib.sha256(maps.encode()).hexdigest() == result['mapsSha256'], 'MAPS_IDENTITY')
    binding = result['artNative']
    require(binding['requestedCompilation'] == args.expected and binding['hotInvocation'] == 'Method.invoke', 'EXPLICIT_COMPILATION_REQUEST')
    before, after = (entry(binding[key], maps) for key in ['hotMethodBefore', 'hotMethodAfter'])
    require(before['dexIndex'] == after['dexIndex'], 'METHOD_DEX_ID_CHANGED')
    require(binding['hotCalls'] == binding['hotIntercepted'] == 1000000 and binding['hotFailures'] == 0, 'INTERCEPTION')
    require(after['kind'] == args.expected, 'COMPILED_ENTRY_NOT_OBSERVED')
    require(before['kind'] == args.expected, 'REQUESTED_ENTRY_NOT_PRESENT_BEFORE_HOT_LOOP')
    if args.expected == 'aot':
        require(before['kind'] == 'aot', 'AOT_NOT_PRESENT_BEFORE_HOT_LOOP')
        compile_log = (args.evidence / 'aot-compile.txt').read_text()
        require('actualCompilerFilter=speed' in compile_log and 'Final Status: PERFORMED' in compile_log, 'AOT_COMPILATION')
    managed = result['artManaged']
    caller_before, caller_after = (entry(managed[key], maps) for key in ['callerBefore', 'callerAfterDeopt'])
    require(managed['requestedCompilation'] == args.expected and caller_before['kind'] == args.expected,
            'MANAGED_CALLER_NOT_COMPILED_BEFORE_INSTALL')
    base = int(managed['callerAfterDeopt']['base'], 16)
    require(caller_before['dexIndex'] == caller_after['dexIndex'] and caller_after['kind'] == 'runtime'
            and int(caller_after['address'], 16) == base + 0x2bc880, 'MANAGED_CALLER_NOT_DEOPTIMIZED')
    reflection = result['reflectionView']
    reflection_before, reflection_after = (entry(reflection[key], maps) for key in ['hotMethodBefore', 'hotMethodAfter'])
    require(reflection['requestedCompilation'] == args.expected and reflection['hotInvocation'] == 'Method.invoke'
            and reflection_before['kind'] == reflection_after['kind'] == args.expected
            and reflection_before['dexIndex'] == reflection_after['dexIndex']
            and reflection['hotCalls'] == reflection['hotMapped'] == reflection['hotSetMapped'] == 1000000
            and reflection['hotPhysicalUnchanged'] is True and reflection['hotErrors'] == 0,
            'COMPILED_REFLECTION_IDENTITY_VIEW')
    summary = {'passed': True, 'runId': result['runId'], 'kind': args.expected, 'before': before, 'after': after,
               'interceptedCalls': binding['hotIntercepted'], 'scope': 'exact Field.get binding from this compiled hotRead caller',
               'genericManagedHook': False, 'inliningCoverageComplete': False,
               'managedCallerBefore': caller_before, 'managedCallerAfter': caller_after,
               'compiledCallerBeforeDeopt': managed['compiledCallerBeforeDeopt'],
               'compiledCallerAfterDeopt': managed['compiledCallerAfterDeopt'],
               'reflectionViewBefore': reflection_before, 'reflectionViewAfter': reflection_after,
               'reflectionViewMappedCalls': reflection['hotMapped'],
               'reflectionViewMappedSets': reflection['hotSetMapped'],
               'verifierSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    with (args.evidence / 'compilation-verification.json').open('x') as output:
        output.write(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
