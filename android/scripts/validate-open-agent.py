#!/usr/bin/env python3
"""Real unsigned stdio MCP -> Android Hook/UI acceptance on an owned fixture."""
import argparse, datetime, hashlib, importlib.util, json, re, shutil, subprocess, time, uuid
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True);parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--install',action='store_true');parser.add_argument('--phase',choices=['normal','disconnect'],default='normal')
    parser.add_argument('--scenario',choices=['hook','code'],default='hook')
    args=parser.parse_args();repo=Path(__file__).resolve().parents[2];build=repo/'android/product/build'
    spec=importlib.util.spec_from_file_location('mapping',Path(__file__).with_name('validate-agent-control.py'));mapping=importlib.util.module_from_spec(spec);spec.loader.exec_module(mapping)
    receipt=json.loads((build/'build-receipt.json').read_text())
    for name,digest in receipt['sourceSha256'].items():
        if mapping.sha(repo/name)!=digest:raise RuntimeError('SOURCE_CHANGED_SINCE_BUILD:'+name)
    for artifact in receipt['artifacts'].values():
        if mapping.sha(artifact['path'])!=artifact['sha256']:raise RuntimeError('APK_CHANGED_SINCE_BUILD')
    native_sha,native_size=mapping.product_native_identity(receipt)
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    run='control-'+uuid.uuid4().hex[:16];remote='/sdcard/Android/data/dev.tripoder.engine/files/'
    report={'runId':run,'passed':False,'sentinelDesktopVerified':False,'modelToolUseVerified':False,'pairingPerformed':False,'phoneApprovalPerformed':False}
    instrument=None;start=None;engine_uid=None;core_path=None
    def adb(*command,check=True,timeout=60):
        value=subprocess.run(['adb','-s',args.serial,*command],capture_output=True,text=True,timeout=timeout)
        if check and value.returncode:raise RuntimeError('ADB_FAILED:'+value.stdout+value.stderr)
        return value
    def wait_file(name,timeout=45):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=adb('shell','cat',remote+name,check=False,timeout=5)
            if value.returncode==0:return value.stdout
            if instrument.poll() is not None:raise RuntimeError('INSTRUMENTATION_ENDED_BEFORE:'+name)
            time.sleep(.15)
        raise RuntimeError('PHONE_STAGE_TIMEOUT:'+name)
    try:
        if args.install:
            for pkg in ['dev.tripoder.engine','dev.tripoder.controltest']:adb('shell','am','force-stop',pkg)
            for name in ['engine','controlTest']:adb('install','--no-incremental','-r','-t',receipt['artifacts'][name]['path'],timeout=120)
        for artifact in receipt['artifacts'].values():
            path=adb('shell','pm','path',artifact['package']).stdout.strip().removeprefix('package:')
            if not re.fullmatch(r'/data/app/[^\n]+/base.apk',path):raise RuntimeError('INSTALLED_PATH_INVALID')
            if adb('shell','sha256sum',path).stdout.split()[0]!=artifact['sha256']:raise RuntimeError('INSTALLED_APK_MISMATCH')
            if artifact['package']=='dev.tripoder.engine':core_path=str(Path(path).parent/'lib/arm64/libtripoderselfhook.so')
        for pkg in ['dev.tripoder.controltest','dev.tripoder.engine']:adb('shell','am','force-stop',pkg)
        uid=adb('shell','cmd','package','list','packages','-U','dev.tripoder.engine').stdout
        engine_uid=int(re.fullmatch(r'package:dev\.tripoder\.engine uid:(\d+)\s*',uid)[1])
        start=adb('shell','date "+%m-%d %H:%M:%S.%3N"').stdout.strip()
        command=['adb','-s',args.serial,'shell','am','instrument','-w','-e','runId',run,'dev.tripoder.controltest/dev.tripoder.controltest.'+('CodeProbeIntegration' if args.scenario=='code' else 'AgentUiIntegration')]
        instrument=subprocess.Popen(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        launch=json.loads(wait_file('launch-workbench-'+run+'.json'))
        if launch['runId']!=run:raise RuntimeError('RUN_MISMATCH')
        adb('shell','am','start','-W','-n',launch['component'])
        ready=wait_file(run+'-open-ready.json');(evidence/'fixture.json').write_text(ready)
        client=subprocess.run(['node',str(repo/('agent/code-probe-acceptance.mjs' if args.scenario=='code' else 'agent/open-acceptance.mjs')),'--serial',args.serial,'--run',run,'--ready',str(evidence/'fixture.json'),'--evidence',str(evidence)],cwd=repo,capture_output=True,text=True,timeout=240)
        (evidence/'mcp-client.txt').write_text(client.stdout+client.stderr)
        if client.returncode:raise RuntimeError('MCP_CLIENT_FAILED:'+client.stderr)
        out,err=instrument.communicate(timeout=35);(evidence/'instrumentation.txt').write_text(out+err)
        raw=wait_file('result-'+run+'.json');(evidence/'device-result.json').write_text(raw);result=json.loads(raw)
        expected=re.search(r'resultSha256=([a-f0-9]{64})',out)
        if not expected or expected[1]!=hashlib.sha256(raw.encode()).hexdigest():raise RuntimeError('INSTRUMENTATION_RECEIPT_MISMATCH')
        if not result.get('passed') or not all(c['passed'] for c in result['checks']):raise RuntimeError('PHONE_ASSERTIONS_FAILED:'+result.get('error',''))
        if result.get('workerUid',engine_uid)!=engine_uid:raise RuntimeError('WORKER_UID_MISMATCH')
        transcript=json.loads((evidence/'mcp-transcript.json').read_text());statuses=mapping.agent_status_receipts(transcript);verified=[]
        for status in statuses:
            identity=status['result']['identity'];generation=identity['processGeneration'];pid=identity['pid']
            if args.scenario=='hook' and generation!=result['processGeneration']:raise RuntimeError('STATUS_GENERATION_MISMATCH')
            verified.append(mapping.verify_runtime_status(status['result'],pid=pid,uid=engine_uid,generation=generation,core_path=core_path,native_sha256=native_sha,native_size=native_size))
        (evidence/'runtime-mapping.json').write_text(json.dumps(verified,indent=2))
        desktop=json.loads((evidence/'desktop-result.json').read_text())
        report.update(passed=True,deviceChecks=len(result['checks']),desktopChecks=len(desktop['checks']),workerPid=verified[0]['pid'],processGeneration=verified[0]['processGeneration'],runtimeMappingVerified=True)
    except Exception as error:
        report['error']=str(error)
        for name in ['result-'+run+'.json','progress-'+run+'.json']:
            value=adb('shell','cat',remote+name,check=False)
            if value.returncode==0:(evidence/name).write_text(value.stdout)
        raise
    finally:
        if instrument and instrument.poll() is None:
            instrument.kill();out,err=instrument.communicate();(evidence/'instrumentation.txt').write_text(out+err)
            adb('shell','am','force-stop','dev.tripoder.engine',check=False)
        if start and engine_uid:
            logs=adb('logcat','-b','crash','-d','--uid='+str(engine_uid),'-T',start,check=False)
            (evidence/'crash.txt').write_text(logs.stdout)
            if logs.returncode or 'FATAL EXCEPTION:' in logs.stdout or re.search(r'Fatal signal \d+ ',logs.stdout):report.update(passed=False,crashOrLogFailure=True)
        adb('shell','am','start','-n','dev.tripoder.engine/dev.tripoder.hooklab.WorkbenchActivity',check=False)
        report['finishedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        report['sourceSha256']={str(p.relative_to(repo)):mapping.sha(p) for p in sorted((repo/'agent').glob('*.mjs'))}
        shutil.copyfile(build/'build-receipt.json',evidence/'build-receipt.json')
        (evidence/'summary.json').write_text(json.dumps(report,indent=2))
    if not report['passed']:raise RuntimeError('ACCEPTANCE_FAILED')
    print(json.dumps(report))

if __name__=='__main__':main()
