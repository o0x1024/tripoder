#!/usr/bin/env python3
"""Build the single Tripoder APK (workbench UI + engine runtime, package dev.tripoder.engine); excludes legacy runners and probe controllers.

Uses the existing explicit development key. These APKs are not release-signed artifacts.
"""
import argparse
import json
from pathlib import Path
import shutil
import zipfile
from runtime_apk_builder import ApkBuilder, sha
from runtime_activity_slots import generate_activity_slots
from runtime_job_slots import generate_job_slots
from self_hook_native import build_native, add_platform_inputs


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--build-tools', required=True)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--ndk', required=True, type=Path)
    add_platform_inputs(parser)
    args=parser.parse_args()
    android=Path(__file__).resolve().parents[1];repo=android.parent;build=android/'product/build'
    source_roots=[android/name for name in ['runtime/src','runtime/native','runtime/licenses','control','control-test','engine','workbench','self-hook/java','self-hook/native','self-hook/include']]
    source_roots.append(repo/'src')
    files=sorted(p for directory in source_roots for p in directory.rglob('*') if p.is_file() and 'build' not in p.parts)
    files += [Path(__file__).resolve(),android/'scripts/runtime_apk_builder.py',android/'scripts/runtime_activity_slots.py',android/'scripts/runtime_job_slots.py',android/'scripts/self_hook_native.py']
    self_hook_java=sorted((android/'self-hook/java').rglob('*.java'))
    files += self_hook_java
    fixture_base=android/'runtime-probe/build/a.apk';fixture_upgrade=android/'runtime-probe/build/assets/a-upgrade.apk'
    fixture_preview=android/'runtime-probe/build/preview-guest.apk'
    fixture_receipt=json.loads((android/'runtime-probe/build/build-receipt.json').read_text())
    fixture_info={'baseSha256':sha(fixture_base),'upgradeSha256':sha(fixture_upgrade),'previewSha256':sha(fixture_preview),'apkRewritten':False}
    if fixture_info['baseSha256']!=fixture_receipt['fixtures']['a']['sha256'] or fixture_info['upgradeSha256']!=fixture_receipt['p1Fixtures']['upgrade']['sha256']:raise RuntimeError('CONTROL_FIXTURE_DIGEST_MISMATCH')
    runtime_apk=android/'runtime-probe/build/runtime-probe.apk'
    if sha(runtime_apk)!=fixture_receipt['hostApkSha256']:raise RuntimeError('RUNTIME_FIXTURE_CONTAINER_DIGEST_MISMATCH')
    with zipfile.ZipFile(runtime_apk) as archive:
        if fixture_info['previewSha256']!=json.loads(archive.read('assets/preview-fixture.json'))['sha256']:raise RuntimeError('PREVIEW_FIXTURE_DIGEST_MISMATCH')
    launcher_source=android/'runtime-fixtures/preview/dev/tripoder/previewguest/CounterActivity.java'
    files += [fixture_base,fixture_upgrade,fixture_preview,launcher_source]
    before={str(p.relative_to(repo)):sha(p) for p in files}
    builder=ApkBuilder(repo,build,args.sdk,args.platform,args.build_tools,args.java_home,android/'runtime-probe/build/acceptance-only.p12')
    fixture_launcher=builder.apk('launcher-guest',[launcher_source],android/'control-test/launcher-fixture/AndroidManifest.xml')
    fixture_info['launcherSha256']=sha(fixture_launcher)
    generated=build/'generated';generated.mkdir(exist_ok=True)
    common=sorted((android/'control/src').rglob('*.java'))
    native=build/'native';native.mkdir(exist_ok=True)
    self_hook, native_receipt=build_native(repo,native,args.ndk,args.platform_libc,args.platform_art,args.platform_linker)
    assets=build/'assets'
    if assets.exists():shutil.rmtree(assets)
    assets.mkdir();shutil.copytree(android/'runtime/licenses',assets/'licenses')
    entries=[]
    for slot,letter in enumerate('ABCDEFGH'):
        entries.append(f'<service android:name="dev.tripoder.runtime.RuntimeSlot$Worker{letter}" android:exported="false" android:process=":slot{slot}"/>')
        for service in range(4):entries.append(f'<service android:name="dev.tripoder.runtime.RuntimeServiceSlots$S{slot}_{service}" android:exported="false" android:process=":slot{slot}"/>')
        entries.append(f'<provider android:name="dev.tripoder.runtime.RuntimeProviderTransport$P{slot}" android:authorities="dev.tripoder.engine.runtime.provider.{slot}" android:exported="false" android:process=":slot{slot}"/>')
        entries.append(f'<receiver android:name="dev.tripoder.runtime.RuntimeReceiverSlots$R{slot}" android:exported="false" android:process=":slot{slot}"/>')
    manifest=generated/'EngineManifest.xml';text=(android/'engine/AndroidManifest.xml').read_text()
    if text.count('<!-- PROCESS_COMPONENT_POOL -->')!=1:raise RuntimeError('PROCESS_COMPONENT_POOL_MARKER_REQUIRED')
    manifest.write_text(text.replace('<!-- PROCESS_COMPONENT_POOL -->','\n'.join(entries)))
    job_sources,manifest=generate_job_slots(build,manifest)
    slot_sources,manifest=generate_activity_slots(build,manifest,"TaskP")
    slot_sources+=job_sources
    core=sorted(p for p in (android/'runtime/src').rglob('*.java') if p.name!='RuntimeAuthorityService.java')
    sources=core+self_hook_java+common+sorted((android/'engine/src').rglob('*.java'))+sorted((android/'workbench/src').rglob('*.java'))+slot_sources
    engine=builder.apk('tripoder',sources,manifest,assets=assets,libraries=[self_hook],jars=[builder.apksig()])
    with zipfile.ZipFile(engine) as archive:
        if sorted(name for name in archive.namelist() if name.startswith('lib/'))!=['lib/arm64-v8a/libtripoderselfhook.so']:
            raise RuntimeError('PRODUCT_NATIVE_PACKAGING_MISMATCH')
    test_assets=build/'test-assets';test_assets.mkdir(exist_ok=True)
    shutil.copyfile(fixture_base,test_assets/'a.apk');shutil.copyfile(fixture_upgrade,test_assets/'a-upgrade.apk');shutil.copyfile(fixture_preview,test_assets/'preview-guest.apk');(test_assets/'install-fixtures.json').write_text(json.dumps(fixture_info))
    shutil.copyfile(fixture_launcher,test_assets/'launcher-guest.apk')
    control_test=builder.apk('tripoder-control-test',common+sorted((android/'control-test/src').rglob('*.java')),android/'control-test/AndroidManifest.xml',assets=test_assets)
    after={str(p.relative_to(repo)):sha(p) for p in files}
    if before!=after:raise RuntimeError('SOURCES_CHANGED_DURING_BUILD')
    receipt={'sourceSha256':before,'artifacts':{'engine':{'path':str(engine),'sha256':sha(engine),'package':'dev.tripoder.engine'}},'certificateSha256':builder.cert_sha256,'selfHookNative':native_receipt,'fridaInputs':[],'productFridaRemoved':True,'developmentSigningOnly':True,'virtualAppCodeUsed':False,'legacyRunnerIncluded':False,'probeControllerIncluded':False,'generatedManifestSha256':sha(manifest),'schema':1}
    receipt['artifacts']['controlTest']={'path':str(control_test),'sha256':sha(control_test),'package':'dev.tripoder.controltest','testOnly':True}
    receipt['installFixtures']=fixture_info
    (build/'build-receipt.json').write_text(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
