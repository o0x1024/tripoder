#!/usr/bin/env python3
"""Bind current H1/H2a evidence to sources, APK and caller run identity."""
import argparse
import hashlib
import json
from pathlib import Path
import uuid
import zipfile
from verify_self_libc import verify_libc

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def mapping(text, address):
    for line in text.splitlines():
        fields = line.split(maxsplit=5)
        start, end = (int(value, 16) for value in fields[0].split('-'))
        if start <= address and address + 4096 <= end:
            return {'mode': fields[1], 'offset': int(fields[2], 16) + address - start,
                    'device': fields[3], 'inode': int(fields[4]),
                    'path': fields[5] if len(fields) == 6 else ''}
    raise RuntimeError('PAGE_MAPPING_MISSING')


def verify_pages(directory, result, owned):
    pages = owned['pages']
    require(pages['model'] == 'owned-anonymous-page-v1', 'PAGE_MODEL')
    require(pages['stagedRW'] == 105 and pages['stagedRX'] == pages['published'] == 104
            and pages['restored'] == 3 and pages['prepareErrno'] == 0
            and pages['injectedPrepareRejections'] == 1, 'PAGE_PUBLICATION')
    require(pages['liveStagingPages'] == pages['liveAnonymousPages'] == 0
            and pages['peakStagingPages'] == 3, 'PAGE_RECLAMATION')
    target, trampoline = int(owned['target'], 16), int(owned['trampoline'], 16)
    require(trampoline == target + 4096, 'PAGE_GEOMETRY')
    snapshots = {}
    for stage in ('prepared', 'active', 'disabled', 'restored'):
        path = directory / 'device' / ('maps-' + stage + '.txt')
        require(sha(path) == result['pageSnapshots'][stage], 'SNAPSHOT_HASH:' + stage)
        snapshots[stage] = [mapping(path.read_text(), address)
                            for address in (target, trampoline, trampoline + 4096)]
    source = snapshots['prepared'][0]
    require(source['mode'] == 'r-xp' and source['inode'] > 0
            and source['path'].endswith('/libtripoderhookfixture.so'), 'SOURCE_PAGE')
    for stage, rows in snapshots.items():
        for index, row in enumerate(rows):
            require(row['mode'] == 'r-xp', 'LIVE_PAGE_PERMISSIONS:' + stage)
            file_page = index == 2 or stage == 'restored' or (stage == 'prepared' and index == 0)
            if file_page:
                expected = {**source, 'offset': source['offset'] + index * 4096}
                require(row == expected, 'FILE_PAGE_RESTORED:' + stage)
            else:
                require(row['inode'] == 0 and row['device'] == '00:00' and row['path'] == '',
                        'ANONYMOUS_LIVE_PAGE:' + stage)


def verify_noncooperative(directory, result):
    phase = result['noncooperative']
    require(phase['passed'] is True and phase['checks'] == 19 and phase['failures'] == phase['callErrors'] == 0,
            'NONCOOPERATIVE_CHECKS')
    require(phase['cycles'] == 50 and phase['commits'] == 100 and phase['admissionRejections'] == 1
            and phase['plainCalls'] > 0 and phase['hookedCalls'] > 0, 'NONCOOPERATIVE_CALLS')
    require(phase['timeoutTargetUnchanged'] is True and phase['commitTargetUnchanged'] is True
            and phase['disabledWithInFlightCallback'] is True, 'NONCOOPERATIVE_FAILURE_AND_LIFETIME')
    require(phase['publishedPages'] == 101 and phase['restoredPages'] == 2
            and phase['liveStagingPages'] == phase['liveAnonymousPages'] == 0
            and phase['unloaded'] is True and phase['fileMappingsRestored'] is True, 'NONCOOPERATIVE_RECLAMATION')
    require(phase['directNativeCalls'] is True and phase['dedicatedOwnedPagesOnly'] is True
            and phase['genericNativeHook'] is False and phase['btiEnforcementTested'] is False, 'NONCOOPERATIVE_SCOPE')
    target, trampoline = int(phase['target'], 16), int(phase['trampoline'], 16)
    require(trampoline == target + 4096, 'NONCOOPERATIVE_PAGE_GEOMETRY')
    rows = {}
    for stage in ('parked-active', 'parked-restored'):
        path = directory / 'device' / ('maps-' + stage + '.txt')
        require(sha(path) == result['pageSnapshots'][stage], 'NONCOOPERATIVE_MAPS_HASH')
        rows[stage] = [mapping(path.read_text(), address) for address in (target, trampoline, trampoline + 4096)]
    source = rows['parked-restored'][0]
    require(source['mode'] == 'r-xp' and source['inode'] > 0
            and source['path'].endswith('/libtripoderhookfixture.so'), 'NONCOOPERATIVE_SOURCE')
    for index, row in enumerate(rows['parked-restored']):
        require(row == {**source, 'offset': source['offset'] + index * 4096}, 'NONCOOPERATIVE_FILE_RESTORATION')
    for index, row in enumerate(rows['parked-active']):
        require(row['mode'] == 'r-xp', 'NONCOOPERATIVE_RX')
        if index == 2:
            require(row == rows['parked-restored'][index], 'NONCOOPERATIVE_RELAY_UNCHANGED')
        else:
            require(row['inode'] == 0 and row['device'] == '00:00' and row['path'] == '', 'NONCOOPERATIVE_ANONYMOUS')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    args = parser.parse_args()
    run_id = str(uuid.UUID(args.run_id))
    directory = args.evidence
    build = json.loads((directory / 'build-receipt.json').read_text())
    host = json.loads((directory / 'host-report.json').read_text())
    dex_host = json.loads((directory / 'dex-host-report.json').read_text())
    park_audit = json.loads((directory / 'park-binary-audit.json').read_text())
    result = json.loads((directory / 'device/result.json').read_text())
    expected_platform = json.loads((ROOT / 'android/self-hook-probe/platform.json').read_text())
    require(result['runId'] == run_id and result['passed'] is True, 'DEVICE_RUN_NOT_PASSED')
    require(result['package'] == 'dev.tripoder.selfhookprobe' and result['debuggable'] is False,
            'WRONG_PROCESS_CONTRACT')
    require(result['uid'] >= 10000 and result['pid'] > 0, 'PROCESS_IDENTITY')
    require(result['apkSha256'] == build['apkSha256'] == sha(Path(build['apk'])), 'APK_IDENTITY')
    require(result['build']['sourceDigest'] == build['sourceDigest'], 'SOURCE_IDENTITY')
    require(result['build']['buildId'] == build['buildId'], 'BUILD_IDENTITY')
    require(result['nativeSha256'] == result['build']['nativeSha256'] == build['nativeSha256'], 'NATIVE_IDENTITY')
    require(park_audit['passed'] is True and park_audit['nativeSha256'] == build['nativeSha256']
            and park_audit['disassemblySha256'] == sha(directory / 'park-binary-audit.txt'), 'PARK_BINARY_AUDIT')
    require(result['fixtureSha256'] == result['build']['fixtureSha256'] == build['fixtureSha256'], 'FIXTURE_IDENTITY')
    require(result['fixtureSha256After'] == result['fixtureSha256'], 'FIXTURE_FILE_MODIFIED')
    for name, digest in build['sources'].items():
        require(sha(ROOT / name) == digest, 'SOURCE_CHANGED:' + name)
    identity = hashlib.sha256(json.dumps(build['sources'], sort_keys=True).encode()).hexdigest()
    require(identity == build['sourceDigest'], 'SOURCE_MANIFEST_DIGEST')
    for name, digest in host['sourceHashes'].items():
        require(build['sources'][name] == digest, 'HOST_SOURCE_MISMATCH:' + name)
    require(dex_host['passed'] is True and dex_host['checks'] == 14 and dex_host['apkSha256'] == build['apkSha256'], 'DEX_HOST_CHECKS')
    for name, digest in dex_host['sourceHashes'].items():
        require(build['sources'][name] == digest, 'DEX_HOST_SOURCE_MISMATCH:' + name)
    require(result['platformGatePassed'] is True and result['platform']['process64Bit'] is True, 'PLATFORM_GATE')
    for name, value in expected_platform.items():
        require(result['platform'][name] == value, 'PLATFORM_IDENTITY:' + name)
    maps = directory / 'device/maps.txt'
    require(sha(maps) == result['mapsSha256'], 'MAPS_IDENTITY')
    mapped = maps.read_text().lower()
    require(result['forbiddenMappings'] == [] and
            not any(word in mapped for word in ('frida', 'gadget', 'libgum')), 'FORBIDDEN_MAPPING')
    require('libtripoderselfhook.so' in mapped, 'NATIVE_MAPPING_MISSING')
    core = result['native']['core']
    execution = result['native']['execution']
    require(core['failures'] == host['core']['failures'] == 0 and
            core['checks'] == host['core']['checks'] and core['checks'] > 0, 'CORE_CHECKS')
    require(host['oracleChecks'] == 38 and len(host['oracle']) == 38, 'ASSEMBLER_ORACLE')
    require(execution['checks'] == 16 and execution['failures'] == 0 and execution['errno'] == 0
            and execution['executed'] is True and execution['rwObserved'] is True
            and execution['rxObserved'] is True, 'SYNTHETIC_EXECUTION')
    for field in ('javaHook', 'btiEnforcementTested'):
        require(result['native'][field] is False, 'OVERSTATED_CAPABILITY:' + field)
    owned = result['native']['ownedHook']
    require(owned['checks'] > 0 and owned['failures'] == 0 and owned['callbackFailures'] == 0
            and owned['executed'] is True and owned['liveElfEntryPatched'] is True, 'OWNED_HOOK')
    require(owned['cycles'] == 50 and owned['hookedCalls'] > 0 and owned['plainCalls'] > 0
            and owned['workerCalls'] == owned['hookedCalls'] + owned['plainCalls'], 'CONCURRENT_CALLS')
    require(owned['unloaded'] is True and owned['reloaded'] is True
            and owned['secondGeneration'] > owned['firstGeneration'] > 0
            and 'libtripoderhookfixture.so' not in mapped, 'MODULE_LEASE')
    require(owned['cooperativeDomainOnly'] is True and owned['arbitraryThreadsStopped'] is False
            and owned['btiEnforcementTested'] is False and owned['javaHook'] is False, 'OWNED_SCOPE')
    verify_pages(directory, result, owned)
    verify_noncooperative(directory, result)
    libc_summary = verify_libc(directory, result, build, require, mapping)
    parked = result['threadPark']
    require(parked['passed'] is True and parked['checks'] == 15 and parked['failures'] == 0
            and parked['cycles'] == 20 and parked['churnCycles'] > 0
            and parked['churnCycles'] + parked['churnRejected'] == 20, 'THREAD_PARK_CYCLES')
    require(parked['signal'] == 60 and parked['createdChildren'] > 0 and parked['workerCalls'] > 0
            and parked['peakParkedThreads'] >= 4
            and parked['capturedContexts'] >= 4 * (21 + parked['churnCycles']), 'THREAD_PARK_CONTEXTS')
    require(parked['timeoutRejected'] is True and parked['timeoutParkedThreads'] >= 4
            and parked['lateSignals'] == 1 and parked['lateSignalRecovery'] is True, 'THREAD_PARK_CANCEL')
    require(int(parked['timeoutSignalMask'], 16) & (1 << 59), 'THREAD_PARK_TIMEOUT_SIGNAL_MASK')
    if parked['churnRejected']:
        require(parked['churnPendingTid'] > 0 and int(parked['churnSignalMask'], 16) & (1 << 59), 'CHURN_REJECTION_SIGNAL_MASK')
    require(parked['processLifetimeReservation'] is True and parked['codePagesModified'] is False
            and parked['noncooperativeHook'] is False and parked['genericJavaHook'] is False, 'THREAD_PARK_SCOPE')
    art = result['art']
    locked = art['lockedMethod']
    require(art['observationPassed'] is True and art['checks'] == 15 and art['failures'] == [], 'ART_OBSERVATIONS')
    require(locked['bound'] is True and locked['threadPresent'] is True and locked['methodReadable'] is True
            and locked['suspendResumeCycles'] == 10 and art['artMethodFieldReadable'] is True, 'ART_ACCESS')
    require(locked['method'] == art['artMethodAddress'] and locked['base'] == art['access']['mappedBase'], 'ART_IDENTITY')
    require(art['internalArtCalls'] is True and art['methodMemoryModified'] is False and art['javaHook'] is False,
            'ART_OBSERVATION_SCOPE')
    binding = result['artNative']
    state = binding['binding']
    require(binding['passed'] is True and binding['failures'] == [] and binding['checks'] == 62, 'ART_NATIVE_CHECKS')
    require(binding['cycles'] == 20 and binding['workerCalls'] > 0 and binding['workerErrors'] == 0, 'ART_NATIVE_CONCURRENCY')
    require(binding['hotCalls'] == binding['hotIntercepted'] == 1000000 and binding['hotFailures'] == 0, 'ART_NATIVE_HOT_CALLS')
    require(state['enabled'] is False and state['transitions'] == 42 and state['replacements'] >= 1000000
            and state['originalExceptions'] == 6 and state['retainedGlobalRefs'] == 4
            and state['processLifetimeSlot'] is True, 'ART_NATIVE_LIFETIME')
    require(binding['nativeMethodHook'] is True and binding['genericJavaHook'] is False, 'ART_NATIVE_SCOPE')
    managed = result['artManaged']
    storage = managed['storage']
    require(storage['published'] == storage['allocations'] == storage['rootedOutsideDeclaredTables'] == 2
            and storage['retainedSlotRoots'] == 12 and storage['retainedRegistryRoots'] == 1, 'MANAGED_BACKUP_STORAGE')
    managed_state = managed['binding']
    require(managed['passed'] is True and managed['checks'] == 25 and managed['failures'] == [], 'ART_MANAGED_CHECKS')
    require(managed['hotCalls'] == managed['hotIntercepted'] == 10000 and managed['hotErrors'] == 0, 'ART_MANAGED_CALLS')
    require(managed_state['ready'] is True and managed_state['enabled'] is False and managed_state['transitions'] == 2
            and managed_state['callbacks'] == managed_state['originals'] == 20004 + managed['compiledCallerBeforeDeopt']['intercepted']
            and managed_state['retainedGlobalRefs'] == 4 and managed_state['slots'] == 2
            and managed_state['processLifetimeSlot'] is True, 'ART_MANAGED_LIFETIME')
    require(managed['ownedManagedMethod'] is True and managed['genericJavaHook'] is False
            and managed['inliningVerified'] is False and managed['gcMovementProven'] is False, 'ART_MANAGED_SCOPE')
    deopt = managed['compiledCallerAfterDeopt']
    require(deopt['calls'] == deopt['intercepted'] == 10000 and deopt['errors'] == 0, 'ART_MANAGED_CALLER_DEOPT')
    index = managed['callerIndex']
    with zipfile.ZipFile(build['apk']) as apk:
        require(index['dexSha256'] == hashlib.sha256(apk.read('classes.dex')).hexdigest(), 'CALLER_DEX_IDENTITY')
    require(index['completeRuntimeCoverage'] is False and index['unresolvedDispatchSites'] > 0, 'CALLER_INDEX_SCOPE')
    require(len(index['callers']) == managed['callerDeoptimizedCount'] == managed['callerRetainedGlobalRefs'] == 1, 'CALLER_INDEX_SELECTION')
    caller = index['callers'][0]
    require(caller['method'] == 'Ldev/tripoder/selfhookprobe/ManagedCaller;->call(I)I'
            and caller['dexIndex'] == managed['callerBefore']['words'][2] and caller['dexSha256'] == index['dexSha256'], 'CALLER_RUNTIME_DEX_IDENTITY')
    reflection = result['reflectionView']
    reflection_state = reflection['binding']
    require(reflection['passed'] is True and reflection['checks'] == 76 and reflection['failures'] == [], 'REFLECTION_VIEW_CHECKS')
    require(reflection['hotInvocation'] == 'Method.invoke' and reflection['hotCalls'] == reflection['hotMapped'] == 1000000
            and reflection['hotSetMapped'] == 1000000 and reflection['hotPhysicalUnchanged'] is True
            and reflection['hotErrors'] == 0 and reflection['cycles'] == 20
            and reflection['workerCalls'] > 0 and reflection['workerErrors'] == 0, 'REFLECTION_VIEW_CALLS')
    require(reflection_state['ready'] is True and reflection_state['enabled'] is False
            and reflection_state['transitions'] == 42 and reflection_state['retainedGlobalRefs'] == 9
            and reflection_state['processLifetimeSlot'] is True
            and reflection_state['mappedSets'] == 1000000 + 10000 * reflection['primeRounds'] + 4
            and reflection_state['denials'] == 4 and reflection_state['originalExceptions'] >= 9, 'REFLECTION_VIEW_LIFETIME_AND_ACCESS')
    require(reflection['nativeReflectionView'] is True and reflection['realActivityThreadConfigured'] is False
            and reflection['genericJavaHook'] is False and reflection['gcMovementProven'] is False, 'REFLECTION_VIEW_SCOPE')
    require(set(build['nativeDependencies']) <= {'libc.so', 'libdl.so', 'libm.so'}
            and build['fixtureDependencies'] == [] and build['fridaInputs'] == []
            and build['productFridaRemoved'] is False, 'BUILD_SCOPE')
    summary = {'schema': 1, 'passed': True, 'runId': run_id, 'buildId': build['buildId'],
               **libc_summary,
               'hostRunId': host['runId'], 'apkSha256': result['apkSha256'],
               'sourceDigest': identity, 'hostCoreChecks': core['checks'], 'assemblerOracleChecks': 38,
               'deviceCoreChecks': core['checks'], 'deviceExecutionChecks': 16,
               'ownedHookChecks': owned['checks'], 'concurrentCycles': owned['cycles'],
               'workerCalls': owned['workerCalls'],
               'threadParkChecks': parked['checks'], 'threadParkCycles': 21 + parked['churnCycles'],
               'threadParkChurnRejections': parked['churnRejected'],
               'noncooperativeHookChecks': result['noncooperative']['checks'],
               'noncooperativeHookCycles': result['noncooperative']['cycles'],
               'noncooperativeWorkerCalls': result['noncooperative']['plainCalls'] + result['noncooperative']['hookedCalls'],
               'pagePublication': owned['pages'], 'pageSnapshots': result['pageSnapshots'],
               'artObservationChecks': art['checks'], 'artNativeChecks': binding['checks'],
               'artNativeWorkerCalls': binding['workerCalls'], 'artNativeHotCalls': binding['hotCalls'],
               'artManagedChecks': managed['checks'], 'artManagedCalls': managed['hotIntercepted'],
               'reflectionViewChecks': reflection['checks'], 'reflectionViewHotCalls': reflection['hotMapped'],
               'reflectionViewWorkerCalls': reflection['workerCalls'],
               'dexHostChecks': dex_host['checks'], 'indexedStaticCallers': len(index['callers']),
               'scope': 'H1/H2a owned ELF, fixed ART, native Field.get and owned managed int(int); no generic hook or product migration',
               'resultSha256': sha(directory / 'device/result.json'),
               'verifierSha256': sha(Path(__file__).resolve())}
    with (directory / 'verification.json').open('x') as output:
        output.write(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
