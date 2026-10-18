#!/usr/bin/env python3
"""Validate the separate-UID control foundation on owned development APKs. Not full P5 or P6 acceptance."""
import argparse
import datetime
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import zipfile


def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def crash_status(log):
    return {
        'nativeFatalSignalObserved': bool(re.search(r'Fatal signal \d+ ', log)),
        'javaFatalExceptionObserved': 'FATAL EXCEPTION:' in log,
    }


def self_hook_samples(log):
    return [{'pid': int(pid), 'bindings': int(bindings), 'durationNs': int(duration)}
            for pid, bindings, duration in re.findall(
                r'^\S+\s+\S+\s+(\d+)\s+\d+\s+I\s+TripoderSelfHook\s*:\s+READY:READY:27/27:bindings=(\d+):ns=(\d+)$',
                log, re.MULTILINE)]


def verify_runtime_status(status, *, pid, uid, generation, core_path, native_sha256, native_size):
    """Independently validate the bounded worker observation against this run and APK."""
    def require(value, code):
        if not value:raise RuntimeError('RUNTIME_MAPPING_'+code)
    require(type(pid) is int and pid>0 and type(uid) is int and uid>0 and isinstance(generation,str) and bool(generation),'EXPECTED_IDENTITY')
    require(isinstance(status,dict) and isinstance(status.get('identity'),dict),'STATUS_MISSING')
    identity=status['identity'];value=status.get('runtimeNativeMappings')
    require(identity.get('pid')==pid and identity.get('processGeneration')==generation,'STATUS_IDENTITY')
    require(isinstance(value,dict),'MISSING')
    require(type(value.get('schema')) is int and value['schema']==1,'SCHEMA')
    require(type(value.get('pid')) is int and value['pid']==pid and type(value.get('uid')) is int and value['uid']==uid and value.get('processGeneration')==generation,'PROCESS_IDENTITY')
    physical=value.get('physicalProcess')
    require(isinstance(physical,str) and re.fullmatch(r'dev\.tripoder\.engine:slot[0-7]',physical) is not None,'PHYSICAL_PROCESS')
    require(value.get('coreSha256')==native_sha256 and re.fullmatch(r'[a-f0-9]{64}',native_sha256) is not None,'CORE_SHA256')
    require(type(value.get('coreSize')) is int and value['coreSize']==native_size and 0<native_size<=64*1024*1024,'CORE_SIZE')
    require(value.get('corePath')==core_path and re.fullmatch(r'/data/app/(?:[^/]+/)+lib/arm64/libtripoderselfhook\.so',core_path) is not None and all(part not in ('.','..') for part in core_path.split('/')),'CORE_PATH')
    require(value.get('foreignBackendRows')==[],'FOREIGN_BACKEND')
    inode=value.get('coreInode');rows=value.get('coreRows');paths_joined=value.get('nativePaths')
    require(isinstance(paths_joined,str) and len(paths_joined)<=32000,'NATIVE_PATHS');paths=paths_joined.split('\n')
    require(isinstance(inode,str) and re.fullmatch(r'[1-9][0-9]*',inode) is not None,'INODE')
    require(isinstance(rows,list) and 1<=len(rows)<=16 and all(isinstance(row,str) and '\n' not in row for row in rows),'ROWS')
    require(1<=len(paths)<=1024 and all(path.startswith('/') and '\n' not in path for path in paths),'NATIVE_PATHS')
    require(len(set(paths))==len(paths) and paths.count(core_path)==1 and len('\n'.join(paths))<=32000,'NATIVE_PATHS')
    require(not any(name in path.lower() for path in paths for name in ('frida','gadget','tripoderio')),'FOREIGN_NATIVE_PATH')
    core_names=[path for path in paths if path.endswith('/libtripoderselfhook.so') or path.endswith('/libtripoderselfhook.so (deleted)')]
    require(core_names==[core_path],'CORE_PATH_ALIAS')
    pattern=r'([0-9a-f]+)-([0-9a-f]+) ([r-][w-][x-][ps]) ([0-9a-f]+) ([0-9a-f]+):([0-9a-f]+) ([0-9]+) +(.+)'
    previous_end=0;device=None;executable=False
    for line in rows:
        row=re.fullmatch(pattern,line);require(row is not None,'ROW_FORMAT')
        begin,end=int(row[1],16),int(row[2],16);permissions=row[3];offset=int(row[4],16);row_device=(int(row[5],16),int(row[6],16))
        require(begin>=previous_end and end>begin and begin%4096==0 and end%4096==0 and offset%4096==0 and offset<native_size,'ROW_RANGE')
        require(row[8]==core_path and row[7]==inode and row_device!=(0,0),'ROW_IDENTITY')
        if device is None:device=row_device
        require(device==row_device,'ROW_DEVICE')
        require(not ('w' in permissions and 'x' in permissions),'ROW_WX')
        executable|='x' in permissions;previous_end=end
    require(executable,'EXECUTABLE_MISSING')
    require(type(value.get('mapsBytes')) is int and sum(len(row.encode())+1 for row in rows)<=value['mapsBytes']<=2*1024*1024,'MAPS_BYTES')
    require(type(value.get('mapsRowCount')) is int and value['mapsRowCount']>=len(rows),'MAPS_ROWS')
    require(all(isinstance(value.get(key),str) and re.fullmatch(r'[a-f0-9]{64}',value[key]) is not None for key in ('mapsSha256','nativeRowsSha256')),'MAPS_DIGEST')
    return {'passed':True,'pid':pid,'uid':uid,'processGeneration':generation,'physicalProcess':physical,'corePath':core_path,'coreSha256':native_sha256,'coreSize':native_size,'coreMappingRows':len(rows),'mapsSha256':value['mapsSha256'],'mapsRowCount':value['mapsRowCount'],'mapsBytes':value['mapsBytes'],'nativePaths':len(paths),'foreignBackendRows':0}


def product_native_identity(receipt):
    with zipfile.ZipFile(receipt['artifacts']['engine']['path']) as archive:
        member='lib/arm64-v8a/libtripoderselfhook.so'
        if sorted(name for name in archive.namelist() if name.startswith('lib/'))!=[member]:raise RuntimeError('PRODUCT_NATIVE_PACKAGING_MISMATCH')
        value=archive.read(member)
    digest=hashlib.sha256(value).hexdigest()
    if digest!=receipt['selfHookNative']['sha256']:raise RuntimeError('PRODUCT_NATIVE_RECEIPT_MISMATCH')
    return digest,len(value)




def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--evidence',required=True,type=Path)
    parser.add_argument('--install',action='store_true')
    parser.add_argument('--phase',choices=['control','hook-ui','library-ui','app-actions-ui','target','resources'],default='control')
    parser.add_argument('--native-cycles',type=int,default=1)
    parser.add_argument('--target',choices=['wechat','honor','wps'])
    parser.add_argument('--wake',action='store_true',help='Target phase: after the guest is running, background it and launch again; the same process must return to the front')
    parser.add_argument('--native-hook',action='store_true',help='Verify the original target xlog native query through the production Hook control path')
    parser.add_argument('--managed-jni-hook',action='store_true',help='Verify the original WeChat native Java declaration through ART with original forwarding')
    parser.add_argument('--inspect-pending',action='store_true',help='Read existing pending launch ownership without reclaiming tasks (resources phase only)')
    parser.add_argument('--close-launch-entry',help='Explicit exact UNKNOWN launch operation to close before a new target launch; never retires its resources')
    parser.add_argument('--close-activity-ticket',action='append',help='Exact observed unattached guest Activity route ticket; close its UNKNOWN operation before the next launch')
    parser.add_argument('--list-entries',action='store_true',help='Target phase: list retained launch entries and stop without launching')
    parser.add_argument('--close-only',action='store_true',help='Target phase: close the exact entry given and stop without launching')
    parser.add_argument('--diagnose-uninstall',action='store_true',help='Target phase: run the real uninstall once and record the exact failure, without launching')
    parser.add_argument('--list-catalog',action='store_true',help='Target phase: list installs, projects and spaces and stop, changing nothing')
    parser.add_argument('--reverify-install',nargs=3,metavar=('SPACE','PACKAGE','GENERATION'),help='Target phase: re-run the full verification of one CORRUPT install and restore it only if every check passes')
    parser.add_argument('--delete-application',action='store_true',help='Target phase: delete the target application, its data and projects with the engine command behind the user\'s Delete, and stop')
    args=parser.parse_args();repo=Path(__file__).resolve().parents[2];build=repo/'android/product/build'
    if args.inspect_pending and args.phase!='resources':parser.error('--inspect-pending requires --phase resources')
    if not 1<=args.native_cycles<=24 or (args.native_cycles!=1 and args.phase!='control'):parser.error('--native-cycles requires control phase and a value from 1 to 24')
    if args.phase=='target' and args.target is None:parser.error('--target is required for target phase')
    if args.native_hook and (args.phase!='target' or args.target=='wps'):parser.error('--native-hook requires target phase and a wechat or honor target')
    if args.managed_jni_hook and (args.phase!='target' or args.target!='wechat'):parser.error('--managed-jni-hook requires target wechat')
    if args.close_activity_ticket is not None:
        if args.phase!='target' or args.close_launch_entry is not None:parser.error('--close-activity-ticket requires target phase and cannot be combined with --close-launch-entry')
        if len(args.close_activity_ticket)>16 or len(set(args.close_activity_ticket))!=len(args.close_activity_ticket):parser.error("Provide at most 16 distinct exact observed Activity tickets")
        for ticket in args.close_activity_ticket:uuid.UUID(ticket)
    if args.close_launch_entry is not None:
        if args.phase!='target':parser.error('--close-launch-entry requires target phase')
        uuid.UUID(args.close_launch_entry)
    receipt=json.loads((build/'build-receipt.json').read_text())
    for name,digest in receipt['sourceSha256'].items():
        if sha(repo/name)!=digest:raise RuntimeError('SOURCE_CHANGED_SINCE_BUILD:'+name)
    for artifact in receipt['artifacts'].values():
        if sha(artifact['path'])!=artifact['sha256']:raise RuntimeError('APK_CHANGED_SINCE_BUILD:'+artifact['path'])
    native_sha256,native_size=product_native_identity(receipt);engine_core_path=None
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    commands=[];owned_uid=None;owned_uids={};device_start=None;report={'schema':1,'startedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'validatorSha256':sha(__file__),'commands':commands,'fullP5Accepted':False,'businessScenariosVerified':False,'rootUsed':False,'permissionGrantsPerformed':False}
    def adb(*command,check=True,timeout=60):
        result=subprocess.run(['adb','-s',args.serial,*command],text=True,capture_output=True,timeout=timeout)
        if command[:2]!=('shell','cat'):commands.append({'command':command,'exitCode':result.returncode,'stdout':result.stdout,'stderr':result.stderr})
        if check and result.returncode:raise RuntimeError(result.stdout+result.stderr)
        return result
    def instrument(label,runner,target_package,**extras):
        run='control-'+uuid.uuid4().hex[:16];command=['shell','am','instrument','-w','-e','runId',run]
        for key,value in extras.items():command+=['-e',key,str(value)]
        command+=['dev.tripoder.controltest/dev.tripoder.controltest.'+runner]
        if runner in ['ControlIntegration','HookUiIntegration','LibraryUiIntegration','AppActionsUiIntegration','TargetLaunchIntegration','ControlResourceInspection']:
            process=subprocess.Popen(['adb','-s',args.serial,*command],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            try:
                deadline=time.monotonic()+15
                while process.poll() is None:
                    signal=adb('shell','cat','/sdcard/Android/data/'+target_package+'/files/launch-workbench-'+run+'.json',check=False,timeout=5)
                    if signal.returncode==0:
                        request=json.loads(signal.stdout)
                        if request!={'runId':run,'component':'dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity'}:raise RuntimeError('LAUNCH_REQUEST_MISMATCH')
                        adb('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',request['component']);break
                    if time.monotonic()>=deadline:raise RuntimeError('LAUNCH_REQUEST_TIMEOUT')
                    time.sleep(.1)
                stdout,stderr=process.communicate(timeout=300 if runner=='TargetLaunchIntegration' else 120);output=subprocess.CompletedProcess(command,process.returncode,stdout,stderr)
                commands.append({'command':command,'exitCode':output.returncode,'stdout':stdout,'stderr':stderr})
            except Exception:
                progress=adb('shell','cat','/sdcard/Android/data/'+target_package+'/files/progress-'+run+'.json',check=False)
                (evidence/(label+'-progress.json')).write_text(progress.stdout if progress.returncode==0 else json.dumps({'runId':run,'progressUnavailable':progress.stderr}))
                if runner=='TargetLaunchIntegration':
                    try:
                        threads=adb('shell','cat','/sdcard/Android/data/'+target_package+'/files/'+run+'-thread-stall.txt',check=False)
                        (evidence/(label+'-thread-stall.txt')).write_text(threads.stdout if threads.returncode==0 else threads.stderr)
                        authority=adb('shell','dumpsys','activity','service','dev.tripoder.engine/dev.tripoder.runtime.RuntimeControllerService','threads',check=False,timeout=15)
                        (evidence/'authority-threads.txt').write_text(authority.stdout+authority.stderr)
                    except Exception as diagnostic:
                        (evidence/'thread-collection-error.txt').write_text(str(diagnostic))
                raise
            finally:
                if process.poll() is None:process.kill();process.communicate();adb('shell','am','force-stop',target_package)
        else:output=adb(*command,timeout=90)
        (evidence/(label+'-instrumentation.txt')).write_text(output.stdout+output.stderr)
        remote='/sdcard/Android/data/'+target_package+'/files/result-'+run+'.json'
        raw=adb('shell','cat',remote).stdout
        digest=re.findall(r'^INSTRUMENTATION_RESULT: resultSha256=([a-f0-9]{64})\s*$',output.stdout,re.MULTILINE)
        if digest!=[hashlib.sha256(raw.encode()).hexdigest()]:raise RuntimeError('DEVICE_RESULT_RECEIPT_MISMATCH:'+label)
        result=json.loads(raw);(evidence/(label+'-result.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
        for name in result.get('captures',[]):
            if not re.fullmatch(re.escape(run)+r'-[a-z-]+\.png',name):raise RuntimeError('SCREENSHOT_NAME_INVALID')
            adb('pull','/sdcard/Android/data/'+target_package+'/files/'+name,str(evidence/name))
        if result.get('runId')!=run or not result.get('passed') or not result.get('checks') or not all(item.get('passed') is True for item in result['checks']):raise RuntimeError('DEVICE_ASSERTIONS_FAILED:'+label)
        return result
    try:
        if adb('get-state').stdout.strip()!='device':raise RuntimeError('DEVICE_NOT_READY')
        if args.install:
            for package in ['dev.tripoder.engine','dev.tripoder.controltest']:adb('shell','am','force-stop',package)
            for name in ['engine','controlTest']:adb('install','--no-incremental','-r',receipt['artifacts'][name]['path'],timeout=90)
        for artifact in receipt['artifacts'].values():
            path=adb('shell','pm','path',artifact['package']).stdout.strip().removeprefix('package:')
            if '\n' in path or not path.startswith('/data/app/') or not path.endswith('/base.apk'):raise RuntimeError('INSTALLED_APK_PATH_INVALID')
            if adb('shell','sha256sum',path).stdout.split()[0]!=artifact['sha256']:raise RuntimeError('INSTALLED_APK_DIGEST_MISMATCH:'+artifact['package'])
            if artifact['package']=='dev.tripoder.engine':engine_core_path=str(Path(path).parent/'lib/arm64/libtripoderselfhook.so')
        adb('shell','am','force-stop','dev.tripoder.controltest');adb('shell','am','force-stop','dev.tripoder.engine')
        uid_result=adb('shell','cmd','package','list','packages','-U','dev.tripoder.engine').stdout
        match=re.fullmatch(r'package:dev\.tripoder\.engine uid:(\d+)\s*',uid_result)
        if match is None:raise RuntimeError('ENGINE_UID_UNAVAILABLE')
        owned_uid=int(match[1]);device_start=adb('shell','date "+%m-%d %H:%M:%S.%3N"').stdout.strip()
        for package in ['dev.tripoder.engine','dev.tripoder.controltest']:
            uid_result=adb('shell','cmd','package','list','packages','-U',package).stdout
            match=re.fullmatch(re.escape('package:'+package)+r' uid:(\d+)\s*',uid_result)
            if match is None:raise RuntimeError('OWNED_UID_UNAVAILABLE:'+package)
            owned_uids[package]=int(match[1])
        if not re.fullmatch(r'\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}',device_start):raise RuntimeError('DEVICE_CLOCK_UNAVAILABLE')
        report['deviceLogStart']=device_start;report['ownedUid']=owned_uid;report['ownedUids']=owned_uids
        if args.phase=='target':
            source=repo/'android/runtime-probe/build/targets'/(args.target+'.apk')
            expected={'wechat':'f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d','honor':'6841b1eae92b3d87b00e986f86086cbe436bcb52e4b6a3c6829f368714538384','wps':'71df658765efeaa59e81733046b66ca7e94430eece14b9f996afa7c1b15e3ff7'}[args.target]
            if sha(source)!=expected:raise RuntimeError('ORIGINAL_TARGET_DIGEST_MISMATCH')
            destination='/sdcard/Android/data/dev.tripoder.engine/files/acceptance'
            adb('shell','mkdir','-p',destination);adb('push',str(source),destination+'/'+args.target+'.apk',timeout=150)
            report['targetApkSha256']=expected
            explicit={'closeLaunchEntry':args.close_launch_entry} if args.close_launch_entry is not None else {}
            if args.close_activity_ticket is not None:explicit['closeActivityTicket']=','.join(args.close_activity_ticket)
            if args.list_entries:explicit['listEntries']='true'
            if args.list_catalog:explicit['listCatalog']='true'
            if args.delete_application:explicit['deleteApplication']='true'
            if args.reverify_install:explicit.update({'reverifySpace':args.reverify_install[0],'reverifyPackage':args.reverify_install[1],'reverifyGeneration':args.reverify_install[2]})
            if args.diagnose_uninstall:explicit['diagnoseUninstall']='true'
            if args.close_only:explicit['closeOnly']='true'
            if args.wake:explicit['wake']='true'
            if args.native_hook:explicit['nativeHook']='true'
            if args.managed_jni_hook:explicit['managedJniHook']='true'
            first=instrument('target-'+args.target,'TargetLaunchIntegration','dev.tripoder.engine',target=args.target,**explicit);results=[first];engine_uid=first['workerUid']
        elif args.phase=='resources':
            first=instrument('resources','ControlResourceInspection','dev.tripoder.engine',inspectionOnly='true' if args.inspect_pending else 'false');results=[first];engine_uid=first['workerUid']
        elif args.phase=='library-ui':
            first=instrument('library-ui','LibraryUiIntegration','dev.tripoder.engine');results=[first];engine_uid=first['workerUid']
        elif args.phase=='app-actions-ui':
            first=instrument('app-actions-ui','AppActionsUiIntegration','dev.tripoder.engine');results=[first];engine_uid=first['workerUid']
        elif args.phase=='hook-ui':
            first=instrument('hook-ui','HookUiIntegration','dev.tripoder.engine');results=[first];engine_uid=first['workerUid']
        else:
            first=instrument('controller','ControlIntegration','dev.tripoder.engine',nativeCycles=args.native_cycles)
            adb('shell','am','force-stop','dev.tripoder.engine')
            recovered=instrument('recovery','ControlIntegration','dev.tripoder.engine',phase='recovery',**{key:first[key] for key in ['operation','space','project','installGeneration','bindingOperation','dataId','readyLaunchOperation']})
            if first['status']['authority']==recovered['status']['authority']:raise RuntimeError('AUTHORITY_DEATH_NOT_OBSERVED')
            results=[first,recovered];engine_uid=first['status']['engineUid']
        if engine_uid!=owned_uid:raise RuntimeError('ENGINE_UID_RECEIPT_MISMATCH')
        if args.phase in ('control','hook-ui'):
            if args.phase=='control':statuses=[first.get('runtimeStatus')]
            else:
                required={'actual-ui-calls-hit-worker-hook','explicit-next-launch-keeps-original-behavior'}
                selected=[item for item in first['checks'] if item.get('name') in required]
                if len(selected)!=2 or {item['name'] for item in selected}!=required:raise RuntimeError('RUNTIME_MAPPING_UI_STATUS_MISSING')
                statuses=[item.get('observed') for item in selected]
            summaries=[verify_runtime_status(status,pid=first['workerPid'],uid=owned_uid,generation=first['processGeneration'],core_path=engine_core_path,native_sha256=native_sha256,native_size=native_size) for status in statuses]
            report['runtimeNativeMappingVerification']=summaries
            (evidence/'runtime-native-mapping-verification.json').write_text(json.dumps(summaries,ensure_ascii=False,indent=2))
        report['phase']=args.phase;report['checks']=sum(len(item['checks']) for item in results);report['controllerUid']=first['uid'];report['engineUid']=engine_uid;report['passed']=True
        if 'workerPid' in first:report['workerPid']=first['workerPid']
        adb('shell','am','force-stop','dev.tripoder.controltest')
        if args.phase!='target':adb('shell','am','start','-W','-n','dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity')
    except Exception as error:report['passed']=False;report['error']=str(error);raise
    finally:
        if owned_uid is not None and device_start is not None:
            for buffer,name,filters in [('crash','engine-crash.txt',[]),('main','engine-runtime.txt',['TripoderSelfHook:I','TripoderRuntime:V','TripoderHook:I','TripoderOwnership:I','TripoderStartup:I','TripoderProvider:I','*:S'])]:
                try:
                    logs=adb('logcat','-b',buffer,'-d','--uid='+str(owned_uid),'-T',device_start,*filters,check=False)
                    (evidence/name).write_text(logs.stdout)
                    if logs.returncode:report['passed']=False;report['logCollectionFailed']=True
                    if buffer=='crash':
                        status=crash_status(logs.stdout);report.update(status)
                        if any(status.values()):report['passed']=False
                    else:report['selfHookInstallationSamples']=self_hook_samples(logs.stdout)
                except Exception as error:
                    report['passed']=False;report['logCollectionFailed']=True;report['logCollectionError']=str(error)
            report['ownedCrashStatus']={}
            for package,uid in owned_uids.items():
                if uid==owned_uid:continue
                try:
                    logs=adb('logcat','-b','crash','-d','--uid='+str(uid),'-T',device_start,check=False)
                    (evidence/(package+'-crash.txt')).write_text(logs.stdout)
                    status=crash_status(logs.stdout);report['ownedCrashStatus'][package]=status
                    if any(status.values()):report['passed']=False;report['ownedFatalObserved']=True
                    if logs.returncode:report['passed']=False;report['logCollectionFailed']=True
                except Exception as error:
                    report['passed']=False;report['logCollectionFailed']=True;report['logCollectionError']=str(error)
        if args.phase=='hook-ui' and report.get('passed') and not any(item['pid']==report.get('workerPid') and item['bindings'] in (2270,2271) for item in report.get('selfHookInstallationSamples',[])):
            report['passed']=False;report['workerBootstrapEvidenceMissing']=True
        report['finishedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat();(evidence/'device-observation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));(evidence/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    if report.get('nativeFatalSignalObserved') or report.get('javaFatalExceptionObserved') or report.get('ownedFatalObserved') or report.get('logCollectionFailed') or report.get('workerBootstrapEvidenceMissing'):raise RuntimeError('OWNED_LOG_EVIDENCE_FAILED')
    print(json.dumps({key:report[key] for key in ['passed','checks','controllerUid','engineUid','fullP5Accepted']}),flush=True)


if __name__=='__main__':main()
