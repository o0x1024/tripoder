#!/usr/bin/env python3
import argparse,json
from pathlib import Path
from verify_self_owned_run import identity,need,sha
from self_module_death import exit_record
def verify(directory,run):
    before=json.loads((directory/'device/before-active-unload.json').read_text());build=json.loads((directory/'build-receipt.json').read_text());plan=json.loads((directory/'run-plan.json').read_text())
    report=identity(directory,run,'native-core-commit-death',before,build,plan)
    witness=json.loads((directory/'device/active-unload-ready.json').read_text())
    need(plan['activity']=='native-commit-death' and before['deathRequested'] is True,'COMMIT_FAULT_CASE')
    need(witness=={'pid':before['pid'],'committedPages':1,'expectedExitStatus':230},'POST_COMMIT_WITNESS')
    record=exit_record((directory/'exit-info.txt').read_text(),before['pid'],before['uid'])
    need(record==json.loads((directory/'exit-observation.json').read_text()) and record['reason']==1 and record['status']==230,'POST_COMMIT_EXIT')
    need(not (directory/'device/result.json').exists(),'NO_SUCCESS_AFTER_PARTIAL_COMMIT')
    report.update(verifierSha256=sha(Path(__file__)),exitStatus=230,scope='Injected failure after a real page commit terminates without resuming or a success ACK')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as f:f.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
