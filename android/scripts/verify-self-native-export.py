#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha,ROOT
def verify(directory,run):
    result,build,report=read(directory,run,'H5-native-export-installation');case=result['nativeHooks']
    need(case['passed'] is True and case['checks']==32 and case['failures']==0 and case['exports']==4,'NATIVE_EXPORT_CASES')
    need(case['concurrentCalls']==2000 and case['concurrentErrors']==0 and case['unwindCalls']==2006,'NATIVE_REAL_CALLS')
    need(case['fileMappingRestored'] is case['moduleRemoved'] is case['hookInstalled'] is True
        and case['productAccepted'] is False and case['generation']==1,'NATIVE_LIFECYCLE_SCOPE')
    pac=case['pac'];need(type(pac) is bool,'PAC_CASE_IDENTITY');mode='ownedNativePac' if pac else 'ownedNative'
    need(result['ownedNativeSha256']==build[mode+'Sha256'],'REAL_PRODUCT_LIBRARY_IDENTITY')
    need(result['auditSha256']==sha(directory/'device/audit.txt') and (directory/'device/audit.txt').read_text()=='2','ORIGINAL_AUDIT_EXACT_ONCE')
    command=['-O2','-Wall','-Wextra','-Werror','-fPIC','-shared','-Wl,-z,max-page-size=16384','-Wl,--build-id=sha1',str(ROOT/'android/runtime-fixtures/native/hook_target.c')]
    if pac:command+=['-mbranch-protection=standard']
    command+=['-o',str(Path(build['apk']).parent/('libtripoderownedhookspac.so' if pac else 'libtripoderownedhooks.so'))]
    need(build[mode+'CompileCommand'][1:]==command,'PRODUCT_OWNED_BUILD_FLAGS')
    report.update(checks=32,exports=4,concurrentCalls=2000,pac=pac,verifierSha256=sha(Path(__file__)),
        scope='Real product owned library compiled with original flags; shared-page Native export installation/original/restore/unload. Product JNI and approval transport not connected.')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
