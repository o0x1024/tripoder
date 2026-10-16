#!/usr/bin/env python3
"""Require matched system exit information and a native active lease witness."""
import argparse
import json
from pathlib import Path
from verify_self_owned_run import identity,need,sha
from self_module_death import exit_record
def verify(directory,run):
    before_path=directory/'device/before-active-unload.json';ready_path=directory/'device/active-unload-ready.json'
    before=json.loads(before_path.read_text());ready=json.loads(ready_path.read_text())
    build=json.loads((directory/'build-receipt.json').read_text());plan=json.loads((directory/'run-plan.json').read_text())
    report=identity(directory,run,'H5-native-module-lifetime',before,build,plan)
    need(plan['activity']=='modules-death' and before['deathRequested'] is True,'DEATH_CASE_REQUESTED')
    need(not (directory/'device/result.json').exists(),'NO_SUCCESS_ACK_AFTER_UNLOAD')
    need(ready['pid']==before['pid'] and ready['installed'] is True and ready['generation']==1 and ready['references']==1
        and ready['checks']==19 and ready['failures']==0 and ready['expectedExitStatus']==198,'ACTIVE_NATIVE_LEASE_WITNESS')
    record=exit_record((directory/'exit-info.txt').read_text(),before['pid'],before['uid'])
    need(record and record['reason']==1 and record['status']==198,'EXACT_SELF_EXIT_STATUS')
    need(record==json.loads((directory/'exit-observation.json').read_text()),'EXIT_OBSERVATION_IDENTITY')
    maps=(directory/'device/maps-active-unload.txt').read_text()
    need(not any(x in maps.lower() for x in ('frida','gadget','libgum')) and 'libowned.so' in maps and 'tripoder-linker-notifier' in maps,'ACTIVE_OWNED_MAPS')
    report.update(pid=ready['pid'],exitStatus=198,activeLeaseWitnessSha256=sha(ready_path),beforeSha256=sha(before_path),
        mapsSha256=sha(directory/'device/maps-active-unload.txt'),exitInfoSha256=sha(directory/'exit-info.txt'),verifierSha256=sha(Path(__file__)),
        scope='Real module lease conflicts with dlclose; direct exit_group before linker frees mapping. Not a published Native Hook yet.')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
