#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H5-native-java-jni');case=result['jni']
    need(case['checks']==33 and case['nativeThreads']==4 and case['foreignCalls']==400,'NATIVE_JNI_CASES')
    need(case['addCallbacks']==case['onceRejected']==403 and case['foreignOriginalRejected']==1,'JNI_ORIGINAL_IDENTITY')
    threads=case['threadLeaseProof'];need(threads['jniOwnedThreadLeases']==0 and threads['jniThreadAttachments']==threads['jniThreadDetachments']==4,'JNI_THREAD_LIFETIME')
    need(case['actualJavaNativeCore'] is True and case['scriptBackendConnected'] is case['productAuthorityConnected'] is case['productAccepted'] is False,'JNI_SCOPE')
    need(result['ownedNativeSha256']==build['ownedNativeSha256'] and result['auditSha256']==sha(directory/'device/audit.txt')
        and (directory/'device/audit.txt').read_text()=='1','JNI_REAL_ORIGINAL_ONCE')
    need(all(name in build['sources'] for name in ['android/self-hook/java/dev/tripoder/selfhook/NativeHook.java',
        'android/self-hook/java/dev/tripoder/selfhook/NativeModules.java','android/self-hook/native/abi/native_jni.c']),'PRODUCT_NATIVE_CORE_SOURCES')
    report.update(checks=33,foreignCalls=400,verifierSha256=sha(Path(__file__)),
        scope='Actual JNI core, one daemon attachment per native-owned pthread, exit detach, borrowed attachments, nested original tokens, GC and restore; authority not connected')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args()
    report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
