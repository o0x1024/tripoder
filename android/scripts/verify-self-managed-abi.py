#!/usr/bin/env python3
"""Independent ABI, moving-reference, metadata and artifact acceptance."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import uuid
import zipfile
from verify_self_storage import verify as verify_storage
from verify_self_interpreter import verify as verify_interpreter

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'android/scripts' / file)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--expected', choices=['jit', 'aot', 'cold'], required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    d = args.evidence
    build = json.loads((d / 'build-receipt.json').read_text())
    path = d / 'device/result.json'
    result = json.loads(path.read_text())
    require(result['passed'] is True and result['phase'] == 'H3h-managed-abi'
            and result['runId'] == str(uuid.UUID(args.run_id)) and result['artCompilation'] == args.expected, 'ABI_RUN')
    require(result['package'] == 'dev.tripoder.selfhookprobe' and result['debuggable'] is False
            and result['uid'] >= 10000 and result['pid'] > 0, 'OWNED_PROCESS')
    require(result['apkSha256'] == build['apkSha256'] == sha(Path(build['apk']))
            and result['nativeSha256'] == build['nativeSha256']
            and result['build']['buildId'] == build['buildId']
            and result['build']['sourceDigest'] == build['sourceDigest'], 'ABI_BUILD')
    for name, digest in build['sources'].items():
        require(sha(ROOT / name) == digest, 'SOURCE_CHANGED:' + name)
    require(hashlib.sha256(json.dumps(build['sources'], sort_keys=True).encode()).hexdigest() == build['sourceDigest'], 'ABI_SOURCE_MANIFEST')
    platform = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())
    verify_interpreter(d,platform)
    require(result['platformGatePassed'] is True and result['platform']['process64Bit'] is True, 'ABI_PLATFORM_GATE')
    require(all(result['platform'][k] == v for k, v in platform.items()), 'ABI_PLATFORM_IDENTITY')
    maps, smaps = [(d / 'device' / name).read_text() for name in ['maps.txt', 'smaps.txt']]
    require(sha(d / 'device/maps.txt') == result['mapsSha256'] and sha(d / 'device/smaps.txt') == result['smapsSha256']
            and 'libtripoderselfhook.so' in maps and not any(x in maps.lower() for x in ['frida', 'gadget', 'libgum']), 'ABI_MAPS')
    abi = result['abi']
    require(abi['checks'] == 2048 and abi['fullCallerCoverage'] is False, 'ABI_CHECKS_AND_SCOPE')
    concurrency = abi['concurrency']
    require(concurrency['threads'] == 4 and concurrency['cycles'] == 20 and concurrency['errors'] == 0
            and concurrency['calls'] == concurrency['originalBodyCalls'] >= 1000
            and 0 < concurrency['nativeOriginalCalls'] <= concurrency['calls'], 'ABI_CONCURRENCY')
    storage = result['storage']
    require(storage == abi['storage'] and storage['allocations'] == storage['published'] == storage['rootedOutsideDeclaredTables'] == 19
            and storage['retainedSlotRoots'] == 114 and storage['retainedRegistryRoots'] == 1
            and storage['observedContainerRootMoves'] > 0, 'ABI_MOVED_BACKUP_ROOTS')
    root_evidence = verify_storage(storage, maps, args.expected)
    root_evidence['readBarrierAdjustedReads'] = storage['readBarrierAdjustedReads']
    contract = json.loads((d / 'art-storage-contract.json').read_text())
    require(contract['passed'] is True and len(contract['entries']) == 8
            and {'READ_ROOT', 'MARK_ROOT'}.issubset({e['name'] for e in contract['entries']})
            and contract['artSha256'] == platform['binaries']['/apex/com.android.art/lib64/libart.so']
            and contract['contractSha256'] == sha(ROOT / 'android/self-hook/native/art_storage_contract.h')
            and contract['checkerSha256'] == sha(ROOT / 'android/self-hook/tests/check_art_storage_contract.py'), 'ROOT_READ_BARRIER_CONTRACT')
    gc = abi['movingGc']
    stress = abi['concurrentRoots']
    require(stress['observations'] == 400 and stress['publishedPerObservation'] == 19
            and stress['allocationBatches'] > 0 and stress['liveBatchBytes'] == 2097152
            and stress['gcAfter'] > stress['gcBefore'] and stress['workerJoined'] is True
            and 0 <= stress['readBarrierAdjustments'] <= storage['readBarrierAdjustedReads'], 'CONCURRENT_GC_ROOT_OBSERVATION')
    require(len(gc['before']) == len(gc['after']) == 4 and all(x > 0 for x in gc['before'] + gc['after'])
            and sum(a != b for a, b in zip(gc['before'], gc['after'])) == gc['movedReferences'] > 0
            and int(gc['gcAfter']) > int(gc['gcBefore']) and 1 <= gc['rounds'] <= 16
            and gc['sameReferenceReturned'] is True and gc['disabledInFlight'] is True, 'ACTUAL_MOVING_GC')
    graph = abi['callerIndex']
    require(graph['callers'] == ['Ldev/tripoder/selfhookprobe/AbiCaller;->integer(I)I']
            and graph['dynamicSites'] > 0 and graph['externalSites'] > 0 and graph['completeRuntimeCoverage'] is False, 'CALLER_SCOPE')
    with zipfile.ZipFile(build['apk']) as apk:
        require(hashlib.sha256(apk.read('classes.dex')).hexdigest() == graph['dexSha256'], 'CALLER_APK_DEX_IDENTITY')
    before,after=[abi[k] for k in ('callerObservationBefore','callerObservationAfter')]
    require(0<before['sequence']<after['sequence'] and before['analysisDigest']==after['analysisDigest']
            and re.fullmatch('[0-9a-f]{64}',before['analysisDigest']),'CALLER_NATIVE_SNAPSHOT_ORDER')
    for snapshot in (before,after):
        require(snapshot['dexSha256']==graph['dexSha256'] and snapshot['completeLoadedInventory'] is snapshot['completeRuntimeCoverage'] is False
                and len(snapshot['methods'])==2,'NATIVE_OBSERVATION_SCOPE')
        require([m['source'] for m in snapshot['methods']]==[graph['target'],*graph['callers']],'NATIVE_OBSERVATION_IDENTITY')
        require(all(m['dexIndex']==m['sourceIndex'] and int(m['method'],16)%8==0 and 0<int(m['owner'],16)<=0xffffffff
                    and int(m['quick'],16)>0 for m in snapshot['methods']),'NATIVE_OBSERVATION_METADATA')
    require([m['method'] for m in before['methods']]==[m['method'] for m in after['methods']]
            and all(int(m['quick'],16)==int(abi['states'][0]['state']['interpreterBridge'],16) for m in after['methods']),
            'NATIVE_OBSERVATION_STABLE_METHODS_AND_INTERPRETER')
    classifier = module('abi_compile', 'verify-self-art-compilation.py')
    require(len(abi['roundtrips']) == 10, 'ABI_ROUNDTRIP_COUNT')
    compiled = []
    for case in abi['roundtrips']:
        require(case['calls'] == 100 and case['native']['originals'] == 100, 'ROUNDTRIP_ORIGINAL_COUNT')
        target, caller = [classifier.entry(case[name], maps) for name in ['targetBefore', 'callerBefore']]
        if args.expected == 'cold':
            require(target['kind'] in ['jit', 'aot'] and caller['kind'] in ['jit', 'aot'], 'COLD_COMPILED_ABI')
        else:
            require(target['kind'] == caller['kind'] == args.expected, 'OBSERVED_COMPILED_ABI')
        compiled.append({'name': case['name'], 'target': target, 'caller': caller})
    expected = dict(bool='ZZ', tiny='BB', small='SS', character='CC', integer='II', wide='JJ', single='FF', decimal='DD',
                    reference='LL', array='LL', nothing='VL', instance='JJL', monitor='LL', monitorStatic='JJ',
                    mixed='LIJDLFJDLIJDFLIJDFL', failure='LL', recursive='JI', lazy='JJ', holding='LL')
    states = {item['name']: item for item in abi['states']}
    require(set(states) == set(expected) and len(abi['states']) == 19, 'ABI_SIGNATURE_SET')
    mapping = module('abi_mapping', 'verify-self-hook-probe.py').mapping
    words = [0xd503245f, 0x580000a0, 0x580000d0, 0xd61f0200, 0xd503201f, 0xd503201f]
    base = int(abi['roundtrips'][0]['targetBefore']['base'], 16)
    for name, shorty in expected.items():
        item = states[name]
        s = item['state']
        require(s['ready'] is True and s['active'] is False and s['backupRooted'] is True and s['stage'] == 'DISABLED'
                and s['shorty'] == shorty and s['transitions'] == (46 if name == 'integer' else 2)
                and s['knownCallers'] == (1 if name == 'integer' else 0)
                and s['retainedRoots'] == (4 if name == 'integer' else 3) and s['retainedRelayPages'] == 1
                and s['fullCallerCoverage'] is False, 'ENTRY_LIFETIME:' + name)
        require(s['instance'] == (name in ['instance', 'monitor', 'holding'])
                and s['originals'] == (101 + concurrency['nativeOriginalCalls'] if name == 'integer' else 100 if name in list(expected)[:10] else 5 if name == 'recursive' else 1), 'ORIGINAL_ABI_COUNTS:' + name)
        require(s['targetFlags'] & 0x100 == s['backupFlags'] & 0x100 == 0
                and int(s['targetQuick'], 16) == int(s['interpreterBridge'], 16) == base + 0x2bc880, 'MANAGED_ENTRY_TYPE')
        require(s['targetFlags'] & 0x02000000 == 0 and s['compilationOwnership'] == 'process-interpreter-lease'
                and s['processInterpreterOwned'] is True and s['observedInstrumentationLevel']==2
                and s['processPolicyClaims']==1, 'PROCESS_INTERPRETER_LEASE')
        require(s['writerOwnerRegistered'] is True and s['desiredEntry']==s['targetQuick']
                and s['writerInterpositionInstalled'] is True and s['completeEntryWriterClosure'] is False,'CENTRAL_WRITER_SCOPE')
        relay = int(s['relay'], 16)
        mapped = mapping(maps, relay)
        require(mapped['mode'] == 'r-xp' and mapped['inode'] == 0, 'RELAY_MAPPING')
        selected, guarded = False, False
        for line in smaps.splitlines():
            header = re.match(r'^([0-9a-f]+)-([0-9a-f]+)\s', line)
            if header:
                selected = int(header[1], 16) <= relay and relay + 4096 <= int(header[2], 16)
            elif selected and line.startswith('VmFlags:'):
                guarded = 'bt' in line.split() and 'mt' not in line.split()
        require(guarded, 'RELAY_BTI')
        code = struct.pack('<6I2Q', *words, int(s['helperMethod'], 16), int(s['interpreterBridge'], 16))
        code += struct.pack('<I', 0xd503201f) * ((4096 - len(code)) // 4)
        file = d / 'device' / ('relay-' + name + '.bin')
        require(file.read_bytes() == code and sha(file) == item['relaySha256'], 'COMPLETE_RELAY_BYTES')
    require(len(abi['helpers']) == 19 and {h['method'] for h in abi['helpers']} == set(expected), 'HELPER_SET')
    for helper in abi['helpers']:
        require(helper['file'] == 'helper-' + helper['method'] + '.dex', 'HELPER_FILE')
        file = d / 'device' / helper['file']
        require(sha(file) == helper['sha256'], 'HELPER_DIGEST')
        decoded = subprocess.run([str(args.sdk / 'build-tools/37.0.0/dexdump'), '-d', str(file)], capture_output=True, text=True, check=True)
        require('Ldev/tripoder/selfhook/ManagedHook;.dispatch' in decoded.stdout, 'INDEPENDENT_HELPER_DECODE')
    oracle = json.loads((d / 'helper-dex-oracle.json').read_text())
    require(oracle['passed'] is True and oracle['checks'] == 79 and len(oracle['decoded']) == 36
            and oracle['slotCapacity']==4096 and oracle['independentlyDecodedSlots']==[63,64,4095], 'HOST_HELPER_ORACLE')
    closure = json.loads((d / 'caller-closure-oracle.json').read_text())
    require(closure['passed'] is True and closure['checks'] == 17 and closure['completeRuntimeCoverage'] is False, 'CALLER_ORACLE')
    if args.expected == 'aot':
        log = (d / 'aot-compile.txt').read_text()
        require('actualCompilerFilter=speed' in log and 'Final Status: PERFORMED' in log, 'AOT_COMPILATION_RECEIPT')
    report = {'passed': True, 'phase': result['phase'], 'runId': result['runId'], 'kind': args.expected,
              'apkSha256': build['apkSha256'], 'buildId': build['buildId'], 'sourceDigest': build['sourceDigest'],
              'checks': abi['checks'], 'signatures': len(expected), 'movingGc': gc, 'storage': storage, 'rootEvidence': root_evidence, 'concurrency': concurrency,
              'compiled': compiled, 'concurrentRoots': stress, 'callerIndex': graph, 'resultSha256': sha(path), 'verifierSha256': sha(Path(__file__)),
              'scope': 'Owned managed Java ABI and actual moving roots; full runtime caller coverage and product migration remain incomplete'}
    with (d / 'verification.json').open('x') as out:
        out.write(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('compiled', 'storage', 'callerIndex', 'rootEvidence')}))


if __name__ == '__main__':
    main()
