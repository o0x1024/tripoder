#!/usr/bin/env python3
"""Bounded self-map oracle over the exact production C source. No APK installation."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--evidence',type=Path,required=True)
parser.add_argument('--serial',default='d861a4fa')
parser.add_argument('--ndk',type=Path,default=Path('/opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370'))
parser.add_argument('--build-only',action='store_true')
parser.add_argument('--run-only',action='store_true')
args=parser.parse_args()
if args.build_only and args.run_only:raise ValueError('EXCLUSIVE_PROBE_ACTION')
repo=Path(__file__).resolve().parents[2];out=args.evidence.resolve()
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
if not args.run_only:
    out.mkdir(parents=True,exist_ok=False)
    sources=[repo/'android/compatibility-test/native/readable_mapping_probe.c']
    sources += [repo/'android/self-hook/native'/name for name in ('art_access.c','art_access.h','art_fixed_contract.h','art_managed_contract.h')]
    hashes={}
    for source in sources:
        target=out/source.name;shutil.copy2(source,target);hashes[source.name]=sha(target)
    binary=out/'owned-readable-probe'
    command=[str(args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android36-clang'),'-std=c11','-O2','-Wall','-Wextra','-Werror','-fPIE','-pie','-I'+str(out),str(out/'readable_mapping_probe.c'),'-ldl','-o',str(binary)]
    compiled=subprocess.run(command,capture_output=True,text=True)
    (out/'compile.txt').write_text(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError('OWNED_READABLE_PROBE_COMPILE_FAILED')
    (out/'receipt.json').write_text(json.dumps({'sourceSha256':hashes,'binarySha256':sha(binary),'command':command,'deviceExecuted':False},indent=2)+'\n')
if args.build_only:print(out);raise SystemExit(0)
receipt=json.loads((out/'receipt.json').read_text());binary=out/'owned-readable-probe'
if sha(binary)!=receipt['binarySha256']:raise RuntimeError('OWNED_READABLE_PROBE_BINARY_CHANGED')
remote='/data/local/tmp/tripoder-owned-readable-'+receipt['binarySha256'][:16]
adb=['adb','-s',args.serial]
subprocess.run([*adb,'push',str(binary),remote],check=True,capture_output=True)
subprocess.run([*adb,'shell','chmod','700',remote],check=True,capture_output=True)
executed=subprocess.run([*adb,'shell',remote],capture_output=True,text=True,timeout=30)
(out/'device.txt').write_text(executed.stdout+executed.stderr)
report=json.loads(executed.stdout)
report.update(deviceExecuted=True,serial=args.serial,binarySha256=receipt['binarySha256'],sourceSha256=receipt['sourceSha256'],exitCode=executed.returncode,dataCleared=False,apkInstalled=False)
(out/'result.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
raise SystemExit(0 if report['passed'] else 1)
