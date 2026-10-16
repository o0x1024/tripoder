#!/usr/bin/env python3
"""Actual product Java observation/binding code under an owned transport harness."""
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha

def verify(directory,run):
    result,build,report=read(directory,run,'H5-product-java-backend');case=result['control']
    need(case['checks']==43 and case['methods']==16 and case['nativeDeclarations']==4 and case['activations']==2,'PRODUCT_JAVA_CASES')
    need(case['handleIdentityReuse'] is case['observationDidNotInitialize'] is case['originalExceptionIdentity'] is case['actualProductJavaBackend'] is True
         and case['productAuthorityConnected'] is case['nativeBackendConnected'] is case['productAccepted'] is False,'PRODUCT_JAVA_SCOPE')
    need(all(name in build['sources'] for name in ['android/runtime/src/dev/tripoder/runtime/RuntimeJavaObservation.java',
         'android/runtime/src/dev/tripoder/runtime/RuntimeJavaHookBackend.java','android/runtime/src/dev/tripoder/runtime/script/JavaHookValues.java']),'ACTUAL_PRODUCT_SOURCES')
    report.update(checks=43,methods=16,nativeDeclarations=4,activations=2,verifierSha256=sha(Path(__file__)),
        scope='Actual Java backend including ordinary/Fast/Critical and unresolved Native declarations with owned transport; product authority/approval transport and Native backend remain separate')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
