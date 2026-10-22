#!/usr/bin/env python3
"""Run real crash recovery on a separately named, explicitly built owned QA installation."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--evidence',required=True,type=Path)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[2]
    receipt=json.loads((root/'android/product/build/build-receipt.json').read_text())
    package=receipt['artifacts']['engine']['package']
    if package=='dev.tripoder.engine' or not receipt.get('recoveryAcceptanceFixture'):
        raise SystemExit('EXPLICIT_SEPARATE_RECOVERY_QA_BUILD_REQUIRED')
    evidence=args.evidence;evidence.mkdir(parents=True,exist_ok=True)
    def adb(*values,check=True,timeout=20):
        return subprocess.run(['adb','-s',args.serial,*values],capture_output=True,text=True,check=check,timeout=timeout)
    installed=adb('shell','pm','path',package).stdout.strip().splitlines()
    if len(installed)!=1 or not installed[0].startswith('package:'):raise RuntimeError('QA_INSTALLATION_REQUIRED')
    actual=adb('shell','sha256sum',installed[0][8:]).stdout.split()[0]
    if actual!=receipt['artifacts']['engine']['sha256']:raise RuntimeError('INSTALLED_QA_HASH_MISMATCH')
    run='control-'+uuid.uuid4().hex[:16]
    command=['adb','-s',args.serial,'shell','am','instrument','-w','-e','runId',run,'dev.tripoder.controltest/dev.tripoder.controltest.EngineRecoveryIntegration']
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    started=False;deadline=time.monotonic()+240
    try:
        while process.poll() is None:
            if time.monotonic()>deadline:raise TimeoutError('RECOVERY_DEVICE_ACCEPTANCE_TIMEOUT')
            if not started:
                signal=adb('shell','cat',f'/sdcard/Android/data/{package}/files/launch-workbench-{run}.json',check=False)
                if signal.returncode==0:
                    request=json.loads(signal.stdout)
                    if request.get('runId')!=run or request.get('component')!=package+'/dev.tripoder.hooklab.WorkbenchActivity':raise RuntimeError('WORKBENCH_SIGNAL_MISMATCH')
                    adb('shell','am','start','-n',request['component']);started=True
            time.sleep(.5)
        output=process.communicate(timeout=10)[0];(evidence/'instrumentation.txt').write_text(output)
        raw=adb('shell','cat',f'/sdcard/Android/data/{package}/files/result-{run}.json').stdout
        result=json.loads(raw);(evidence/'result.json').write_text(raw)
        log=adb('logcat','-d','--uid='+str(result['uid']),'-t','2000',check=False).stdout
        (evidence/'qa-logcat.txt').write_text(log)
        fault_pid=result.get('workbenchStartupStatus',{}).get('enginePid')
        crash=adb('logcat','-b','crash','-d','-t','2000',check=False).stdout
        signal_lines=[line for line in (log+'\n'+crash).splitlines() if fault_pid and re.search(r'\b'+str(fault_pid)+r'\b',line) and 'Fatal signal 6 (SIGABRT)' in line]
        (evidence/'qa-signal-evidence.txt').write_text('\n'.join(dict.fromkeys(signal_lines))+'\n')
        native_fatal=bool(signal_lines)
        expected=re.findall(r'INSTRUMENTATION_RESULT: resultSha256=([a-f0-9]{64})',output)
        if expected!=[hashlib.sha256(raw.encode()).hexdigest()]:raise RuntimeError('DEVICE_REPORT_RECEIPT_MISMATCH')
        (evidence/'qa-build-receipt.json').write_text(json.dumps(receipt,indent=2))
        verification={'package':package,'installedApkSha256':actual,'runId':run,'deviceBuild':result.get('deviceBuild'),'passed':bool(result.get('passed') and native_fatal),'checks':len(result.get('checks',[])),'scope':result.get('scope'),'nativeSigabrtObserved':native_fatal,'faultAuthorityPid':fault_pid}
        (evidence/'verification.json').write_text(json.dumps(verification,indent=2))
        print(json.dumps(verification))
        if not result.get('passed'):raise RuntimeError(result.get('error','RECOVERY_DEVICE_ACCEPTANCE_FAILED'))
        if not native_fatal:raise RuntimeError('NATIVE_SIGABRT_LOG_EVIDENCE_REQUIRED')
    finally:
        if process.poll() is None:process.kill();process.communicate()
        progress=adb('shell','cat',f'/sdcard/Android/data/{package}/files/progress-{run}.json',check=False)
        (evidence/'progress.json').write_text(progress.stdout)

if __name__=='__main__':main()
