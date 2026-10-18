#!/usr/bin/env python3
import argparse,json
from pathlib import Path
from verify_self_owned_run import read,need,sha
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    result,build,report=read(a.evidence,a.run_id,'native-core-ranges');case=result['ranges']
    need(case['passed'] is True and case['checks']==52 and case['failures']==0 and case['exports']==7 and case['stage']=='UNLOAD','RANGE_EXECUTION_CASES')
    need(result['rangeNativeSha256']==build['rangeNativeSha256'],'RANGE_ELF_IDENTITY')
    report.update(checks=52,verifierSha256=sha(Path(__file__)),scope='Real ELF TBZ/TBNZ/CBZ/B/literal LDR/ADR/ADRP entry relocation, shared-page restoration and unload')
    with (a.evidence/'verification.json').open('x') as f:f.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
