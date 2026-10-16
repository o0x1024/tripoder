#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha

def verify(directory,run,all_managers):
    result,build,report=read(directory,run,'H6-fixed-manager-installer');case=result['installer']
    checks=15 if all_managers else 13;installed=1523 if all_managers else 8
    need(case['checks']==checks and case['installed']==installed and case['callbacks']==5 and case['unownedOriginals']==1,'FIXED_INSTALLER_COUNTS')
    need(case['allManagers'] is all_managers and (case['otherInstallationNs']>0 if all_managers else case['otherInstallationNs']==0),'FIXED_INSTALLER_MODE')
    need(case['actualProductInstaller'] is case['bootClassTargets'] is True and case['systemBinderCalls']==0
        and case['productAccepted'] is False,'FIXED_INSTALLER_SCOPE')
    need('android/runtime/src/dev/tripoder/runtime/RuntimeManagedInstaller.java' in build['sources'],'ACTUAL_PRODUCT_INSTALLER_SOURCE')
    report.update(checks=checks,installed=installed,verifierSha256=sha(Path(__file__)),scope='Actual product installer and entry ownership for the complete selected fixed manager inventory; BatteryManager functional checks on owned objects, no system Binder calls; full product startup not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);p.add_argument('--all-managers',action='store_true');a=p.parse_args()
    report=verify(a.evidence,a.run_id,a.all_managers)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
