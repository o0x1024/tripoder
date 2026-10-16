#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha

def verify(directory,run):
    result,build,report=read(directory,run,'H6-managed-constructor');case=result['constructor'];native=case['native']
    need(case['checks']==26 and case['directCalls']==case['reflectedCalls']==case['delegatedCalls']==2,'CONSTRUCTOR_CALLS')
    need(case['constructorFlagsPreserved'] is True and case['productAccepted'] is False and case['movingGcRequested']==3,'CONSTRUCTOR_SCOPE')
    need(native['backupRooted'] is True and native['active'] is False and native['originals']==4
        and native['targetFlags']&0x10000 and native['backupFlags']&0x10000,'CONSTRUCTOR_BACKUP_AND_FLAGS')
    report.update(checks=26,verifierSha256=sha(Path(__file__)),scope='Owned managed constructor and different-signature method in one helper loader; whole-batch preflight; super/final fields, exact arguments/object roots across requested GC, direct/reflection/this delegation, original once and exception integrity; product construction adapter not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
