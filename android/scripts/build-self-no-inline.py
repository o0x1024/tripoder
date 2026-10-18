#!/usr/bin/env python3
"""Build only E1. The unchanged product core and a separate read-only ART observer are packaged."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import uuid
from runtime_apk_builder import ApkBuilder, sha
from self_hook_native import build_native, add_platform_inputs

ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['sdk','java-home','ndk','key']:p.add_argument('--'+name,type=Path,required=True)
    add_platform_inputs(p);a=p.parse_args()
    probe=ROOT/'android/self-hook-probe';core=ROOT/'android/self-hook';build=probe/'build/e1';build.mkdir(parents=True,exist_ok=True)
    (build/'build-receipt.json').unlink(missing_ok=True)
    builder=ApkBuilder(ROOT,build,a.sdk,'android-37.0','37.0.0',a.java_home,a.key)
    selected=['ProbeEnvironment.java','ProbeFiles.java','ArtProbe.java','ArtFixture.java','ArtCompilation.java','OwnedCompilationPaths.java','CoverageTarget.java']
    java=sorted((core/'java').rglob('*.java'))+[probe/'src/dev/tripoder/selfhookprobe'/n for n in selected]+sorted((probe/'e1').glob('*.java'))
    late=probe/'coverage-fixtures/dev/tripoder/selfhookprobe/dynamic/LateCaller.java'
    inputs=set(java+[late,probe/'platform.json',probe/'e1/AndroidManifest.xml',probe/'e1/probe_no_inline.c',probe/'native/probe_art_state.c',core/'native/art_access.c',Path(__file__).resolve(),ROOT/'android/scripts/run-self-no-inline.py',ROOT/'android/scripts/verify-self-no-inline.py',ROOT/'android/scripts/verify_self_owned_run.py',ROOT/'android/scripts/verify-self-art-compilation.py'])
    before={str(f.relative_to(ROOT)):sha(f) for f in sorted(inputs)}
    library,native=build_native(ROOT,build/'native',a.ndk,a.platform_libc,a.platform_art,a.platform_linker)
    sources={**before,**native['sourceSha256']}
    clang=a.ndk.resolve()/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android36-clang'
    observer=build/'libtripoderhookfixture.so'
    builder.run(clang,'-std=c11','-O2','-fPIC','-fvisibility=hidden','-mbranch-protection=standard','-mno-outline-atomics','-Wall','-Wextra','-Werror','-shared','-Wl,--no-undefined,-z,relro,-z,now,--build-id=sha1','-I'+str(core/'include'),'-I'+str(core/'native'),probe/'e1/probe_no_inline.c',probe/'native/probe_art_state.c',core/'native/art_access.c','-ldl','-o',observer)
    late_classes=build/'late-classes';late_dex=build/'late-dex'
    for d in [late_classes,late_dex]:
        if d.exists():shutil.rmtree(d)
        d.mkdir()
    builder.run(builder.java/'bin/javac','--release','17','-classpath',builder.platform,'-d',late_classes,probe/'src/dev/tripoder/selfhookprobe/CoverageTarget.java',late)
    builder.run(builder.tools/'d8','--release','--min-api','36','--lib',builder.platform,'--output',late_dex,late_classes/'dev/tripoder/selfhookprobe/dynamic/LateCaller.class')
    assets=build/'assets';assets.mkdir(exist_ok=True)
    shutil.copyfile(late_dex/'classes.dex',assets/'late-caller.dex');shutil.copyfile(probe/'platform.json',assets/'platform.json')
    metadata={'buildId':str(uuid.uuid4()),'sourceDigest':hashlib.sha256(json.dumps(sources,sort_keys=True).encode()).hexdigest(),'nativeSha256':sha(library),'fixtureSha256':sha(observer),'experiment':'E1','productAccepted':False}
    (assets/'build.json').write_text(json.dumps(metadata))
    apk=builder.apk('self-hook-e1',java,probe/'e1/AndroidManifest.xml',assets=assets,libraries=[library,observer])
    for path,digest in sources.items():
        if sha(ROOT/path)!=digest:raise RuntimeError('E1_SOURCE_CHANGED_DURING_BUILD:'+path)
    receipt={**metadata,'apk':str(apk),'apkSha256':sha(apk),'sources':sources,'core':native,'observerLibrary':str(observer),'lateDexSha256':sha(assets/'late-caller.dex'),'developmentSigningOnly':True}
    (build/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({'apk':str(apk),'sha256':receipt['apkSha256'],'productCoreUnchanged':True}))

if __name__=='__main__':main()
