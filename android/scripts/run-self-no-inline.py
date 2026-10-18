#!/usr/bin/env python3
"""Fresh owned E1 process, immutable build receipt, no compiler/cache setting changes."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid
from verify_self_owned_run import sha, need, ROOT

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--evidence-root',type=Path,required=True);p.add_argument('--install',action='store_true');a=p.parse_args()
    build=ROOT/'android/self-hook-probe/build/e1';receipt=json.loads((build/'build-receipt.json').read_text())
    need(sha(receipt['apk'])==receipt['apkSha256'],'E1_APK_CHANGED')
    for path,digest in receipt['sources'].items():need(sha(ROOT/path)==digest,'SOURCE_CHANGED:'+path)
    root=a.evidence_root.resolve();need(root.is_relative_to(ROOT/'docs/evidence'),'E1_EVIDENCE_LOCATION')
    run=str(uuid.uuid4());out=root/run;out.mkdir(parents=True);pkg='dev.tripoder.selfhookprobe'
    (out/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    (out/'run-plan.json').write_text(json.dumps({'runId':run,'package':pkg,'apkSha256':receipt['apkSha256'],'runnerSha256':sha(Path(__file__)),'cacheReset':False,'compilerSettingsChanged':False,'productPolicyChanged':False},indent=2)+'\n')
    def adb(label,*args,check=True,timeout=30):
        r=subprocess.run(['adb','-s',a.serial,*map(str,args)],capture_output=True,text=True,timeout=timeout)
        if label:(out/(label+'.txt')).write_text(r.stdout+r.stderr)
        if check:need(r.returncode==0,'E1_ADB:'+str(label)+':'+r.stderr)
        return r
    remote='/sdcard/Android/data/'+pkg+'/files/'+run
    try:
        if a.install:adb('install','install','--no-incremental','-r',receipt['apk'],timeout=90)
        path=adb(None,'shell','pm','path',pkg).stdout.strip().removeprefix('package:')
        need(path.startswith('/data/app/') and path.endswith('/base.apk') and '\n' not in path,'E1_INSTALLED_PATH')
        need(adb('installed-sha','shell','sha256sum',path).stdout.split()[0]==receipt['apkSha256'],'E1_INSTALLED_IDENTITY')
        adb('force-stop','shell','am','force-stop',pkg)
        launched=adb('launch','shell','am','start','-W','-f','0x10008000','-n',pkg+'/.NoInlineProbeActivity','--es','runId',run)
        need('Status: ok' in launched.stdout and 'LaunchState: COLD' in launched.stdout,'E1_COLD_LAUNCH')
        print(json.dumps({'runId':run,'directory':str(out),'status':'RUNNING'}),flush=True)
        deadline=time.monotonic()+120
        while adb(None,'shell','test','-f',remote+'/result.json',check=False).returncode:
            need(time.monotonic()<deadline,'E1_RESULT_TIMEOUT');need(adb(None,'shell','pidof',pkg,check=False).returncode==0,'E1_PROCESS_DIED');time.sleep(1)
        adb('pull','pull',remote,out/'device')
        result=json.loads((out/'device/result.json').read_text());need(result['runId']==run and result['apkSha256']==receipt['apkSha256'],'E1_RESULT_IDENTITY')
        print(json.dumps({'runId':run,'directory':str(out),'passed':result['passed'],'error':result.get('error')}),flush=True)
        need(result['passed'] is True,'E1_EXPERIMENT_FAILED')
    except Exception as e:
        if not (out/'device').exists():adb('failure-pull','pull',remote,out/'device',check=False)
        (out/'runner-outcome.json').write_text(json.dumps({'passed':False,'runId':run,'error':str(e)},indent=2)+'\n');raise

if __name__=='__main__':main()
