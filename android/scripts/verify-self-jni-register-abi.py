#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H7-jni-register-abi');case=result['jni']
    need(case['passed'] is True and case['checks']==50 and case['failures']==0 and case['modes']==4 and case['javaCallbacks']==104 and case['quickManagedCalls']==28,'JNI_REGISTER_ABI_CASES')
    need(case['publicScalarArguments']==16 and case['privateIntegerStackBytes']==80 and case['nativeFlagsPreserved'] is True
        and case['hookOwnershipInstalled'] is True and case['productAccepted'] is False,'JNI_REGISTER_ABI_SCOPE')
    need('android/self-hook/native/abi/register_abi.c' in build['sources'],'REGISTER_TRANSPORT_SOURCE')
    report.update(checks=50,modes=4,javaCallbacks=104,quickManagedCalls=28,verifierSha256=sha(Path(__file__)),scope='Private JNI register transport on actual ordinary/Fast/Critical/instance native declarations, generic frame/state checks, 18 ABI arguments, Java upcall GC/stack walks, pending exception and local-root cleanup; quick entry owner preserved across actual Unregister/RegisterNatives; SDK Native declaration integration not yet installed')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
