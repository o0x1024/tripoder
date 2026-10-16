#!/usr/bin/env python3
"""Independent identity, metadata, relay, mapping and active-frame checks."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import uuid

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def load_module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'android/scripts' / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--expected', choices=['jit', 'aot'], required=True)
    args = parser.parse_args()
    build = json.loads((args.evidence / 'build-receipt.json').read_text())
    path = args.evidence / 'device/result.json'
    result = json.loads(path.read_text())
    require(result['passed'] is True and result['phase'] == 'H3g-owned-active-entry'
            and result['runId'] == str(uuid.UUID(args.run_id)) and result['artCompilation'] == args.expected, 'ACTIVE_ENTRY_RUN')
    require(result['package'] == 'dev.tripoder.selfhookprobe' and result['debuggable'] is False
            and result['uid'] >= 10000 and result['pid'] > 0, 'ACTIVE_ENTRY_PROCESS')
    require(result['apkSha256'] == build['apkSha256'] == sha(Path(build['apk']))
            and result['nativeSha256'] == build['nativeSha256'] and result['build']['buildId'] == build['buildId']
            and result['build']['sourceDigest'] == build['sourceDigest'], 'ACTIVE_ENTRY_BUILD_IDENTITY')
    for name, digest in build['sources'].items():
        require(sha(ROOT / name) == digest, 'SOURCE_CHANGED:' + name)
    require(hashlib.sha256(json.dumps(build['sources'], sort_keys=True).encode()).hexdigest() == build['sourceDigest'], 'SOURCE_MANIFEST_DIGEST')
    expected = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())
    require(result['platformGatePassed'] is True and result['platform']['process64Bit'] is True, 'ACTIVE_ENTRY_PLATFORM_GATE')
    for name, value in expected.items():
        require(result['platform'][name] == value, 'ACTIVE_ENTRY_PLATFORM_IDENTITY:' + name)
    maps_path, smaps_path = (args.evidence / 'device' / name for name in ['maps.txt', 'smaps.txt'])
    maps, smaps = maps_path.read_text(), smaps_path.read_text()
    require(sha(maps_path) == result['mapsSha256'] and sha(smaps_path) == result['smapsSha256']
            and 'libtripoderselfhook.so' in maps and not any(word in maps.lower() for word in ['frida', 'gadget', 'libgum']), 'ACTIVE_ENTRY_MAPS')
    storage = result['storage']
    require(storage['published'] == storage['allocations'] == storage['rootedOutsideDeclaredTables'] == 1
            and storage['retainedSlotRoots'] == 6 and storage['retainedRegistryRoots'] == 1
            and storage['obsoleteFlag'] is False and storage['jniIdType'] == 0, 'BACKUP_GC_ROOT_PROTOCOL')
    state = result['native']
    require(result['nativeStatus'] == state and state['ready'] is True and state['enabled'] is False and state['stage'] == 'DISABLED'
            and state['transitions'] == 42 and state['retainedRoots'] == 5 and state['retainedRelayPages'] == 1
            and state['callerCount'] == 1 and state['inflight'] == 0 and state['genericManagedHook'] is False, 'ACTIVE_ENTRY_LIFETIME')
    require(result['checks'] == 1071 and result['workerCalls'] >= 1000 and result['workerErrors'] == 0
            and result['attemptedCalls'] == result['originalBodyCalls'] == result['workerCalls'] + 1010
            and state['originals'] == state['callbacks'] + 1 and state['callbacks'] >= 1007, 'ACTIVE_ENTRY_EXACT_ORIGINALS')
    require(result['oldFrameResult'] == 43 and result['inflightResult'] == 143 and result['gcRequestsWithFrames'] == 8, 'ACTIVE_ENTRY_OLD_VERSIONS')
    prepared = result['prepared']
    require(prepared['ready'] is True and prepared['transitions'] == 0 and prepared['callbacks'] == prepared['originals'] == 0, 'ACTIVE_ENTRY_PREPARE')
    samples = [result[name] for name in ['targetBefore', 'targetActive', 'targetAfter']]
    before_words, active_words, after_words = (sample['words'] for sample in samples)
    require(all(len(words) == 8 and words[1] & 0x100 == 0 for words in [before_words, active_words, after_words])
            and len({words[2] for words in [before_words, active_words, after_words]}) == 1
            and len({words[3] & 0xffff for words in [before_words, active_words, after_words]}) == 1
            and before_words[4:6] == active_words[4:6] == after_words[4:6], 'ACTIVE_ENTRY_MANAGED_METADATA')
    require((prepared['targetFlags'] & 0x1ffff) == (active_words[1] & 0x1ffff) == (after_words[1] & 0x1ffff)
            and active_words[1] & 0x02900000 == after_words[1] & 0x02900000 == 0x02000000
            and state['backupFlags'] & 0x02900100 == 0x02000000 and state['helperFlags'] & 0x108 == 0x108
            and state['targetData'] == state['backupData'], 'ACTIVE_ENTRY_SEPARATE_NATIVE_METADATA')
    classifier = load_module('compilation', 'verify-self-art-compilation.py')
    baseline, caller = (classifier.entry(result[name], maps) for name in ['targetBefore', 'callerBefore'])
    require(baseline['kind'] == caller['kind'] == args.expected, 'ACTIVE_ENTRY_COMPILED_BASELINE')
    base = int(result['targetAfter']['base'], 16)
    caller_after = classifier.entry(result['callerAfterDeopt'], maps)
    require(int(caller_after['address'], 16) == base + 0x2bc880 and caller_after['kind'] == 'runtime'
            and caller_after['dexIndex'] == caller['dexIndex'], 'ACTIVE_ENTRY_CALLER_DEOPT')
    relay = int(state['relay'], 16)
    require(active_words[6] | active_words[7] << 32 == relay and after_words[6] | after_words[7] << 32 == base + 0x2bc880
            and int(state['jniBridge'], 16) == base + 0x2bc710, 'ACTIVE_ENTRY_TARGET_ENTRIES')
    mapping = load_module('base_probe', 'verify-self-hook-probe.py').mapping
    relay_map = mapping(maps, relay)
    require(relay_map['mode'] == 'r-xp' and relay_map['inode'] == 0 and relay_map['device'] == '00:00', 'ACTIVE_ENTRY_RELAY_MAPPING')
    selected, guarded = False, False
    for line in smaps.splitlines():
        header = re.match(r'^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)', line)
        if header:
            selected = int(header[1], 16) <= relay and relay + 4096 <= int(header[2], 16)
        elif selected and line.startswith('VmFlags:'):
            guarded = 'bt' in line.split() and 'mt' not in line.split()
    require(guarded, 'ACTIVE_ENTRY_BTI_MAPPING')
    require(mapping(maps, int(state['helperData'], 16))['path'].endswith('/libtripoderselfhook.so'), 'ACTIVE_ENTRY_HELPER_CALLBACK_OWNER')
    oracle = json.loads((args.evidence / 'int-relay-oracle.json').read_text())
    require(oracle['passed'] is True and oracle['checkerSha256'] == sha(ROOT / 'android/self-hook/tests/check_int_relay.py'), 'ACTIVE_ENTRY_RELAY_ORACLE')
    relay_path = args.evidence / 'device/relay.bin'
    image = struct.pack('<6I2Q', *oracle['codeWords'], int(state['helperMethod'], 16), int(state['jniBridge'], 16))
    image += struct.pack('<I', 0xd503201f) * ((4096 - len(image)) // 4)
    require(len(image) == 4096 and relay_path.read_bytes() == image and sha(relay_path) == result['relaySha256'], 'ACTIVE_ENTRY_COMPLETE_RELAY_BYTES')
    if args.expected == 'aot':
        log = (args.evidence / 'aot-compile.txt').read_text()
        require('actualCompilerFilter=speed' in log and 'Final Status: PERFORMED' in log, 'ACTIVE_ENTRY_AOT_COMPILE')
    summary = {'passed': True, 'runId': result['runId'], 'phase': result['phase'], 'kind': args.expected,
               'buildId': build['buildId'], 'apkSha256': build['apkSha256'], 'sourceDigest': build['sourceDigest'],
               'checks': result['checks'], 'workerCalls': result['workerCalls'], 'originalBodyCalls': result['originalBodyCalls'],
               'targetBefore': baseline, 'callerBefore': caller, 'callerAfter': caller_after,
               'oldFrameResult': 43, 'inflightResult': 143, 'gcRequestsWithFrames': 8,
               'scope': 'In-process owned static int(int); cold-start and runtime-image acceptance is separate',
               'resultSha256': sha(path), 'verifierSha256': sha(Path(__file__).resolve())}
    with (args.evidence / 'verification.json').open('x') as output:
        output.write(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
