#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import uuid
ROOT=Path(__file__).resolve().parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def need(v,r):
    if not v:raise RuntimeError(r)
def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();d=a.evidence
    path=d/'device/result.json';r=json.loads(path.read_text());b=json.loads((d/'build-receipt.json').read_text())
    need(r['passed'] is True and r['phase']=='H2e-native-scalar-abi' and r['runId']==str(uuid.UUID(a.run_id)),'RUN')
    need(r['package']=='dev.tripoder.selfhookprobe' and r['debuggable'] is False and r['uid']>=10000 and r['pid']>0,'OWNED_PROCESS')
    need(r['apkSha256']==b['apkSha256']==sha(Path(b['apk'])) and r['nativeSha256']==b['nativeSha256']
        and r['build']['buildId']==b['buildId'] and r['build']['sourceDigest']==b['sourceDigest'],'BUILD')
    for name,h in b['sources'].items():need(sha(ROOT/name)==h,'SOURCE_CHANGED:'+name)
    need(hashlib.sha256(json.dumps(b['sources'],sort_keys=True).encode()).hexdigest()==b['sourceDigest'],'SOURCE_MANIFEST')
    platform=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())
    need(r['platformGatePassed'] is True and all(r['platform'][k]==v for k,v in platform.items()),'PLATFORM')
    s=r['scalar'];need(s['passed'] is True and s['status']==0 and s['checks']==490 and s['cases']==13 and s['failures']==0
        and s['concurrentCalls']==4000 and s['concurrentErrors']==s['hookInstalls']==0,'ABI_EXECUTION')
    need(s['targetDiscovery'] is s['fullNativeHook'] is False,'SCOPE')
    host=json.loads((d/'scalar-host-report.json').read_text());need(host['passed'] is True and host['hostChecks']==2103 and host['typedCases']==13,'HOST')
    for name,h in host['sourceHashes'].items():need(sha(ROOT/name)==h and b['sources'][name]==h,'HOST_SOURCE:'+name)
    for item in host['decoded']:need(sha(d/(item['name']+'-audit.txt'))==item['auditSha256'],'LLVM_AUDIT')
    maps=(d/'device/maps.txt').read_text();need(sha(d/'device/maps.txt')==r['mapsSha256'] and 'libtripoderselfhook.so' in maps
        and not any(x in maps.lower() for x in ('frida','gadget','libgum')),'MAPS')
    report={'passed':True,'phase':r['phase'],'runId':r['runId'],'apkSha256':b['apkSha256'],'buildId':b['buildId'],
        'sourceDigest':b['sourceDigest'],'scalar':s,'assemblyExecuted':True,'resultSha256':sha(path),'verifierSha256':sha(Path(__file__)),
        'scope':'Owned 13 typed compiler comparisons and TLS errno; no generic target selection or Hook installation'}
    with (d/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
if __name__=='__main__':main()
