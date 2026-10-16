#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H5-native-leaf-entry');case=result['leaf']
    need(case['passed'] is True and case['checks']==30 and case['failures']==0 and case['exports']==6,'NATIVE_LEAF_CASES')
    need(0<case['fileBytes']<4096 and case['active'] is case['productAccepted'] is False and case['hookInstalled'] is True,'NATURAL_PARTIAL_FILE_LEAVES')
    need(result['leafNativeSha256']==build['leafNativeSha256'],'LEAF_ARTIFACT')
    report.update(checks=30,exports=6,fileBytes=case['fileBytes'],verifierSha256=sha(Path(__file__)),scope='Natural compiler MOV/register ALU/FP/SIMD/RET/void leaf entries and a partial source file page; actual core hooks, product acceptance separate')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
