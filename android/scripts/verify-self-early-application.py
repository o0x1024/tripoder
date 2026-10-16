#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H6-early-application-identity');case=result['application'];native=case['native']
    need(case['checks']==14 and case['guestPhases']==8 and case['installedBootEntries']==2,'EARLY_APPLICATION_PHASES')
    need(case['preparedOffMainThread'] is case['nullBeforeConstruction'] is case['physicalOwnershipPreserved'] is True
        and case['productAccepted'] is False,'EARLY_APPLICATION_SCOPE')
    need(native['constructs']==native['attaches']==1 and native['viewRoots']==9 and native['viewEnabled'] is False
        and native['retainedGlobalRefs']==18 and native['stage']=='ATTACHED','EARLY_APPLICATION_REAL_OWNERSHIP')
    need('android/runtime/src/dev/tripoder/runtime/RuntimeManagedInstaller.java' in build['sources'],'REAL_PRODUCT_INSTALLER')
    report.update(checks=14,guestPhases=8,verifierSha256=sha(Path(__file__)),scope='Real product installer and native ownership; off-main bootstrap, separate guest loader, factory and Application clinit/constructor see null Application and logical process name; attach/onCreate see published guest, physical host unchanged; complete product bootstrap not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
