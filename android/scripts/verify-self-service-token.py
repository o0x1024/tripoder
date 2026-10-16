#!/usr/bin/env python3
"""Fixed Service member access on owned objects; not a system Binder acceptance."""
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha

def verify(directory,run_id):
    result,build,report=read(directory,run_id,'H4-service-token');case=result['service'];registry=result['frameworkAccess']
    need(registry['ready'] is True and registry['stage']=='READY' and registry['jniIdType']==0,'FIXED_REGISTRY_READY')
    need(case['checks']==23 and case['calls']==6 and case['threads']==2 and case['exactException'] is case['physicalFieldsUnchanged'] is True
         and case['systemServiceInvoked'] is case['hookInstalled'] is case['productAccepted'] is False,'SERVICE_FIXED_CALL_CONTRACT')
    report.update(checks=23,calls=6,threads=2,systemServiceInvoked=False,verifierSha256=sha(Path(__file__)),
        scope='Exact fixed Service fields/interface JNI call with owned manager; real system stop lifecycle remains separate')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
