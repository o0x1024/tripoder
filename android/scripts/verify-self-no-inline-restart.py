#!/usr/bin/env python3
"""Verify the retained-flag cold-restart counterexample; success here is NOT an E1 pass."""
import argparse
import json
from pathlib import Path
from verify_self_owned_run import identity, need, sha

def load(directory):
    r=json.loads((directory/'device/result.json').read_text());b=json.loads((directory/'build-receipt.json').read_text());p=json.loads((directory/'run-plan.json').read_text())
    identity(directory,r['runId'],'P2-E1-owned-no-inline',r,b,p)
    need(sha(directory/'device/maps.txt')==r['mapsSha256'],'RESTART_MAPS_IDENTITY')
    need(p['cacheReset'] is False and p['compilerSettingsChanged'] is False and p['productPolicyChanged'] is False,'RESTART_NO_SETTINGS_CHANGE')
    return r,b

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--first',type=Path,required=True);p.add_argument('--restart',type=Path,required=True);a=p.parse_args()
    first,fb=load(a.first);second,sb=load(a.restart)
    verification=json.loads((a.first/'verification.json').read_text())
    need(verification['passed'] is True and verification['resultSha256']==sha(a.first/'device/result.json'),'RESTART_FIRST_RUN_VERIFIED')
    need(first['pid']!=second['pid'] and first['runId']!=second['runId'] and fb['apkSha256']==sb['apkSha256'] and fb['sourceDigest']==sb['sourceDigest'],'RESTART_SAME_BUILD_NEW_PROCESS')
    need(first['passed'] is True and second['passed'] is False and 'E1_PREEXISTING_COMPILE_DONT_BOTHER' in second['error'],'RESTART_EXPLICIT_REJECTION')
    need(second['attemptedCalls']==second['originalBodyCalls']==0 and 'prepared' not in second,'RESTART_NO_HOOK_OR_TARGET_CALL')
    initial=first['targetBefore']['words'][1];retained=first['targetDisabled']['words'][1];observed=second['targetBefore']['words'][1]
    need(initial&0x02000000==0 and retained==observed and observed&0x02000000!=0,'RESTART_RETAINED_DECLARATION_FLAG')
    method=int(second['targetBefore']['method'],16);mapping=None
    for line in (a.restart/'device/maps.txt').read_text().splitlines():
        fields=line.split();begin,end=(int(x,16) for x in fields[0].split('-'))
        if begin<=method<end:mapping=line;break
    need(mapping is not None and '/cache/oat_primary/arm64/base.art' in mapping,'RESTART_METHOD_IN_CACHED_RUNTIME_IMAGE')
    report={'counterexampleVerified':True,'experimentGatePassed':False,'productAccepted':False,'firstRunId':first['runId'],'restartRunId':second['runId'],'firstPid':first['pid'],'restartPid':second['pid'],'apkSha256':fb['apkSha256'],'initialFlags':hex(initial),'firstDisabledFlags':hex(retained),'restartInitialFlags':hex(observed),'restartMethodMapping':mapping,'restartGlobalLevelObserved':False,'sourceDigest':fb['sourceDigest'],'verifierSha256':sha(Path(__file__))}
    with (a.restart/'restart-verification.json').open('x') as f:f.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))

if __name__=='__main__':main()
