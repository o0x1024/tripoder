#!/usr/bin/env python3
"""Observe owned Provider/resource fixtures as a normal Android package, not inside Tripoder."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import uuid


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--serial',required=True);parser.add_argument('--evidence',required=True,type=Path);parser.add_argument('--install',action='store_true');parser.add_argument('--scenario',choices=['multiprocess','resources','creation','service-startup','service-passive','activity-flags'],default='multiprocess');parser.add_argument('--mode',choices=['direct','super','super-replace'],default='direct');args=parser.parse_args()
    root=Path(__file__).resolve().parents[2];build=root/'android/runtime-probe/build';receipt=json.loads((build/'build-receipt.json').read_text());fixture_key,apk_name={'creation':('instrumentationWorld','instrumentation-world.apk'),'service-startup':('serviceStartup','service-startup.apk'),'service-passive':('servicePassive','service-passive.apk')}.get(args.scenario,('providerIdentity','provider-identity.apk'));expected=receipt['p2Fixtures'][fixture_key]['sha256'];apk=build/apk_name
    if hashlib.sha256(apk.read_bytes()).hexdigest()!=expected:raise RuntimeError('FIXTURE_CHANGED')
    for name,digest in receipt['sourceSha256'].items():
        if name.startswith('android/runtime-fixtures/service-startup/' if args.scenario in ['service-startup','service-passive'] else 'android/runtime-fixtures/provider-identity/') and hashlib.sha256((root/name).read_bytes()).hexdigest()!=digest:raise RuntimeError('FIXTURE_SOURCE_CHANGED')
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False);run=args.scenario+'-'+uuid.uuid4().hex[:16];commands=[];report={'runId':run,'scope':'normal-android-provider-baseline','scenario':args.scenario,'fixtureSha256':expected,'commands':commands,'passed':False,'tripoderRuntimeVerified':False}
    def adb(*command,check=True):
        result=subprocess.run(['adb','-s',args.serial,*command],capture_output=True,text=True,timeout=90)
        if command[:2]!=('shell','cat'):commands.append({'command':command,'exitCode':result.returncode,'stdout':result.stdout,'stderr':result.stderr})
        if check and result.returncode:raise RuntimeError(result.stdout+result.stderr)
        return result
    try:
        if args.install:adb('install','--no-incremental','-r',str(apk))
        pkg='dev.tripoder.provideridentity';path=adb('shell','pm','path',pkg).stdout.strip().removeprefix('package:')
        if not path.startswith('/data/app/') or '\n' in path or not path.endswith('/base.apk'):raise RuntimeError('INSTALLED_FIXTURE_PATH_INVALID')
        if adb('shell','sha256sum',path).stdout.split()[0]!=expected:raise RuntimeError('INSTALLED_FIXTURE_CHANGED')
        # A retained task can be brought forward without onCreate/runId delivery on this OEM.
        # Each baseline owns a fresh task, so its result must belong to this invocation.
        adb('shell','am','force-stop',pkg);adb('shell','am','start','-W','-f','0x10008000','-n',pkg+('/.MultiprocessActivity' if args.scenario=='multiprocess' else '/.InstrumentationActivity' if args.scenario=='creation' else '/dev.tripoder.servicestartup.StartupActivity' if args.scenario=='service-startup' else '/dev.tripoder.servicestartup.PassiveActivity' if args.scenario=='service-passive' else '/.FlagTopActivity' if args.scenario=='activity-flags' else '/.PackageResourcesActivity'),'--es','baseline','true','--es','runId',run,'--es','mode',args.mode)
        deadline=time.monotonic()+(110 if args.scenario=='resources' else 40);result=None
        while time.monotonic()<deadline:
            fetched=adb('shell','cat','/sdcard/Android/data/'+pkg+'/files/result-'+run+'.json',check=False)
            if fetched.returncode==0:result=json.loads(fetched.stdout);break
            time.sleep(.2)
        if result is None:raise RuntimeError('BASELINE_RESULT_UNKNOWN')
        (evidence/'device-result.json').write_text(json.dumps(result,indent=2,ensure_ascii=False))
        if result.get('completed') is not True:raise RuntimeError('BASELINE_NOT_COMPLETED')
        if args.scenario in ['resources','creation','service-startup','service-passive','activity-flags']:
            checks=result['checks']
            required={'service-declared-remote-process-preserved','disabled-service-excluded-by-default','disabled-service-explicit-match','activity-manager-application-create-observed-self','activity-manager-observes-real-caller','activity-manager-memory-info-complete','application-load-xml-metadata'}
            if args.scenario=='activity-flags':required={'standard-root-in-own-task','single-top-delivers-to-existing-standard-instance-2','standard-without-flag-creates-new-instance','single-top-below-another-activity-creates-new-instance','real-child-destruction-and-root-return'}
            if args.scenario=='creation':required={'instrumentation-receives-logical-class-and-component','replacement-attach-base-context-is-logical','runtime-default-thermal-callback-on-real-main','super-then-replace-child-registers-final-object','replacement-child-real-result'}
            if args.scenario=='service-passive':required={'non-creating-binding-accepted','native-non-creating-bind-has-no-target-process','pending-binding-receives-real-remote-binder','promoted-service-real-onDestroy-observed'}
            if args.scenario=='service-startup':
                required={'startService-returns-logical-component-during-attach','real-service-start-and-bind-callbacks','service-really-runs-in-declared-remote-process'}
                checks.append({'name':'native-non-creating-bind-accepted','passed':result.get('bindWithoutCreate')=='accepted'})
            if not required.issubset({check['name'] for check in checks}):raise RuntimeError('RESOURCE_BASELINE_CHECKS_INCOMPLETE')
        else:
            checks=[]
            for key in ['main','peer']:
                item=result[key];checks.append({'name':key+'-lazy-local-single-instance','passed':item['before']==0 and item['after']==1 and item['first']['pid']==item['pid'] and item['again']['instance']==item['first']['instance'] and item['first']['creations']==1})
            checks.append({'name':'processes-own-distinct-provider-instances','passed':result['main']['pid']!=result['peer']['pid'] and result['main']['first']['instance']!=result['peer']['first']['instance']})
            checks.append({'name':'later-acquire-retains-local-instance','passed':result['mainAfterPeer']['before']==1 and result['mainAfterPeer']['first']['instance']==result['main']['first']['instance']})
        report['checks']=checks;report['passed']=all(item['passed'] for item in checks)
        if not report['passed']:raise RuntimeError('BASELINE_DIFFERS_FROM_DECLARED_CONTRACT')
        print(json.dumps({'passed':True,'checks':len(checks),'runId':run,'tripoderRuntimeVerified':False}),flush=True)
    except Exception as error:report['error']=str(error);raise
    finally:
        (evidence/'device-observation.json').write_text(json.dumps(report,indent=2,ensure_ascii=False));shutil.copyfile(build/'build-receipt.json',evidence/'build-receipt.json')


if __name__=='__main__':main()
