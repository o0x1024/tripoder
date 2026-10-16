#!/usr/bin/env python3
import argparse
from pathlib import Path
import json
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H5-product-native-backend');case=result['control']
    need(case['checks']==31 and case['exports']==4 and case['activations']==2 and case['foreignCalls']==400,'PRODUCT_NATIVE_CASES')
    need(case['actualProductNativeBackend'] is case['actualSourceCompiler'] is case['realModuleEvents'] is True
         and case['productAuthorityConnected'] is case['javaBackendConnected'] is case['productAccepted'] is False,'PRODUCT_NATIVE_SCOPE')
    need(result['ownedNativeSha256']==build['ownedNativeSha256'] and result['auditSha256']==sha(directory/'device/audit.txt')
        and (directory/'device/audit.txt').read_text()=='1','PRODUCT_NATIVE_SIDE_EFFECT_ONCE')
    need(all(name in build['sources'] for name in ['android/runtime/src/dev/tripoder/runtime/RuntimeNativeObservation.java',
        'android/runtime/src/dev/tripoder/runtime/RuntimeNativeHookBackend.java','android/runtime/src/dev/tripoder/runtime/script/NativeHookValues.java']),'ACTUAL_PRODUCT_NATIVE_SOURCES')
    report.update(checks=31,exports=4,activations=2,foreignCalls=400,verifierSha256=sha(Path(__file__)),
        scope='Actual product Native observation/backend/source compiler and real JNI hooks; owned transport, external approval authority and Java composite not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
