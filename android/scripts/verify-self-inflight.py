#!/usr/bin/env python3
"""Distinguish an owned AOT counterexample from the early-entry policy proof."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import uuid
from verify_self_interpreter import verify as verify_contracts

ROOT=Path(__file__).resolve().parents[2]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def need(value,reason):
    if not value:
        raise RuntimeError(reason)

def address(snapshot):
    words=snapshot['words']
    return words[6]|(words[7]<<32)

def verify(directory,run_id,early):
    result_file=directory/'device/result.json'
    result=json.loads(result_file.read_text())
    build=json.loads((directory/'build-receipt.json').read_text())
    plan=json.loads((directory/'run-plan.json').read_text())
    need(result['passed'] is True and result['phase']=='H3m-inflight-boundary'
         and result['runId']==str(uuid.UUID(run_id))==plan['runId']
         and result['artCompilation']==plan['expected']=='aot'
         and plan['activity']==('startup' if early else 'inflight'),'RUN_IDENTITY')
    need(result['package']=='dev.tripoder.selfhookprobe' and result['debuggable'] is False
         and result['uid']>=10000 and result['pid']>0,'OWNED_NON_DEBUGGABLE_PROCESS')
    need(result['apkSha256']==build['apkSha256']==plan['apkSha256']==sha(Path(build['apk']))
         and result['nativeSha256']==build['nativeSha256']
         and result['build']['buildId']==build['buildId']
         and result['build']['sourceDigest']==build['sourceDigest'],'BUILD_IDENTITY')
    for name,digest in build['sources'].items():
        need(sha(ROOT/name)==digest,'SOURCE_CHANGED:'+name)
    need(hashlib.sha256(json.dumps(build['sources'],sort_keys=True).encode()).hexdigest()==build['sourceDigest'],'SOURCE_MANIFEST')
    platform=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())
    need(result['platformGatePassed'] is True and all(result['platform'][k]==v for k,v in platform.items()),'PLATFORM')
    verify_contracts(directory,platform)
    for name in ('maps','smaps'):
        need(result[name+'Sha256']==sha(directory/('device/'+name+'.txt')),'MAPS_IDENTITY')
    maps=(directory/'device/maps.txt').read_text()
    need(not any(word in maps.lower() for word in ('frida','gadget','libgum')),'UNEXPECTED_BACKEND')
    compile_proof=json.loads((directory/'aot-compile.json').read_text())
    need(compile_proof['apkSha256']==build['apkSha256'] and compile_proof['returncode']==0
         and 'actualCompilerFilter=speed' in compile_proof['stdout']
         and 'Final Status: PERFORMED' in compile_proof['stdout'],'AOT_COMPILE_PROOF')
    spec=importlib.util.spec_from_file_location('inflight_compilation',ROOT/'android/scripts/verify-self-art-compilation.py')
    compilation=importlib.util.module_from_spec(spec);spec.loader.exec_module(compilation)
    test=result['inflight']
    for key in ('callerBefore','targetBefore'):
        need(compilation.entry(test[key],maps)['kind']=='aot','ACTUAL_AOT_ENTRY:'+key)
    need(test['earlyPolicy'] is early and test['policyBefore']['owned'] is False,'POLICY_START')
    frame_policy=test['policyBeforeFrame'];after=test['policyAfter']
    need(frame_policy['owned'] is early and frame_policy['explicitInitialization'] is early
         and frame_policy['claims']==int(early),'POLICY_PRECEDES_FRAME')
    need(after['owned'] is True and after['explicitInitialization'] is early and after['claims']==1
         and after['observedLevel']==2 and after['preexistingCompiledFramesCovered'] is False
         and after['fullCallerCoverage'] is False,'POLICY_SCOPE')
    enabled=test['enabled'];state=test['after']
    for item in (enabled,state):
        need(item['active'] is True and item['writerInterpositionInstalled'] is True
             and item['targetQuick']==item['desiredEntry']==item['relay']
             and item['completeEntryWriterClosure'] is item['fullCallerCoverage'] is False,'RELAY_OWNERSHIP')
    expected_entry=int(enabled['interpreterBridge'],16) if early else address(test['callerBefore'])
    need(address(test['callerBeforeFrame'])==expected_entry,'ACTUAL_FRAME_ENTRY')
    need(test['freshValue']==test['expectedHookedValue']==110 and test['bodyCalls']==2
         and enabled['originals']==0 and state['originals']==(2 if early else 1)
         and test['resumedValue']==(110 if early else 10) and test['resumedCallCovered'] is early
         and test['productAccepted'] is False,'FRAME_BOUNDARY_VALUES_AND_ORIGINALS')
    return dict(passed=True,phase=result['phase'],runId=run_id,apkSha256=build['apkSha256'],
                buildId=build['buildId'],sourceDigest=build['sourceDigest'],earlyPolicy=early,
                resumedValue=test['resumedValue'],freshValue=test['freshValue'],originalCalls=state['originals'],
                counterexampleVerified=not early,preexistingAotMigrationAccepted=False,productAccepted=False,
                resultSha256=sha(result_file),verifierSha256=sha(Path(__file__)),
                scope='New frame entered after explicit policy initialization' if early else 'Old AOT frame bypass reproduced; diagnostic only')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--run-id',required=True)
    parser.add_argument('--mode',choices=['late','early'],required=True)
    args=parser.parse_args()
    report=verify(args.evidence,args.run_id,args.mode=='early')
    with (args.evidence/'verification.json').open('x') as output:
        output.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
