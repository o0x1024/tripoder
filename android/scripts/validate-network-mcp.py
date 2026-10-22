#!/usr/bin/env python3
"""Prepare a self-owned Android installation, then verify native HTTP and stdio with ADB stopped.
ADB is installation/bootstrap infrastructure only. No credential enters evidence or stdout.
"""
import argparse,datetime,hashlib,json,os,shutil,socket,subprocess,tempfile,time,uuid
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--read-only',action='store_true');parser.add_argument('--receipt',type=Path);parser.add_argument('--fixture',type=Path);parser.add_argument('--serial',required=True);parser.add_argument('--evidence',type=Path,required=True);parser.add_argument('--install',action='store_true')
    args=parser.parse_args();repo=Path(__file__).resolve().parents[2];build=repo/'android/product/build';receipt_path=args.receipt.resolve() if args.receipt else build/'build-receipt.json';receipt=json.loads(receipt_path.read_text());fixture=args.fixture.resolve() if args.fixture else build/'launcher-guest.apk'
    def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
    for path,digest in receipt['sourceSha256'].items():
        if sha(repo/path)!=digest:raise RuntimeError('SOURCE_CHANGED_SINCE_BUILD:'+path)
    for artifact in receipt['artifacts'].values():
        if sha(artifact['path'])!=artifact['sha256']:raise RuntimeError('APK_CHANGED_SINCE_BUILD')
    if sha(fixture)!=receipt['installFixtures']['launcherSha256']:raise RuntimeError('FIXTURE_CHANGED_SINCE_BUILD')
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False);package=receipt['artifacts']['engine']['package'];run='control-'+uuid.uuid4().hex[:16];remote='/sdcard/Android/data/'+package+'/files/';node=shutil.which('node');report={'passed':False,'runId':run,'package':package,'scope':'LAN HTTPS MCP through stdio and native HTTP; '+('read-only' if args.read_only else 'owned fixture'),'adbStoppedDuringCalls':False,'sentinelDesktopVerified':False}
    adb_path=shutil.which('adb')
    def adb(*command,check=True,timeout=120):
        result=subprocess.run([adb_path,'-s',args.serial,*command],capture_output=True,text=True,timeout=timeout)
        if check and result.returncode:raise RuntimeError('ADB_SETUP_FAILED:'+str(command[:2]))
        return result
    def server_running():
        try:
            with socket.create_connection(('127.0.0.1',5037),timeout=1):return True
        except OSError:return False
    instrument=None;stopped=False;credential_name='mcp-connection-'+run+'.json'
    try:
        if args.install:
            for key in ['engine','controlTest']:
                result=adb('install','--no-incremental','-r','-t',receipt['artifacts'][key]['path']);(evidence/(key+'-install.txt')).write_text(result.stdout+result.stderr)
        for artifact in receipt['artifacts'].values():
            apk=adb('shell','pm','path',artifact['package']).stdout.strip().removeprefix('package:')
            if adb('shell','sha256sum',apk).stdout.split()[0]!=artifact['sha256']:raise RuntimeError('INSTALLED_APK_MISMATCH')
        instrument=subprocess.Popen([adb_path,'-s',args.serial,'shell','am','instrument','-w','-e','runId',run,'dev.tripoder.controltest/dev.tripoder.controltest.NetworkMcpBootstrap'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        deadline=time.monotonic()+25
        while time.monotonic()<deadline:
            if adb('shell','test','-f',remote+'launch-workbench-'+run+'.json',check=False).returncode==0:break
            if instrument.poll() is not None:raise RuntimeError('BOOTSTRAP_ENDED_EARLY')
            time.sleep(.2)
        adb('shell','am','start','-W','-n',package+'/dev.tripoder.hooklab.WorkbenchActivity')
        out,err=instrument.communicate(timeout=60);(evidence/'bootstrap-instrumentation.txt').write_text(out+err)
        raw=adb('shell','cat',remote+'result-'+run+'.json').stdout;result=json.loads(raw);(evidence/'bootstrap-result.json').write_text(raw)
        if not result.get('passed'):raise RuntimeError('NETWORK_BOOTSTRAP_FAILED:'+result.get('error',''))
        with tempfile.TemporaryDirectory(prefix='tripoder-network-credential-') as folder:
            connection=Path(folder)/'connection.json';data=adb('shell','cat',remote+credential_name).stdout
            with os.fdopen(os.open(connection,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'w') as stream:stream.write(data)
            data=None;adb('shell','rm',remote+credential_name)
            # Instrumentation may finish its target process. Relaunch the persisted, explicitly enabled endpoint.
            adb('shell','am','start','-W','-n',package+'/dev.tripoder.hooklab.WorkbenchActivity')
            time.sleep(2)
            subprocess.run([adb_path,'kill-server'],check=True,capture_output=True);stopped=True
            if server_running():raise RuntimeError('ADB_SERVER_STILL_RUNNING')
            report['adbStoppedDuringCalls']=True
            client=subprocess.run([node,str(repo/'agent/network-acceptance.mjs'),'--connection',str(connection),'--evidence',str(evidence),'--fixture',str(fixture),*(['--read-only','true'] if args.read_only else [])],cwd=repo,env={**os.environ,'PATH':'/nonexistent'},capture_output=True,text=True,timeout=480)
            (evidence/'client.txt').write_text(client.stdout+client.stderr)
            report['adbServerAbsentAfterCalls']=not server_running()
            if client.returncode:raise RuntimeError('NETWORK_MCP_CLIENT_FAILED; see client.txt')
            actual=json.loads((evidence/'network-result.json').read_text());report['passed']=actual['passed'] and report['adbServerAbsentAfterCalls'];report['checks']=len(actual['checks'])
    finally:
        if stopped:subprocess.run([adb_path,'start-server'],capture_output=True,timeout=30)
        if instrument and instrument.poll() is None:instrument.terminate();instrument.communicate(timeout=10)
        adb('shell','rm','-f',remote+credential_name,check=False)
        shutil.copyfile(receipt_path,evidence/'build-receipt.json')
        report['clientSourceSha256']={str(p.relative_to(repo)):sha(p) for p in [repo/'agent/server.mjs',repo/'agent/transport.mjs',repo/'agent/tools.mjs',repo/'agent/network-acceptance.mjs']}
        report['finishedAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat();(evidence/'summary.json').write_text(json.dumps(report,indent=2))
    if not report['passed']:raise RuntimeError('NETWORK_ACCEPTANCE_FAILED')
    print(json.dumps(report))
if __name__=='__main__':main()
