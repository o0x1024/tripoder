#!/usr/bin/env python3
"""Build an owned ART signal-mask contract. Import the APK into a THICK test space."""
import argparse
import json
from pathlib import Path
import subprocess
from runtime_apk_builder import ApkBuilder,sha

ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['sdk','java-home','ndk','key']:
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    probe=ROOT/'android/self-hook-probe/signal-contract'
    core=ROOT/'android/self-hook/native'
    build=ROOT/'android/self-hook-probe/build/signal-contract'
    build.mkdir(parents=True,exist_ok=True)
    receipt=build/'build-receipt.json';receipt.unlink(missing_ok=True)
    inputs=[*probe.iterdir(),core/'signal_chain.c',core/'signal_chain.h',
            Path(__file__).resolve(),ROOT/'android/scripts/runtime_apk_builder.py']
    before={str(p.relative_to(ROOT)):sha(p) for p in sorted(inputs) if p.is_file()}
    clang=args.ndk.resolve()/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android36-clang'
    library=build/'libsignalcontract.so'
    subprocess.run([str(clang),'-std=c11','-O2','-g','-shared','-fPIC','-Wall','-Wextra','-Werror',
                    '-I'+str(core),str(probe/'probe.c'),str(probe/'user_delivery.c'),str(core/'signal_chain.c'),'-o',str(library)],check=True)
    builder=ApkBuilder(ROOT,build,args.sdk,'android-37.0','37.0.0',args.java_home,args.key)
    apk=builder.apk('signal-contract',[probe/'Probe.java'],probe/'AndroidManifest.xml',libraries=[library])
    if any(sha(ROOT/p)!=digest for p,digest in before.items()):
        raise RuntimeError('BUILD_INPUT_CHANGED')
    receipt.write_text(json.dumps({'schema':1,'sourceSha256':before,'apkSha256':sha(apk),
                                   'nativeSha256':sha(library)},indent=2)+'\n')

if __name__=='__main__':main()
