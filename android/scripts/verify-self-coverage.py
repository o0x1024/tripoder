#!/usr/bin/env python3
"""Independent acceptance of owned loaded-class observation, never full coverage."""
import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import uuid
import zipfile
from verify_self_interpreter import verify as verify_interpreter
from verify_self_entry_evidence import verify as verify_entry_artifacts

ROOT = Path(__file__).resolve().parents[2]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def require(value, reason):
    if not value: raise RuntimeError(reason)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=Path,required=True);parser.add_argument('--run-id',required=True)
    args=parser.parse_args();d=args.evidence
    build=json.loads((d/'build-receipt.json').read_text());path=d/'device/result.json';result=json.loads(path.read_text())
    require(result['passed'] is True and result['phase']=='H3j-loaded-classes' and result['runId']==str(uuid.UUID(args.run_id)), 'COVERAGE_RUN')
    require(result['package']=='dev.tripoder.selfhookprobe' and result['debuggable'] is False and result['uid']>=10000 and result['pid']>0,'OWNED_PROCESS')
    require(result['apkSha256']==build['apkSha256']==sha(Path(build['apk'])) and result['nativeSha256']==build['nativeSha256']
            and result['build']['buildId']==build['buildId'] and result['build']['sourceDigest']==build['sourceDigest'],'BUILD_IDENTITY')
    for name,digest in build['sources'].items(): require(sha(ROOT/name)==digest,'SOURCE_CHANGED:'+name)
    require(hashlib.sha256(json.dumps(build['sources'],sort_keys=True).encode()).hexdigest()==build['sourceDigest'],'SOURCE_MANIFEST')
    platform=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())
    verify_interpreter(d,platform)
    require(result['platformGatePassed'] is True and all(result['platform'][k]==v for k,v in platform.items()),'PLATFORM')
    maps=(d/'device/maps.txt').read_text();require(sha(d/'device/maps.txt')==result['mapsSha256'] and 'libtripoderselfhook.so' in maps
        and not any(x in maps.lower() for x in ('frida','gadget','libgum')),'MAPS')
    c=result['coverage'];small=c['small']
    future=c['lateHooks']
    require(future['targetActiveBeforeLoaderCreation'] is True and future['knownCallersAtPrepare']==0
        and future['compiledCalls']==1000 and future['primingCalls']>=10000 and future['primingCalls']%10000==0
        and future['totalIntercepted']==future['intercepted']==future['primingCalls']+1002
        and future['originalBodyCalls']==future['totalIntercepted']+1 and future['fullFutureCoverage'] is False,'OWNED_LATE_CALLERS')
    spec=importlib.util.spec_from_file_location('late_compile',ROOT/'android/scripts/verify-self-art-compilation.py')
    classifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(classifier)
    require(classifier.entry(future['compiledCaller'],maps)['kind']=='runtime','LATE_INTERPRETER_ENTRY')
    require(future['processBefore']['owned'] is future['processPrepared']['owned'] is False
        and future['processBefore']['claims']==future['processPrepared']['claims']==0,'PREPARE_HAS_NO_PROCESS_POLICY_SIDE_EFFECT')
    for policy in [future['processInterpreter'],future['processAfterDisable']]:
        require(policy['bound'] is policy['owned'] is policy['processLifetimeLease'] is True and policy['observedLevel']==2
            and policy['claims']==1 and policy['client']=='tripoder-managed-process-interpreter'
            and policy['policy']=='process-interpreter-lease','CORE_INTERPRETER_LIFETIME_LEASE')
    require(future['activeState']['active'] is True and future['activeState']['knownCallers']==0
        and future['activeState']['originals']==future['totalIntercepted'] and future['disabledState']['active'] is False,'LATE_OWNER_LIFETIME')
    for state in [future['activeState'],future['disabledState']]:
        require(state['compilationOwnership']=='process-interpreter-lease' and state['processInterpreterOwned'] is True
            and state['observedInstrumentationLevel']==2 and state['processPolicyClaims']==1
            and state['writerInterpositionInstalled'] is True and state['completeEntryWriterClosure'] is False,'LATE_CORE_POLICY')
    targets=c['loaderTargets'];dynamic=targets['dynamic'];framework=targets['framework'];storage=targets['storage']
    require(dynamic['sameNameDifferentLoaders'] is True and dynamic['intercepted']==dynamic['unownedCalls']==1001
        and dynamic['bodyCalls']==2003,'DYNAMIC_TARGET_LOADER_ISOLATION')
    require(framework['declaringClass']=='android.app.ActivityThread' and framework['method']=='currentApplication'
        and framework['intercepted']==1000 and framework['originalIdentityPreserved'] is True
        and framework['physical']['ready'] is True and framework['physical']['checks']>=3,'BOOT_FRAMEWORK_ORIGINAL_AND_PHYSICAL_IDENTITY')
    require(storage['allocations']==storage['published']==storage['rootedOutsideDeclaredTables']==3
        and storage['bootAllocations']==1 and storage['retainedSlotRoots']==17 and storage['retainedRegistryRoots']==1
        and sum(x['bootAllocator'] for x in storage['rootSnapshots'])==1,'ACTUAL_BOOT_AND_DEFINING_LOADER_ALLOCATORS')
    for state in (dynamic['active'],framework['active']):
        require(state['active'] is state['backupRooted'] is state['processInterpreterOwned'] is True
            and state['knownCallers']==0 and state['desiredEntry']==state['targetQuick']==state['relay'],'CUSTOM_AND_BOOT_ACTIVE_ENTRY')
    for state in (dynamic['disabled'],framework['disabled']):
        require(state['active'] is False and state['backupRooted'] is True
            and state['desiredEntry']==state['targetQuick']==state['interpreterBridge'],'CUSTOM_AND_BOOT_DISABLED_ENTRY')
    for flag in ('callerSensitiveCovered','loaderUnloadCovered','productAccepted'):require(targets[flag] is False,'LOADER_UNPROVEN_SCOPE')
    require(targets['unknownBootRejected'] is True,'NO_GENERIC_BOOT_SEMANTIC_ASSUMPTION')
    owner=c['entryOwner']
    smaps=(d/'device/smaps.txt').read_text();require(sha(d/'device/smaps.txt')==result['smapsSha256'],'SMAPS')
    verify_entry_artifacts(d,owner,maps,smaps,True)
    require(small['budget']==1 and small['visited']==2 and small['classes']==0 and small['complete'] is False
            and c['partialBindingRejected'] is True,'NO_PARTIAL_RESULT')
    snapshots=[c['before'],c['loaded'],*c['observations']]
    require(len(c['observations'])==24 and all(s['complete'] is True and s['futureClassPublicationCovered'] is False
        and s['budget']==65536 and 2<=s['classes']==s['visited']<=65536 and s['bootClasses']>0 for s in snapshots),'BOUNDED_ENUMERATIONS')
    require(all(a['sequence']<b['sequence'] for a,b in zip([small,*snapshots],snapshots)),'CAPTURE_SEQUENCE')
    require(c['gcAfter']>c['gcBefore'] and c['allocationBatches']>0,'CONCURRENT_GC')
    require(len(c['classRootsBefore'])==len(c['classRootsAfter'])==2 and all(x>0 for x in c['classRootsBefore']+c['classRootsAfter'])
        and sum(a!=b for a,b in zip(c['classRootsBefore'],c['classRootsAfter']))==c['movedClasses']>0,'ACTUAL_CLASS_MOVEMENT')
    require(c['initializationsBeforeInvoke']==0 and c['initializationsAfterInvoke']==2 and c['distinctDefiningLoaders'] is True,'LOADER_AND_INITIALIZATION')
    require(c['futureClassPublicationCovered'] is c['productAccepted'] is False and c['entryInterpositionInstalled'] is True,'SCOPE')
    with zipfile.ZipFile(build['apk']) as apk:
        require(hashlib.sha256(apk.read('classes.dex')).hexdigest()==c['apkDexSha256'],'APK_DEX')
        require(hashlib.sha256(apk.read('assets/late-caller.dex')).hexdigest()==build['lateDexSha256']==c['lateDexSha256']==sha(d/'device/late-caller.dex'),'LATE_DEX')
    require(c['caller']=='Ldev/tripoder/selfhookprobe/dynamic/LateCaller;->call(I)I','EXACT_CALLER')
    report={'passed':True,'phase':result['phase'],'runId':result['runId'],'apkSha256':build['apkSha256'],'buildId':build['buildId'],
        'sourceDigest':build['sourceDigest'],'coverage':c,'resultSha256':sha(path),'unwindTraceSha256':sha(d/'device/entry-unwind.txt'),'verifierSha256':sha(Path(__file__)),
        'scope':'Owned dynamic classes, moving roots and central entry writer; full future publication and direct-store closure remain unproven'}
    with (d/'verification.json').open('x') as out: out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'passed':True,'runId':result['runId'],'observations':len(c['observations']),'movedClasses':c['movedClasses'],'gcCycles':c['gcAfter']-c['gcBefore']}))
if __name__=='__main__':main()
