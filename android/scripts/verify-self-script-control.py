#!/usr/bin/env python3
"""Verify the four owned script targets through the real ManagedHook backend."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import uuid
import zipfile
from verify_self_interpreter import verify as verify_interpreter

ROOT=Path(__file__).resolve().parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def require(v,r):
    if not v:raise RuntimeError(r)
def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'android/scripts'/file)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True)
    p.add_argument('--expected',choices=['jit','aot'],required=True);p.add_argument('--sdk',type=Path,required=True);args=p.parse_args()
    d=args.evidence;path=d/'device/result.json';r=json.loads(path.read_text());build=json.loads((d/'build-receipt.json').read_text())
    require(r['passed'] is True and r['phase']=='H5-owned-script-control' and r['runId']==str(uuid.UUID(args.run_id))
        and r['artCompilation']==args.expected and r['ownedScopeRequested'] is True,'SCRIPT_RUN')
    require(r['package']=='dev.tripoder.selfhookprobe' and r['uid']>=10000 and r['pid']>0 and r['debuggable'] is False,'OWNED_PROCESS')
    require(r['apkSha256']==build['apkSha256']==sha(Path(build['apk'])) and r['nativeSha256']==build['nativeSha256']
        and r['build']['buildId']==build['buildId'] and r['build']['sourceDigest']==build['sourceDigest'],'BUILD_IDENTITY')
    for name,h in build['sources'].items():require(sha(ROOT/name)==h,'SOURCE_CHANGED:'+name)
    require(hashlib.sha256(json.dumps(build['sources'],sort_keys=True).encode()).hexdigest()==build['sourceDigest'],'SOURCE_MANIFEST')
    platform=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())
    verify_interpreter(d,platform)
    require(r['platformGatePassed'] is True and all(r['platform'][k]==v for k,v in platform.items()),'PLATFORM')
    maps=(d/'device/maps.txt').read_text();smaps=(d/'device/smaps.txt').read_text()
    require(sha(d/'device/maps.txt')==r['mapsSha256'] and sha(d/'device/smaps.txt')==r['smapsSha256']
        and 'libtripoderselfhook.so' in maps and not any(x in maps.lower() for x in ('frida','gadget','libgum')),'MAPS')
    s=r['script'];require(s['checks']>=400 and s['scope']=='owned-four-methods-four-direct-callers' and s['requestedCompilation']==args.expected,'SCRIPT_SCOPE')
    for flag in ('productBackend','fullCallerCoverage','authorityApprovalPath'):require(r[flag] is s[flag] is False,'UNPROVEN_PRODUCT_BOUNDARY')
    for flag in ('originalThrowableIdentity','inFlightOldParameterSnapshot','prepareDidNotPublish','unknownTargetRejected','allRetired'):
        require(s[flag] is True,'SCRIPT_CONTRACT:'+flag)
    require(s['primaryRounds']==100 and s['concurrentThreads']==4 and s['concurrentCalls']==400,'CALLS')
    require(s['physicalBodyDeltas']==dict(integer=505,wide=100,single=100,failure=101),'PHYSICAL_BODY_COUNTS')
    storage=r['storage'];require(storage['allocations']==storage['published']==storage['rootedOutsideDeclaredTables']==4
        and storage['retainedSlotRoots']==24 and storage['retainedRegistryRoots']==1,'ORIGINAL_ROOTS')
    graph=s['callerEvidence'];require(graph['definingLoaderBound'] is True and graph['completeRuntimeCoverage'] is False
        and graph['productBackend'] is False and len(graph['accepted'])==4,'CALLER_SCOPE')
    with zipfile.ZipFile(build['apk']) as apk:require(hashlib.sha256(apk.read('classes.dex')).hexdigest()==graph['dexSha256'],'CALLER_DEX')
    names={'integer':('scriptIntOwned','callScriptInt','(I)I','II',504,6),'wide':('scriptLongOwned','callScriptLong','(J)J','JJ',100,4),
           'single':('scriptFloatOwned','callScriptFloat','(F)F','FF',100,4),'failure':('scriptFailureOwned','callScriptFailure','(I)I','II',101,2)}
    require({x['symbol'] for x in graph['accepted']}==set(names),'CALLER_SET')
    for item in graph['accepted']:
        method,caller,desc,*_=names[item['symbol']]
        require(item['target']=='Ldev/tripoder/selfhookprobe/ScriptOwnedFixture;->'+method+desc
            and item['caller']=='Ldev/tripoder/selfhookprobe/ScriptOwnedCaller;->'+caller+desc,'CALLER_IDENTITY')
    classifier=load('script_compile','verify-self-art-compilation.py');compiled=[]
    require(len(s['compilation'])==4 and {x['symbol'] for x in s['compilation']}==set(names),'COMPILATION_SET')
    for item in s['compilation']:
        pair=[classifier.entry(item[k],maps) for k in ('target','caller')]
        require(all(e['kind']==args.expected for e in pair),'ACTUAL_COMPILATION');compiled.append(pair)
    require(len(s['sourceFiles'])==4 and {x['phase'] for x in s['sourceFiles']}=={'primary','replace','bad-before','bad-after'},'SOURCE_SET')
    hashes=set()
    for source in s['sourceFiles']:
        require(source['file']=='script-source-'+source['phase']+'.ts' and sha(d/'device'/source['file'])==source['sha256']
            and source['compilerVersion']==s['compilerVersion'],'SOURCE_DIGEST');hashes.add(source['sha256'])
    require(len(s['publications'])==8 and [x['operation'] for x in s['publications']]==['publish','retire']*4,'PUBLICATION_SEQUENCE')
    require({x['sourceSha256'] for x in s['publications'] if x['operation']=='publish'}==hashes,'PUBLICATION_SOURCE')
    require(len(s['native'])==4 and {x['symbol'] for x in s['native']}==set(names),'NATIVE_SET')
    mapping=load('script_maps','verify-self-hook-probe.py').mapping
    for item in s['native']:
        name=item['symbol'];state=item['native'];expected=names[name]
        require(state['ready'] is state['backupRooted'] is state['writerOwnerRegistered'] is True
            and state['writerInterpositionInstalled'] is True and state['completeEntryWriterClosure'] is False
            and state['active'] is state['fullCallerCoverage'] is False
            and state['stage']=='DISABLED' and state['knownCallers']==1 and state['shorty']==expected[3]
            and state['originals']==expected[4] and state['transitions']==expected[5]
            and state['targetQuick']==state['desiredEntry']==state['interpreterBridge'],'NATIVE_LIFETIME:'+name)
        require(state['compilationOwnership']=='process-interpreter-lease' and state['processInterpreterOwned'] is True
            and state['observedInstrumentationLevel']==2 and state['processPolicyClaims']==1,'SCRIPT_PROCESS_INTERPRETER')
        relay=d/'device'/('script-relay-'+name+'.bin');helper=d/'device'/('script-helper-'+name+'.dex')
        require(sha(relay)==item['relaySha256'] and sha(helper)==item['helperSha256'],'HELPER_ARTIFACT')
        code=struct.pack('<6I2Q',0xd503245f,0x580000a0,0x580000d0,0xd61f0200,0xd503201f,0xd503201f,
            int(state['helperMethod'],16),int(state['interpreterBridge'],16));code+=struct.pack('<I',0xd503201f)*((4096-len(code))//4)
        region=mapping(maps,int(state['relay'],16));require(relay.read_bytes()==code and region['mode']=='r-xp' and region['inode']==0,'RELAY_CODE')
        decoded=subprocess.run([str(args.sdk/'build-tools/37.0.0/dexdump'),'-d',str(helper)],capture_output=True,text=True,check=True).stdout
        require('Ldev/tripoder/selfhook/ManagedHook;.dispatch' in decoded,'HELPER_DISPATCH')
    commands=s['commands'];require([x['sequence'] for x in commands]==list(range(1,len(commands)+1)),'COMMAND_ORDER')
    for command in commands:require(command['request']['processGeneration']==r['runId'],'COMMAND_GENERATION')
    require(any(x['response'].get('result',{}).get('state')=='DRAINING' for x in commands),'DRAIN_ACK')
    require(any(x['response'].get('ok') is False and 'HOOK_PARAMETER_VERSION_STALE' in str(x['response']) for x in commands),'STALE_CAS_REJECTED')
    if args.expected=='aot':require('actualCompilerFilter=speed' in (d/'aot-compile.txt').read_text() and 'Final Status: PERFORMED' in (d/'aot-compile.txt').read_text(),'AOT_RECEIPT')
    report={'passed':True,'phase':r['phase'],'runId':r['runId'],'kind':args.expected,'apkSha256':build['apkSha256'],'buildId':build['buildId'],
        'sourceDigest':build['sourceDigest'],'checks':s['checks'],'nativeOriginals':{x['symbol']:x['native']['originals'] for x in s['native']},
        'compiled':compiled,'resultSha256':sha(path),'verifierSha256':sha(Path(__file__)),
        'scope':'Owned source to real ManagedHook; product backend, approval authority and full caller coverage remain unproven'}
    with (d/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='compiled'}))
if __name__=='__main__':main()
