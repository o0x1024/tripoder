#!/usr/bin/env python3
import argparse,json
from pathlib import Path
from verify_self_owned_run import read,need,sha
def verify(directory,run):
    result,build,report=read(directory,run,'H7-jni-sdk');case=result['jni'];checks=case['passedChecks']
    need(case['passed'] is True and len(checks)==len(set(checks))==case['checks'] and len(checks)>=31,'JNI_SDK_CASES')
    for required in ['INDEPENDENT_UNINITIALIZED_GUEST','PREPARE_NO_GUEST_INITIALIZATION','ALL_NATIVE_MODE_FLAGS_AND_BACKUP_ROOTS',
        'MIXED_GPR_FPR_OVERFLOW_AND_NEGATIVE_ZERO','INSTANCE_UTF16_REFERENCE_IDENTITY','EXACT_NATIVE_ORIGINAL_EXCEPTION','REPLACE_UNRESOLVED_NATIVE_WITHOUT_ORIGINAL',
        'LAZY_NAMED_IMPLEMENTATION_RESOLUTION','UNREGISTERED_ORIGINAL_RESOLUTION_FAILURE','REPLACEMENT_SURVIVES_UNREGISTER',
        'CONCURRENT_REGISTER_ORIGINAL_AND_MOVING_GC','ADMITTED_ORIGINAL_SURVIVES_DISABLE','RETIRED_MISSING_USES_REAL_LOOKUP',
        'REREGISTRATION_DOES_NOT_CONSUME_HOOK_SLOTS']:
        need(required in checks,'MISSING_'+required)
    need(case['modes']==3 and case['parameters']==32 and case['concurrentCalls']==384 and case['registrations']==67,'JNI_SDK_ABI_COUNTS')
    need(case['native']['errors']==0 and case['native']['calls']>=396 and case['productAccepted'] is False,'JNI_SDK_NATIVE_OBSERVATIONS')
    need('android/self-hook/native/art_jni_original.c' in build['sources'],'JNI_ORIGINAL_IMPLEMENTATION')
    report.update(checks=len(checks),parameters=32,concurrentCalls=384,native=case['native'],verifierSha256=sha(Path(__file__)),scope='ManagedHook Native declarations with original native flags/frame/loader, actual registration changes, unresolved original, 32 Java parameters, GC and in-flight disable; Engine integration not accepted')
    return report
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);p.add_argument('--run-id',required=True);a=p.parse_args();report=verify(a.evidence,a.run_id)
    with (a.evidence/'verification.json').open('x') as out:out.write(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
