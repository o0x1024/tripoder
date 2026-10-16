#!/usr/bin/env python3
"""Independent acceptance of an owned PAC entry routed through a sealed ELF."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import uuid
import zipfile
from verify_self_entry_evidence import verify as verify_entry_artifacts

ROOT=Path(__file__).resolve().parents[2]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def require(ok,reason):
    if not ok:raise RuntimeError(reason)
def branch(pc,target):
    delta=target-pc;require(delta%4==0 and -0x8000000<=delta<0x8000000,'BRANCH_RANGE');return 0x14000000|((delta//4)&0x03ffffff)
def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();d=a.evidence
    b=json.loads((d/'build-receipt.json').read_text());path=d/'device/result.json';r=json.loads(path.read_text())
    require(r['passed'] is True and r['phase']=='H3k-owned-entry-publication' and r['runId']==str(uuid.UUID(a.run_id)),'ENTRY_RUN')
    require(r['package']=='dev.tripoder.selfhookprobe' and r['uid']>=10000 and r['pid']>0 and r['debuggable'] is False,'OWNED_PROCESS')
    require(r['apkSha256']==b['apkSha256']==sha(Path(b['apk'])) and r['nativeSha256']==b['nativeSha256']
        and r['build']['sourceDigest']==b['sourceDigest'] and r['build']['buildId']==b['buildId'],'BUILD_IDENTITY')
    for name,digest in b['sources'].items():require(sha(ROOT/name)==digest,'SOURCE_CHANGED:'+name)
    require(hashlib.sha256(json.dumps(b['sources'],sort_keys=True).encode()).hexdigest()==b['sourceDigest'],'SOURCE_MANIFEST')
    platform=json.loads((ROOT/'android/self-hook-probe/platform.json').read_text())
    require(r['platformGatePassed'] is True and all(r['platform'][k]==v for k,v in platform.items()),'PLATFORM')
    maps=(d/'device/maps.txt').read_text();smaps=(d/'device/smaps.txt').read_text()
    require(r['mapsSha256']==sha(d/'device/maps.txt') and r['smapsSha256']==sha(d/'device/smaps.txt')
        and 'libtripoderselfhook.so' in maps and not any(x in maps.lower() for x in ('frida','gadget','libgum')),'MAPS')
    e=r['entry'];proof=verify_entry_artifacts(d,e,maps,smaps,False)
    with zipfile.ZipFile(b['apk']) as apk:require(hashlib.sha256(apk.read('lib/arm64-v8a/libtripoderselfhook.so')).hexdigest()==b['nativeSha256'],'PACKAGED_NATIVE')
    report=dict(passed=True,phase=r['phase'],runId=r['runId'],apkSha256=b['apkSha256'],buildId=b['buildId'],sourceDigest=b['sourceDigest'],
        calls=e['calls'],asyncSamples=e['asyncSamples'],stateMask=proof['stateMask'],pageCommits=2,resultSha256=sha(path),verifierSha256=sha(Path(__file__)),
        scope='Owned PAC entry, sealed ELF and loader-visible FDEs only; no real ART interposition or full writer closure')
    with (d/'verification.json').open('x') as stream:stream.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
if __name__=='__main__':main()
