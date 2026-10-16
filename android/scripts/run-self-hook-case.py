#!/usr/bin/env python3
"""One fresh owned-probe process; retain receipts and never repair device state."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'android/self-hook-probe/build'
PACKAGE = 'dev.tripoder.selfhookprobe'
ACTIVITIES = {'abi': 'ManagedAbiProbeActivity', 'active': 'ActiveEntryProbeActivity',
              'main': 'ProbeActivity', 'framework': 'FrameworkProbeActivity', 'application': 'ApplicationProbeActivity',
              'coverage': 'CoverageProbeActivity', 'script': 'ScriptControlProbeActivity', 'scalar': 'ScalarProbeActivity',
              'entry': 'EntryPublicationProbeActivity'}
ACTIVITIES['writer']='EntryWriterProbeActivity'
ACTIVITIES['inflight']='InFlightProbeActivity'
ACTIVITIES['startup']='InFlightProbeActivity'
ACTIVITIES['inventory']='ManagerInventoryProbeActivity'
ACTIVITIES['capacity']='CapacityProbeActivity'
ACTIVITIES['service-token']='ServiceTokenProbeActivity'
ACTIVITIES['product-framework']='ProductFrameworkProbeActivity'
ACTIVITIES['product-java']='ProductJavaControlProbeActivity'
ACTIVITIES['modules']='LinkerProbeActivity'
ACTIVITIES['modules-death']='LinkerProbeActivity'
ACTIVITIES['inbound']='NativeInboundProbeActivity'
ACTIVITIES['native-export']='NativeExportProbeActivity'
ACTIVITIES['native-export-pac']='NativeExportProbeActivity'
ACTIVITIES['native-race']='NativeRaceProbeActivity'
ACTIVITIES['native-inflight-death']='NativeRaceProbeActivity'
ACTIVITIES['native-jni']='NativeJniProbeActivity'
ACTIVITIES['product-native']='ProductNativeProbeActivity'
ACTIVITIES['product-mixed']='ProductMixedProbeActivity'
ACTIVITIES['native-leaf']='NativeLeafProbeActivity'
ACTIVITIES['catalog']='ModuleCatalogProbeActivity'
ACTIVITIES['catalog-empty']='ModuleCatalogProbeActivity'
ACTIVITIES['manager-installer']='ManagerInstallerProbeActivity'
ACTIVITIES['manager-all']='ManagerInstallerProbeActivity'
ACTIVITIES['constructor']='ConstructorProbeActivity'
ACTIVITIES['early-application']='EarlyApplicationProbeActivity'
ACTIVITIES['jni-register']='JniRawProbeActivity'
ACTIVITIES['jni-sdk']='JniSdkProbeActivity'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--activity', choices=ACTIVITIES, required=True)
    parser.add_argument('--expected', choices=['jit', 'aot', 'cold', 'native'], required=True)
    parser.add_argument('--evidence-root', type=Path, required=True)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--aot-proof', type=Path)
    args = parser.parse_args()
    if args.activity in ('inflight','startup') and args.expected!='aot':
        raise RuntimeError('INFLIGHT_REQUIRES_AOT')
    if (args.expected=='native') != (args.activity in ('scalar','entry','inventory','capacity','service-token','product-framework','product-java','modules','modules-death','inbound','native-export','native-export-pac','native-race','native-inflight-death','native-jni','product-native','product-mixed','native-leaf','catalog','catalog-empty','manager-installer','manager-all','constructor','early-application','jni-register','jni-sdk')):
        raise RuntimeError('NATIVE_FIXTURE_REQUIRES_NATIVE_EXECUTION_MODE')
    if args.expected == 'cold' and args.activity != 'abi':
        raise RuntimeError('COLD_ACCEPTANCE_REQUIRES_ABI_ACTIVITY')
    parent = args.evidence_root.resolve()
    if not parent.is_relative_to(ROOT / 'docs/evidence'):
        raise RuntimeError('OWNED_EVIDENCE_DIRECTORY_REQUIRED')
    receipt = json.loads((BUILD / 'build-receipt.json').read_text())
    if sha(Path(receipt['apk'])) != receipt['apkSha256']:
        raise RuntimeError('APK_CHANGED')
    for name, digest in receipt['sources'].items():
        if sha(ROOT / name) != digest:
            raise RuntimeError('SOURCE_CHANGED:' + name)
    if args.activity == 'main':
        dex = json.loads((ROOT / 'android/self-hook/build/dex-host/report.json').read_text())
        if dex.get('passed') is not True or dex.get('apkSha256') != receipt['apkSha256']:
            raise RuntimeError('CURRENT_APK_DEX_ORACLE_REQUIRED: run android/self-hook/tests/check_dex_graph.py --apk ' + receipt['apk'])
        if any(receipt['sources'].get(name) != digest for name, digest in dex['sourceHashes'].items()):
            raise RuntimeError('DEX_ORACLE_SOURCE_CHANGED')
    if args.expected == 'aot':
        if args.aot_proof is None:
            raise RuntimeError('AOT_PROOF_REQUIRED')
        proof = json.loads(args.aot_proof.read_text())
        if proof['apkSha256'] != receipt['apkSha256'] or 'actualCompilerFilter=speed' not in proof['stdout'] or 'Final Status: PERFORMED' not in proof['stdout']:
            raise RuntimeError('AOT_PROOF_IDENTITY')
    run = str(uuid.uuid4())
    directory = parent / run
    directory.mkdir(parents=True)
    for name in ['build-receipt.json', 'native-dynamic.txt', 'native-notes.txt', 'fixture-elf.txt',
                 'park-binary-audit.json', 'park-binary-audit.txt', 'libc-contract.json', 'libc-source-pages.bin',
                 'art-framework-contract.json', 'art-storage-contract.json', 'art-interpreter-contract.json', 'int-relay-oracle.json',
                 'helper-dex-oracle.json', 'caller-closure-oracle.json']:
        shutil.copyfile(BUILD / name, directory / name)
    for name in ['art-entry-writer-contract.json','near-leaf-audit.json','near-leaf-audit.txt','libtripoder-entry-leaf-template.so']:
        shutil.copyfile(BUILD/name,directory/name)
    if args.activity == 'coverage':
        shutil.copyfile(BUILD / 'loaded-class-report.json', directory / 'loaded-class-report.json')
        for name in ['entry-leaf-audit.json','entry-leaf-audit.txt']:
            shutil.copyfile(BUILD/name,directory/name)
    if args.activity=='scalar':
        for name in ['scalar-host-report.json','scalar_call-audit.txt','scalar_capture-audit.txt']:
            shutil.copyfile(BUILD/name,directory/name)
    if args.activity in ('modules','modules-death'):
        for name in ['linker-contract.json','notifier-template.json','notifier-template.txt','module-lifetime-host.json']:
            shutil.copyfile(BUILD/name,directory/name)
    if args.activity=='inbound':
        for name in ['inbound-audit.json','inbound-audit.txt']:
            shutil.copyfile(BUILD/name,directory/name)
    if args.activity in ('native-export','native-export-pac','native-race','native-inflight-death'):
        for name in ['native-prefix-audit.json','native-template-audit.json','native-template-audit.txt']:
            shutil.copyfile(BUILD/name,directory/name)
    for source, name in [(BUILD / 'self-hook-probe/signature.txt', 'signature.txt'),
                         (ROOT / 'android/self-hook/build/host/host-report.json', 'host-report.json'),
                         (ROOT / 'android/self-hook/build/dex-host/report.json', 'dex-host-report.json')]:
        shutil.copyfile(source, directory / name)
    if args.expected == 'aot':
        (directory / 'aot-compile.txt').write_text(proof['stdout'])
        shutil.copyfile(args.aot_proof, directory / 'aot-compile.json')
    plan = {'runId': run, 'activity': args.activity, 'expected': args.expected, 'package': PACKAGE,
            'apkSha256': receipt['apkSha256'], 'runnerSha256': sha(Path(__file__)), 'cacheReset': False,
            'clearOwnedTask': True}
    (directory / 'run-plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    adb = [str(args.adb), '-s', args.serial]
    def command(label, *parts, check=True):
        result = subprocess.run([*adb, *parts], capture_output=True, text=True, timeout=30)
        if label:
            (directory / (label + '.txt')).write_text(result.stdout + result.stderr)
        if check and result.returncode:
            raise RuntimeError(label + ':' + result.stderr)
        return result
    try:
        compiler = command(None, 'shell', 'dumpsys', 'package', PACKAGE)
        # Retain only this package's compiler section, not package-query relations.
        state = compiler.stdout.partition('Dexopt state:')[2]
        (directory / 'compiler-state-before.txt').write_text('Dexopt state:' + state if state else 'DEXOPT_STATE_UNAVAILABLE\n')
        command('force-stop', 'shell', 'am', 'force-stop', PACKAGE)
        stop_deadline=time.monotonic()+5
        while True:
            stopped=command(None,'shell','pidof',PACKAGE,check=False)
            if stopped.returncode==1 and not stopped.stdout.strip():
                (directory/'process-stopped.json').write_text(json.dumps({'package':PACKAGE,'pidofExitCode':1,'pidPresent':False})+'\n')
                break
            if stopped.returncode not in (0,1) or time.monotonic()>=stop_deadline:
                raise RuntimeError('OWNED_PROCESS_DID_NOT_STOP')
            time.sleep(0.1)
        extras = ['--ez', 'selfAccess', 'true'] if args.activity == 'framework' else ['--es', 'artCompilation', args.expected]
        if args.activity == 'script': extras += ['--ez','ownedScriptScope','true']
        if args.activity == 'startup': extras += ['--ez','earlyPolicy','true']
        if args.activity in ('modules-death','native-inflight-death'):extras += ['--ez','activeUnload','true']
        if args.activity == 'native-export-pac':extras += ['--ez','pac','true']
        if args.activity == 'catalog-empty':extras += ['--ez','emptyCatalog','true']
        if args.activity == 'manager-all':extras += ['--ez','allManagers','true']
        # Clear only this probe's task: an old root Activity can otherwise be
        # reconstructed before the requested fixture and consume one-shot state.
        launched = command('launch', 'shell', 'am', 'start', '-W', '-f', '0x10008000', '-n', PACKAGE + '/.' + ACTIVITIES[args.activity], '--es', 'runId', run, *extras)
        if 'LaunchState: COLD' not in launched.stdout or 'Status: ok' not in launched.stdout:
            raise RuntimeError('COLD_LAUNCH_REQUIRED')
        print(json.dumps({'runId': run, 'activity': args.activity, 'status': 'RUNNING'}), flush=True)
        remote = '/sdcard/Android/data/' + PACKAGE + '/files/' + run
        if args.activity in ('modules-death','native-inflight-death'):
            from self_module_death import collect
            collect(command,directory,remote,PACKAGE)
            return
        deadline = time.monotonic() + (600 if args.activity in ('capacity','manager-all') else 120)
        while command(None, 'shell', 'test', '-f', remote + '/result.json', check=False).returncode:
            if time.monotonic() >= deadline:
                raise RuntimeError('RESULT_TIMEOUT')
            if command(None, 'shell', 'pidof', PACKAGE, check=False).returncode:
                raise RuntimeError('OWNED_PROCESS_EXITED_WITHOUT_RESULT')
            time.sleep(1)
        command('pull', 'pull', remote, str(directory / 'device'))
        result = json.loads((directory / 'device/result.json').read_text())
        if result['runId'] != run or result['apkSha256'] != receipt['apkSha256']:
            raise RuntimeError('DEVICE_RESULT_IDENTITY')
        print(json.dumps({'runId': run, 'directory': str(directory), 'passed': result['passed'], 'error': result.get('error')}), flush=True)
        if result['passed'] is not True:
            raise RuntimeError('PROBE_FAILED')
    except Exception as error:
        if 'remote' in locals() and not (directory/'device').exists():
            command('failure-pull','pull',remote,str(directory/'device'),check=False)
        (directory / 'runner-outcome.json').write_text(json.dumps({'passed': False, 'error': str(error), 'runId': run}, indent=2) + '\n')
        raise


if __name__ == '__main__':
    main()
