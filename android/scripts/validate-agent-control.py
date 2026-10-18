#!/usr/bin/env python3
"""Owned fixture MCP transport and phone UI acceptance. Does not claim Sentinel/model or P6 acceptance."""
import argparse, datetime, hashlib, json, re, shutil, subprocess, time, uuid, zipfile
from pathlib import Path

def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

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


def agent_status_receipts(transcript):
    requests={};statuses=[]
    for entry in transcript:
        message=entry.get('message',{})
        if entry.get('direction')=='request' and message.get('method')=='tools/call':requests[message.get('id')]=message.get('params',{}).get('name')
        if entry.get('direction')!='response' or requests.get(message.get('id'))!='hook_status':continue
        result=message.get('result',{});content=result.get('content',[])
        if result.get('isError') is not False or len(content)!=1 or content[0].get('type')!='text':raise RuntimeError('RUNTIME_MAPPING_MCP_STATUS_REJECTED')
        receipt=json.loads(content[0]['text'])
        if receipt.get('ok') is not True or not isinstance(receipt.get('result'),dict):raise RuntimeError('RUNTIME_MAPPING_MCP_STATUS_REJECTED')
        statuses.append(receipt)
    if not statuses:raise RuntimeError('RUNTIME_MAPPING_MCP_STATUS_MISSING')
    return statuses


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--serial',required=True);parser.add_argument('--evidence',type=Path,required=True);parser.add_argument('--install',action='store_true');parser.add_argument('--phase',choices=['normal','disconnect','sentinel'],default='normal');args=parser.parse_args()
    repo=Path(__file__).resolve().parents[2];build=repo/'android/product/build';receipt=json.loads((build/'build-receipt.json').read_text())
    for name,digest in receipt['sourceSha256'].items():
        if sha(repo/name)!=digest:raise RuntimeError('SOURCE_CHANGED_SINCE_BUILD:'+name)
    for artifact in receipt['artifacts'].values():
        if sha(artifact['path'])!=artifact['sha256']:raise RuntimeError('APK_CHANGED_SINCE_BUILD')
    native_sha256,native_size=product_native_identity(receipt);engine_core_path=None
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False);run='control-'+uuid.uuid4().hex[:16];commands=[];instrument=None;start=None;uids={};report={'schema':1,'runId':run,'sentinelClientVerified':False,'modelToolUseVerified':False,'fullP5Accepted':False,'businessScenariosVerified':False,'commands':commands,'startedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    def adb(*command,check=True,timeout=60):
        result=subprocess.run(['adb','-s',args.serial,*command],text=True,capture_output=True,timeout=timeout)
        if command[:2]!=('shell','cat'):commands.append({'command':command,'exitCode':result.returncode,'stdout':result.stdout,'stderr':result.stderr})
        if check and result.returncode:raise RuntimeError(result.stdout+result.stderr)
        return result
    def wait_json(name,timeout=45):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            result=adb('shell','cat','/sdcard/Android/data/dev.tripoder.engine/files/'+name,check=False,timeout=5)
            if result.returncode==0:
                value=json.loads(result.stdout)
                if value['runId']!=run:raise RuntimeError('RUN_MISMATCH')
                return value
            if instrument is not None and instrument.poll() is not None:raise RuntimeError('INSTRUMENTATION_ENDED_BEFORE:'+name)
            time.sleep(.2)
        raise RuntimeError('DEVICE_MILESTONE_TIMEOUT:'+name)
    try:
        if adb('get-state').stdout.strip()!='device':raise RuntimeError('DEVICE_NOT_READY')
        if args.install:
            for package in ['dev.tripoder.engine','dev.tripoder.controltest']:adb('shell','am','force-stop',package)
            for name in ['engine','controlTest']:adb('install','--no-incremental','-r',receipt['artifacts'][name]['path'],timeout=90)
        for artifact in receipt['artifacts'].values():
            path=adb('shell','pm','path',artifact['package']).stdout.strip().removeprefix('package:')
            if '\n' in path or not path.startswith('/data/app/') or not path.endswith('/base.apk'):raise RuntimeError('INSTALLED_APK_PATH_INVALID')
            if adb('shell','sha256sum',path).stdout.split()[0]!=artifact['sha256']:raise RuntimeError('INSTALLED_APK_DIGEST_MISMATCH')
            if artifact['package']=='dev.tripoder.engine':engine_core_path=str(Path(path).parent/'lib/arm64/libtripoderselfhook.so')
        for package in ['dev.tripoder.controltest','dev.tripoder.engine']:adb('shell','am','force-stop',package)
        for package in ['dev.tripoder.engine','dev.tripoder.controltest']:
            uid=adb('shell','cmd','package','list','packages','-U',package).stdout;match=re.fullmatch(re.escape('package:'+package)+r' uid:(\d+)\s*',uid)
            if not match:raise RuntimeError('UID_UNAVAILABLE')
            uids[package]=int(match[1])
        start=adb('shell','date "+%m-%d %H:%M:%S.%3N"').stdout.strip()
        if not re.fullmatch(r'\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}',start):raise RuntimeError('DEVICE_CLOCK_UNAVAILABLE')
        report['deviceLogStart']=start;report['uids']=uids
        command=['adb','-s',args.serial,'shell','am','instrument','-w','-e','runId',run,'-e','phase',args.phase,'dev.tripoder.controltest/dev.tripoder.controltest.AgentUiIntegration']
        instrument=subprocess.Popen(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        launch=wait_json('launch-workbench-'+run+'.json');adb('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',launch['component'])
        ready=wait_json('agent-'+run+'-ready.json');(evidence/'fixture-public.json').write_text(json.dumps(ready,ensure_ascii=False,indent=2));(evidence/'device-public-key.txt').write_text(ready['publicKey']+'\n')
        local=repo/'.tripoder-local'/run;local.mkdir(parents=True,mode=0o700);config=local/'session.json';report['privateConfigRecordedInEvidence']=False
        pair=subprocess.run(['node',str(repo/'agent/pair.mjs'),'--serial',args.serial,'--device-public-key-file',str(evidence/'device-public-key.txt'),'--config',str(config)],capture_output=True,text=True,timeout=115)
        (evidence/'pairing.txt').write_text(pair.stdout+pair.stderr)
        if pair.returncode:raise RuntimeError('PAIR_FAILED:'+pair.stderr)
        if args.phase=='sentinel':
            wait_json('agent-'+run+'-waiting-client.json',75)
            setup=subprocess.run(['node',str(repo/'agent/sentinel-acceptance.mjs'),str(config),str(evidence/'fixture-public.json'),str(evidence)],cwd=repo,capture_output=True,text=True,check=True)
            print(setup.stdout,flush=True)
            stdout,stderr=instrument.communicate(timeout=590);(evidence/'instrumentation.txt').write_text(stdout+stderr)
        else:
            client=subprocess.run(['node',str(repo/'agent/acceptance-client.mjs'),str(config),str(evidence/'fixture-public.json'),str(evidence/'mcp-transcript.json'),args.phase],cwd=repo,text=True,capture_output=True,timeout=240)
            (evidence/'mcp-client.txt').write_text(client.stdout+client.stderr)
            if client.returncode:raise RuntimeError('MCP_CLIENT_FAILED:'+client.stderr)
        stdout,stderr=instrument.communicate(timeout=40);(evidence/'instrumentation.txt').write_text(stdout+stderr)
        result=wait_json('result-'+run+'.json');(evidence/'device-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
        for name in result.get('captures',[]):
            if not re.fullmatch(re.escape(run)+r'-[a-z-]+\.png',name):raise RuntimeError('CAPTURE_NAME_INVALID')
            adb('pull','/sdcard/Android/data/dev.tripoder.engine/files/'+name,str(evidence/name))
        if not result.get('passed') or not all(c['passed'] for c in result['checks']):raise RuntimeError('DEVICE_ASSERTIONS_FAILED')
        if result['workerUid']!=uids['dev.tripoder.engine']:raise RuntimeError('ENGINE_UID_RECEIPT_MISMATCH')
        if args.phase in ('normal','disconnect'):
            transcript=json.loads((evidence/'mcp-transcript.json').read_text())
            statuses=agent_status_receipts(transcript['transcript']);summaries=[]
            for receipt_status in statuses:
                if receipt_status.get('processGeneration')!=result['processGeneration']:raise RuntimeError('RUNTIME_MAPPING_MCP_PROCESS_MISMATCH')
                summary=verify_runtime_status(receipt_status['result'],pid=result['workerPid'],uid=uids['dev.tripoder.engine'],generation=result['processGeneration'],core_path=engine_core_path,native_sha256=native_sha256,native_size=native_size)
                summary['requestId']=receipt_status.get('requestId');summaries.append(summary)
            report['runtimeNativeMappingVerification']=summaries
            (evidence/'runtime-native-mapping-verification.json').write_text(json.dumps(summaries,ensure_ascii=False,indent=2))
        if args.phase=='sentinel':
            audit=[json.loads(line) for line in (evidence/'sentinel-mcp-audit.jsonl').read_text().splitlines()];parents={item['ppid'] for item in audit};parent_paths={str(pid):subprocess.check_output(['ps','-p',str(pid),'-o','comm='],text=True).strip() for pid in parents}
            calls=[item for item in audit if item.get('tool') and item.get('ok') is True]
            required={'session_get','process_get','hook_observe','hook_prepare','hook_activate','hook_status','hook_disable'}
            if not required.issubset({item['tool'] for item in calls}):raise RuntimeError('SENTINEL_TOOL_CHAIN_INCOMPLETE')
            if not any(item.get('clientInfo',{}).get('name')=='sentinel-ai' for item in audit):raise RuntimeError('SENTINEL_CLIENT_INFO_MISSING')
            if not parent_paths or not all(path.endswith('/sentinel-ai') for path in parent_paths.values()):raise RuntimeError('SENTINEL_PARENT_PROCESS_NOT_VERIFIED')
            if not any(item.get('result',{}).get('hits',0)>=2 and item.get('result',{}).get('errors')==0 for item in calls):raise RuntimeError('SENTINEL_HIT_EVIDENCE_MISSING')
            report['sentinelClientVerified']=True;report['sentinelParents']=parent_paths;report['sentinelToolCalls']=len(calls)
        report['passed']=True;report['deviceChecks']=len(result['checks']);report['mcpChecks']=[] if args.phase=='sentinel' else json.loads((evidence/'mcp-transcript.json').read_text())['checks'];report['workerPid']=result['workerPid']
    except Exception as error:
        report['passed']=False;report['error']=str(error)
        result=adb('shell','cat','/sdcard/Android/data/dev.tripoder.engine/files/result-'+run+'.json',check=False)
        if result.returncode==0:(evidence/'device-result.json').write_text(result.stdout)
        progress=adb('shell','cat','/sdcard/Android/data/dev.tripoder.engine/files/progress-'+run+'.json',check=False)
        if progress.returncode==0:(evidence/'device-progress.json').write_text(progress.stdout)
        raise
    finally:
        if instrument is not None and instrument.poll() is None:
            instrument.kill();stdout,stderr=instrument.communicate();(evidence/'instrumentation.txt').write_text(stdout+stderr);adb('shell','am','force-stop','dev.tripoder.engine')
        if start:
            for package,uid in uids.items():
                logs=adb('logcat','-b','crash','-d','--uid='+str(uid),'-T',start,check=False);(evidence/(package+'-crash.txt')).write_text(logs.stdout)
                if logs.returncode or 'FATAL EXCEPTION:' in logs.stdout or re.search(r'Fatal signal \d+ ',logs.stdout):report['passed']=False;report['fatalOrLogFailure']=True
            bootstrap=adb('logcat','-b','main','-d','--uid='+str(uids['dev.tripoder.engine']),'-T',start,'TripoderSelfHook:I','TripoderOwnership:I','*:S',check=False)
            (evidence/'engine-bootstrap.txt').write_text(bootstrap.stdout)
            report['selfHookInstallationSamples']=self_hook_samples(bootstrap.stdout)
            if bootstrap.returncode:report['passed']=False;report['fatalOrLogFailure']=True
            if report.get('passed') and not any(item['pid']==report.get('workerPid') and item['bindings'] in (2270,2271) for item in report['selfHookInstallationSamples']):
                report['passed']=False;report['workerBootstrapEvidenceMissing']=True
        report['finishedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat();report['desktopSourceSha256']={str(path.relative_to(repo)):sha(path) for path in sorted((repo/'agent').glob('*.mjs'))};shutil.copyfile(build/'build-receipt.json',evidence/'build-receipt.json');(evidence/'device-observation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    if not report['passed']:raise RuntimeError('DEVICE_ACCEPTANCE_FAILED')
    print(json.dumps({'passed':True,'deviceChecks':report['deviceChecks'],'mcpChecks':len(report['mcpChecks']),'sentinelClientVerified':report['sentinelClientVerified']}))

if __name__=='__main__':main()
