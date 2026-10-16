#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H5-product-mixed-backend');case=result['control']
    need(case['checks']==19 and case['javaMethods']==1 and case['nativeExports']==2,'MIXED_CASES')
    need(case['actualProductComposite'] is case['fullEvidencePartitions'] is case['sameInvocationSession'] is True
        and case['productAuthorityConnected'] is case['productAccepted'] is False,'MIXED_SCOPE')
    need(result['ownedNativeSha256']==build['ownedNativeSha256'] and 'android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookPlatform.java' in build['sources'],'MIXED_PRODUCT_SOURCES')
    report.update(checks=19,javaMethods=1,nativeExports=2,verifierSha256=sha(Path(__file__)),scope='Actual composite product Platform and one mixed session; trusted owned transport, external approval authority not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
