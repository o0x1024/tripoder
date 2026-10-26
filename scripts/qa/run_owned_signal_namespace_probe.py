#!/usr/bin/env python3
"""Owned standalone kernel signal / exact production namespace counterexample."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,uuid

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--evidence',type=Path,required=True)
p.add_argument('--serial',default='d861a4fa')
p.add_argument('--ndk',type=Path,default=Path('/opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370'))
p.add_argument('--build-only',action='store_true')
p.add_argument('--run-only',action='store_true')
a=p.parse_args()
if a.build_only and a.run_only:raise ValueError('EXCLUSIVE_PROBE_ACTION')
repo=Path(__file__).resolve().parents[2];out=a.evidence.resolve()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
if not a.run_only:
    out.mkdir(parents=True,exist_ok=False)
    runtime=repo/'android/runtime/native'
    sources=[repo/'android/compatibility-test/native/signal_namespace_probe.c']
    sources += [runtime/n for n in ['syscall_namespace.c','syscall_namespace.h','ptrace_namespace.c','ptrace_namespace.h','io_adapter.c','io_adapter.h','path_policy.c','path_policy.h','user_memory.c','user_memory.h','kernel_syscall_a64.S','kernel_syscall.h']]
    sources += [repo/'android/self-hook/native/signal_chain.h']
    hashes={}
    for source in sources:
        target=out/source.name;shutil.copyfile(source,target);hashes[source.name]=sha(target)
    binary=out/'owned-signal-namespace-probe'
    command=[str(a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android36-clang'),'-std=c11','-O2','-Wall','-Wextra','-Werror','-fPIE','-pie','-I'+str(out),*[str(out/n) for n in ['signal_namespace_probe.c','syscall_namespace.c','ptrace_namespace.c','io_adapter.c','path_policy.c','user_memory.c','kernel_syscall_a64.S']],'-ldl','-o',str(binary)]
    built=subprocess.run(command,capture_output=True,text=True);(out/'compile.txt').write_text(built.stdout+built.stderr)
    if built.returncode:raise RuntimeError('OWNED_SIGNAL_NAMESPACE_BUILD_FAILED')
    (out/'receipt.json').write_text(json.dumps({'sourceSha256':hashes,'binarySha256':sha(binary),'command':command,'deviceExecuted':False},indent=2)+'\n')
if a.build_only:print(out);raise SystemExit(0)
r=json.loads((out/'receipt.json').read_text());binary=out/'owned-signal-namespace-probe'
if sha(binary)!=r['binarySha256']:raise RuntimeError('OWNED_BINARY_CHANGED')
adb=['adb','-s',a.serial];remote='/data/local/tmp/tripoder-owned-signal-bin-'+r['binarySha256'][:16]
subprocess.run([*adb,'push',str(binary),remote],capture_output=True,check=True)
subprocess.run([*adb,'shell','chmod','700',remote],capture_output=True,check=True)
root='/data/local/tmp/tripoder-owned-signal-'+uuid.uuid4().hex
ran=subprocess.run([*adb,'shell',remote,root],capture_output=True,text=True,timeout=30)
(out/'device.txt').write_text(ran.stdout+ran.stderr)
report=json.loads(ran.stdout)
report.update(serial=a.serial,binarySha256=r['binarySha256'],sourceSha256=r['sourceSha256'],exitCode=ran.returncode,deviceExecuted=True,dataCleared=False,apkInstalled=False)
(out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
raise SystemExit(0 if report['passed'] else 1)
