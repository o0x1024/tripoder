#!/usr/bin/env python3
"""Build the single Tripoder APK (workbench UI + engine runtime, package dev.tripoder.engine); excludes legacy runners and probe controllers.

Uses the existing explicit development key. These APKs are not release-signed artifacts.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
from runtime_apk_builder import ApkBuilder, sha
from runtime_activity_slots import generate_activity_slots
from runtime_job_slots import generate_job_slots
from self_hook_native import build_native, add_platform_inputs


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-dir', type=Path, help='Explicit isolated output directory for concurrent builds')
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--build-tools', required=True)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--recovery-acceptance',action='store_true',help='Include owned fault fixture ONLY in a separate QA package')
    parser.add_argument('--startup-diagnostics',action='store_true',help='Record bounded early Application thread samples ONLY in a separate QA package')
    parser.add_argument('--mcp-port',type=int,help='Explicit MCP port for a separate QA package; production port is unchanged')
    parser.add_argument('--package', default='dev.tripoder.engine',
                        help='Application id. A second id installs alongside the original, which is the only way to '
                             'validate a build on a device signed with a key this machine does not hold.')
    add_platform_inputs(parser)
    args=parser.parse_args()
    package=args.package
    if args.mcp_port is not None and (package=='dev.tripoder.engine' or not 1024<=args.mcp_port<=65535):raise SystemExit('MCP_PORT_REQUIRES_SEPARATE_QA_PACKAGE_AND_VALID_PORT')
    if args.recovery_acceptance and package=='dev.tripoder.engine':raise SystemExit('RECOVERY_FIXTURE_REQUIRES_SEPARATE_QA_PACKAGE')
    if args.startup_diagnostics and package=='dev.tripoder.engine':raise SystemExit('STARTUP_DIAGNOSTICS_REQUIRES_SEPARATE_QA_PACKAGE')
    if not package or any(part=='' or not part.replace('_','a').isalnum() for part in package.split('.')) or '.' not in package:
        raise SystemExit('INVALID_PACKAGE:'+repr(package))
    android=Path(__file__).resolve().parents[1];repo=android.parent;build=args.build_dir.resolve() if args.build_dir else android/'product/build'
    source_roots=[android/name for name in ['runtime/src','runtime/native','runtime/licenses','control','control-test','engine','workbench','self-hook/java','self-hook/native','self-hook/include']]
    source_roots.append(repo/'src')
    if args.recovery_acceptance:source_roots.append(android/'recovery-test/src')
    files=sorted(p for directory in source_roots for p in directory.rglob('*') if p.is_file() and 'build' not in p.parts)
    files += [Path(__file__).resolve(),android/'scripts/runtime_apk_builder.py',android/'scripts/runtime_activity_slots.py',android/'scripts/runtime_job_slots.py',android/'scripts/self_hook_native.py']
    self_hook_java=sorted((android/'self-hook/java').rglob('*.java'))
    files += self_hook_java
    files += sorted((repo/'agent').glob('*.mjs'))
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
    fixture_singletask=builder.apk('launcher-singletask',[launcher_source],android/'control-test/launcher-singletask/AndroidManifest.xml')
    fixture_info['launcherSha256']=sha(fixture_launcher)
    probe_fixture=android/'control-test/code-fixture'
    payload=builder.apk('code-payload',[probe_fixture/'payload/Payload.java'],probe_fixture/'payload/AndroidManifest.xml')
    payload_assets=build/'code-payload-assets';payload_assets.mkdir(exist_ok=True)
    with zipfile.ZipFile(payload) as archive:(payload_assets/'payload.dex').write_bytes(archive.read('classes.dex'))
    fixture_probe=builder.apk('code-probe-guest',[probe_fixture/'ProbeApplication.java',probe_fixture/'ProbeActivity.java'],probe_fixture/'AndroidManifest.xml',assets=payload_assets)
    fixture_info['probeSha256']=sha(fixture_probe);fixture_info['payloadSha256']=sha(payload_assets/'payload.dex')
    application_contract=android/'control-test/application-contract-fixture'
    contract_probe=builder.apk('application-contract-guest',sorted(application_contract.glob('*.java')),application_contract/'AndroidManifest.xml')
    autofill_contract=android/'control-test/autofill-contract-fixture'
    autofill_probe=builder.apk('autofill-contract-guest',sorted(autofill_contract.glob('*.java')),autofill_contract/'AndroidManifest.xml')
    result_contract=android/'control-test/result-contract-fixture'
    result_probe=builder.apk('result-contract-guest',sorted(result_contract.glob('*.java')),result_contract/'AndroidManifest.xml')
    biometric_contract=android/'control-test/biometric-contract-fixture'
    biometric_probe=builder.apk('biometric-contract-guest',sorted(biometric_contract.glob('*.java')),biometric_contract/'AndroidManifest.xml')
    task_fixture=android/'control-test/task-presentation-fixture'
    task_probe=builder.apk('task-presentation-guest',sorted(task_fixture.glob('*.java')),task_fixture/'AndroidManifest.xml',resources=task_fixture/'res')
    fixture_info['taskPresentationSha256']=sha(task_probe)
    fixture_info['applicationContractSha256']=sha(contract_probe)
    generated=build/'generated';generated.mkdir(exist_ok=True)
    common=sorted((android/'control/src').rglob('*.java'))
    native=build/'native';native.mkdir(exist_ok=True)
    self_hook, native_receipt=build_native(repo,native,args.ndk,args.platform_libc,args.platform_art,args.platform_linker)
    assets=build/'assets'
    if assets.exists():shutil.rmtree(assets)
    assets.mkdir();shutil.copytree(android/'runtime/licenses',assets/'licenses')
    subprocess.run(['node',str(repo/'agent/export-contract.mjs'),str(assets/'mcp-contract.json')],cwd=repo,check=True)
    entries=[]
    for slot,letter in enumerate('ABCDEFGH'):
        entries.append(f'<service android:name="dev.tripoder.runtime.RuntimeSlot$Worker{letter}" android:exported="false" android:process=":slot{slot}"/>')
        for service in range(16):entries.append(f'<service android:name="dev.tripoder.runtime.RuntimeServiceSlots$S{slot}_{service}" android:exported="false" android:process=":slot{slot}"/>')
        entries.append(f'<provider android:name="dev.tripoder.runtime.RuntimeProviderTransport$P{slot}" android:authorities="{package}.runtime.provider.{slot}" android:exported="false" android:process=":slot{slot}"/>')
        entries.append(f'<receiver android:name="dev.tripoder.runtime.RuntimeReceiverSlots$R{slot}" android:exported="false" android:process=":slot{slot}"/>')
    entries.append('<service android:name="dev.tripoder.runtime.RuntimeIsolatedService" android:exported="false" android:isolatedProcess="true" android:process=":isolated"/>')
    manifest=generated/'EngineManifest.xml';text=(android/'engine/AndroidManifest.xml').read_text()
    if args.recovery_acceptance:text=text.replace('<!-- PROCESS_COMPONENT_POOL -->','<service android:name="dev.tripoder.runtime.RecoveryFaultService" android:process=":authority" android:exported="false"/><!-- PROCESS_COMPONENT_POOL -->')
    if args.startup_diagnostics:text=text.replace('<!-- PROCESS_COMPONENT_POOL -->','<meta-data android:name="tripoder.diagnostics.startup" android:value="true"/><!-- PROCESS_COMPONENT_POOL -->')
    if text.count('<!-- PROCESS_COMPONENT_POOL -->')!=1:raise RuntimeError('PROCESS_COMPONENT_POOL_MARKER_REQUIRED')
    # One pass for every place the id is embedded (manifest package, provider authorities, task affinity), so a renamed
    # build can never share an authority or affinity with the install it sits next to.
    text=text.replace('dev.tripoder.engine','@PACKAGE@').replace('@PACKAGE@',package)
    residual=text.replace(package,'')
    if 'dev.tripoder.engine' in residual:
        raise RuntimeError('PACKAGE_OVERRIDE_INCOMPLETE:'+','.join(sorted({line.strip() for line in text.splitlines() if 'dev.tripoder.engine' in line and package not in line})))
    manifest.write_text(text.replace('<!-- PROCESS_COMPONENT_POOL -->','\n'.join(entries)))
    # The harness binds and starts the engine by its instrumentation target package, so the same sources can validate
    # a renamed build installed next to an unmodifiable original. One target id, substituted in one place.
    control_manifest=generated/'ControlTestManifest.xml';control_text=(android/'control-test/AndroidManifest.xml').read_text()
    if 'dev.tripoder.engine' not in control_text:raise RuntimeError('CONTROL_TEST_TARGET_REQUIRED')
    control_manifest.write_text(control_text.replace('dev.tripoder.engine',package))
    job_sources,manifest=generate_job_slots(build,manifest)
    slot_sources,manifest=generate_activity_slots(build,manifest,"TaskP")
    slot_sources+=job_sources
    core=sorted(p for p in (android/'runtime/src').rglob('*.java') if p.name!='RuntimeAuthorityService.java')
    sources=core+self_hook_java+common+sorted((android/'engine/src').rglob('*.java'))+sorted((android/'workbench/src').rglob('*.java'))+slot_sources
    if args.mcp_port is not None:
        import re
        identity=android/'workbench/src/dev/tripoder/hooklab/AgentNetworkIdentity.java'
        override=generated/'AgentNetworkIdentity.java'
        replaced,count=re.subn(r'\bPORT\s*=\s*\d+', 'PORT='+str(args.mcp_port), identity.read_text())
        if count!=1:raise RuntimeError('QA_MCP_PORT_SOURCE_CONTRACT')
        override.write_text(replaced)
        sources=[path for path in sources if path!=identity]+[override]
    if args.recovery_acceptance:sources+=sorted((android/'recovery-test/src').rglob('*.java'))
    engine=builder.apk('tripoder' if package=='dev.tripoder.engine' else 'tripoder-'+package.rsplit('.',1)[-1],sources,manifest,assets=assets,libraries=[self_hook],jars=[builder.apksig()])
    with zipfile.ZipFile(engine) as archive:
        if sorted(name for name in archive.namelist() if name.startswith('lib/'))!=['lib/arm64-v8a/libtripoderselfhook.so']:
            raise RuntimeError('PRODUCT_NATIVE_PACKAGING_MISMATCH')
    test_assets=build/'test-assets';test_assets.mkdir(exist_ok=True)
    shutil.copyfile(fixture_base,test_assets/'a.apk');shutil.copyfile(fixture_upgrade,test_assets/'a-upgrade.apk');shutil.copyfile(fixture_preview,test_assets/'preview-guest.apk');(test_assets/'install-fixtures.json').write_text(json.dumps(fixture_info))
    shutil.copyfile(fixture_launcher,test_assets/'launcher-guest.apk')
    shutil.copyfile(fixture_singletask,test_assets/'launcher-singletask.apk')
    shutil.copyfile(fixture_probe,test_assets/'code-probe-guest.apk')
    shutil.copyfile(contract_probe,test_assets/'application-contract-guest.apk')
    shutil.copyfile(autofill_probe,test_assets/'autofill-contract-guest.apk')
    shutil.copyfile(result_probe,test_assets/'result-contract-guest.apk')
    shutil.copyfile(biometric_probe,test_assets/'biometric-contract-guest.apk')
    shutil.copyfile(task_probe,test_assets/'task-presentation-guest.apk')
    control_test=builder.apk('tripoder-control-test',common+sorted((android/'control-test/src').rglob('*.java')),control_manifest,assets=test_assets)
    after={str(p.relative_to(repo)):sha(p) for p in files}
    if before!=after:raise RuntimeError('SOURCES_CHANGED_DURING_BUILD')
    receipt={'sourceSha256':before,'artifacts':{'engine':{'path':str(engine),'sha256':sha(engine),'package':package}},'certificateSha256':builder.cert_sha256,'selfHookNative':native_receipt,'fridaInputs':[],'productFridaRemoved':True,'developmentSigningOnly':True,'virtualAppCodeUsed':False,'legacyRunnerIncluded':False,'probeControllerIncluded':False,'generatedManifestSha256':sha(manifest),'schema':1}
    receipt['artifacts']['controlTest']={'path':str(control_test),'sha256':sha(control_test),'package':'dev.tripoder.controltest','testOnly':True}
    receipt['installFixtures']=fixture_info
    receipt['recoveryAcceptanceFixture']=args.recovery_acceptance
    receipt['startupDiagnostics']=args.startup_diagnostics
    receipt['mcpPortOverride']=args.mcp_port
    (build/'build-receipt.json').write_text(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
