#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,identity,need,sha
from self_module_death import exit_record
def verify(directory,run,death):
    if not death:
        result,build,report=read(directory,run,'H5-native-export-races');case=result['race']
        need(case['passed'] is True and case['checks']==25 and case['failures']==0,'NATIVE_RACE_CASES')
        need(case['oldResult']==7 and case['oldErrno']==17 and case['oldCallbacks']==case['newCallbacks']==1
             and case['moduleRemoved'] is case['immutableActivations'] is True and case['productJavaConnected'] is case['productAccepted'] is False,'IMMUTABLE_LATE_CALLBACK')
    else:
        before_path=directory/'device/before-active-unload.json';ready_path=directory/'device/active-unload-ready.json'
        result=json.loads(before_path.read_text());ready=json.loads(ready_path.read_text());build=json.loads((directory/'build-receipt.json').read_text());plan=json.loads((directory/'run-plan.json').read_text())
        report=identity(directory,run,'H5-native-export-races',result,build,plan)
        need(plan['activity']=='native-inflight-death' and result['deathRequested'] is True and not (directory/'device/result.json').exists(),'INFLIGHT_DEATH_CASE')
        need(ready['pid']==result['pid'] and ready['generation']==ready['references']==1 and ready['checks']==14 and ready['failures']==0
            and ready['installed'] is ready['publicationRetired'] is ready['invocationLease'] is True,'ONLY_INVOCATION_LEASE_REMAINS')
        record=exit_record((directory/'exit-info.txt').read_text(),result['pid'],result['uid'])
        need(record and record['reason']==1 and record['status']==198 and record==json.loads((directory/'exit-observation.json').read_text()),'EXACT_INFLIGHT_EXIT')
        maps=(directory/'device/maps-active-unload.txt').read_text();need(not any(x in maps.lower() for x in ('frida','gadget','libgum')) and 'libtripoderownedhooks.so' in maps,'INFLIGHT_MAPS')
        report.update(exitStatus=198,pid=ready['pid'],beforeSha256=sha(before_path),readySha256=sha(ready_path),exitInfoSha256=sha(directory/'exit-info.txt'))
    need(result['ownedNativeSha256']==build['ownedNativeSha256'],'RACE_PRODUCT_LIBRARY')
    report.update(verifierSha256=sha(Path(__file__)),scope='Real Native activation/page/invocation races; controlled C session stand-in, product Java/JNI dispatch not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);p.add_argument('--death',action='store_true');a=p.parse_args()
    report=verify(a.evidence,a.run_id,a.death)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
