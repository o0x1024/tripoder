#!/usr/bin/env python3
"""Validate ingress fixes on a separate QA package, retaining exact build/run evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import zipfile
import xml.etree.ElementTree as ET


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--receipt',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--serial',required=True)
    p.add_argument('--retry-engine-once',action='store_true',help='Explicitly reconnect a supervisor interrupted by prior instrumentation once; never replay a guest operation')
    args=p.parse_args();receipt=json.loads(args.receipt.read_text());package=receipt['artifacts']['engine']['package']
    if package=='dev.tripoder.engine':raise ValueError('SEPARATE_QA_PACKAGE_REQUIRED')
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    repo=Path(__file__).resolve().parents[2]
    def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
    def adb(*argv,check=True,timeout=60):
        result=subprocess.run(['adb','-s',args.serial,*argv],capture_output=True,text=True,timeout=timeout)
        if check and result.returncode:raise RuntimeError(result.stdout+result.stderr)
        return result
    for name,digest in receipt['sourceSha256'].items():
        if sha(repo/name)!=digest:raise RuntimeError('SOURCE_CHANGED:'+name)
    (out/'build-receipt.json').write_text(json.dumps(receipt,indent=2))
    (out/'runner-sha256.txt').write_text(sha(Path(__file__).resolve()))
    for kind in ('engine','controlTest'):
        item=receipt['artifacts'][kind]
        if sha(Path(item['path']))!=item['sha256']:raise RuntimeError('APK_CHANGED')
        adb('install','--no-incremental','-r',item['path'])
        installed=adb('shell','pm','path',item['package']).stdout.strip().removeprefix('package:')
        if '\n' in installed or adb('shell','sha256sum',installed).stdout.split()[0]!=item['sha256']:raise RuntimeError('INSTALLED_APK_MISMATCH')
    with zipfile.ZipFile(receipt['artifacts']['engine']['path']) as archive:
        if hashlib.sha256(archive.read('lib/arm64-v8a/libtripoderselfhook.so')).hexdigest()!=receipt['selfHookNative']['sha256']:raise RuntimeError('CORE_MISMATCH')
    fixture=Path(receipt['artifacts']['engine']['path']).with_name('ingress-guest.apk')
    if sha(fixture)!=receipt['installFixtures']['ingressSha256']:raise RuntimeError('FIXTURE_DIGEST_MISMATCH')
    native_package='dev.tripoder.ingressguest'
    adb('install','--no-incremental','-r',str(fixture))
    adb('shell','am','force-stop',native_package)
    native_launch=adb('shell','am','start','-W','-n',native_package+'/'+native_package+'.Main')
    (out/'native-launch.txt').write_text(native_launch.stdout)
    native_passed=False;native_text='';until=time.monotonic()+25
    try:
        while time.monotonic()<until:
            adb('shell','uiautomator','dump','/data/local/tmp/tripoder-ingress-native.xml',check=False,timeout=20)
            raw=adb('shell','cat','/data/local/tmp/tripoder-ingress-native.xml',check=False)
            if raw.returncode==0:
                tree=ET.fromstring(raw.stdout)
                texts=[n.attrib.get('text','') for n in tree.iter('node') if n.attrib.get('package')==native_package]
                native_text='\n'.join(texts)
                if 'INGRESS_GUEST_PASS' in texts:native_passed=True;break
                if any(t.startswith('INGRESS_GUEST_FAIL') for t in texts):break
            time.sleep(.2)
    finally:adb('shell','am','force-stop',native_package)
    (out/'native-fixture.json').write_text(json.dumps({'passed':native_passed,'text':native_text,'sha256':sha(fixture)},indent=2))
    if not native_passed:raise RuntimeError('NATIVE_FIXTURE_FAILED')
    results=[]
    for runner in ('RuntimeIngressIntegration','GuestIngressIntegration'):
        run='control-'+uuid.uuid4().hex[:16]
        command=['adb','-s',args.serial,'shell','am','instrument','-w','-r','-e','runId',run,'dev.tripoder.controltest/dev.tripoder.controltest.'+runner]
        process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            if runner=='GuestIngressIntegration':
                until=time.monotonic()+20
                while process.poll() is None and time.monotonic()<until:
                    signal=adb('shell','cat','/sdcard/Android/data/'+package+'/files/launch-workbench-'+run+'.json',check=False)
                    if signal.returncode==0:
                        requested=json.loads(signal.stdout)
                        if requested!={'runId':run,'component':package+'/dev.tripoder.hooklab.WorkbenchActivity'}:raise RuntimeError('WORKBENCH_REQUEST_MISMATCH')
                        adb('shell','am','start','-W','-n',requested['component'])
                        if args.retry_engine_once:
                            for _ in range(4):
                                window='/data/local/tmp/tripoder-ingress-reconnect.xml'
                                adb('shell','uiautomator','dump',window,check=False,timeout=15)
                                snapshot=adb('shell','cat',window,check=False)
                                if snapshot.returncode:continue
                                owned=[n for n in ET.fromstring(snapshot.stdout).iter('node') if n.get('package')==package]
                                button=next((n for n in owned if n.get('text')=='重新连接' and n.get('clickable')=='true'),None)
                                if any('SUPERVISOR_INTERRUPTED' in n.get('text','') for n in owned) and button is not None:
                                    bounds=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',button.get('bounds',''))
                                    if bounds is None:raise RuntimeError('RECONNECT_BOUNDS_INVALID')
                                    x1,y1,x2,y2=map(int,bounds.groups())
                                    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                                    (out/'explicit-reconnect.json').write_text(json.dumps({'runId':run,'reason':'SUPERVISOR_INTERRUPTED','count':1,'guestOperationReplayed':False}))
                                    break
                                if button is None:break
                                time.sleep(.2)
                        break
                    time.sleep(.1)
                else:raise RuntimeError('WORKBENCH_REQUEST_TIMEOUT')
            stdout,stderr=process.communicate(timeout=150)
            (out/(runner+'-instrumentation.txt')).write_text(stdout+stderr)
            raw=adb('shell','cat','/sdcard/Android/data/'+package+'/files/result-'+run+'.json').stdout
            (out/(runner+'-result.json')).write_text(raw)
            digests=re.findall(r'resultSha256=([a-f0-9]{64})',stdout)
            if digests!=[hashlib.sha256(raw.encode()).hexdigest()]:raise RuntimeError('RESULT_DIGEST_MISMATCH')
            data=json.loads(raw);results.append(data)
            print(json.dumps({'runner':runner,'passed':data.get('passed'),'checks':len(data['checks']),'error':data.get('error')}),flush=True)
            if not data.get('passed'):break
        finally:
            if process.poll() is None:process.kill();process.communicate()
    uid=adb('shell','cmd','package','list','packages','-U',package).stdout.strip().split('uid:')[-1]
    (out/'logcat-qa.txt').write_text(adb('logcat','-d','--uid='+uid,check=False).stdout)
    summary={'passed':native_passed and len(results)==2 and all(r.get('passed') for r in results),'nativeFixturePassed':native_passed,'results':[{'runId':r['runId'],'passed':r['passed'],'checks':len(r['checks'])} for r in results],'commercialApplicationsAccepted':False}
    (out/'summary.json').write_text(json.dumps(summary,indent=2))
    adb('shell','am','force-stop',package)
    if not summary['passed']:raise SystemExit('Ingress failure evidence retained in '+str(out))


if __name__=='__main__':main()
