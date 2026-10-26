#!/usr/bin/env python3
"""Validate the compatibility repair beside the original app. Never clears engine data.

Uses the frozen renamed product receipt and real Android SQLite; optional imports
copy original device APKs into this QA app's scratch directory and verify them.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--imports', action='store_true')
    parser.add_argument('--native-publication-apk', type=Path)
    parser.add_argument('--sdk', type=Path, default=Path('/opt/homebrew/share/android-commandlinetools'))
    parser.add_argument('--java-home', type=Path, default=Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    receipt_path = repo / 'android/product/build/build-receipt.json'
    receipt = json.loads(receipt_path.read_text())
    engine = receipt['artifacts']['engine']
    package = engine['package']
    if not package.startswith('dev.tripoder.compat'):
        raise RuntimeError('SEPARATE_COMPAT_PACKAGE_REQUIRED')
    for name, digest in receipt['sourceSha256'].items():
        if sha(repo / name) != digest:
            raise RuntimeError('SOURCE_CHANGED_SINCE_BUILD:' + name)
    if sha(engine['path']) != engine['sha256']:
        raise RuntimeError('PRODUCT_APK_CHANGED')
    out = args.evidence.resolve()
    out.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(receipt_path, out / 'product-receipt.json')
    shutil.copyfile(engine['path'], out / 'engine.apk')
    source = out / 'CompatibilityIntegration.java'
    shutil.copyfile(repo / 'android/compatibility-test/CompatibilityIntegration.java', source)
    restart_source = out / 'ComponentRestartChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/ComponentRestartChecks.java', restart_source)
    settings_source = out / 'SettingsReadOnlyChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/SettingsReadOnlyChecks.java', settings_source)
    context_source = out / 'ContextStartReceiptChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/ContextStartReceiptChecks.java', context_source)
    empty_task_source = out / 'EmptyTaskReclaimChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/EmptyTaskReclaimChecks.java', empty_task_source)
    space_source = out / 'SpaceCreationRecoveryChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/SpaceCreationRecoveryChecks.java', space_source)
    thin_source = out / 'ThinProviderAccessChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/ThinProviderAccessChecks.java', thin_source)
    launcher_source = out / 'LauncherResolutionChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/LauncherResolutionChecks.java', launcher_source)
    service_source = out / 'ServiceParcelChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/ServiceParcelChecks.java', service_source)
    component_query_source = out / 'ComponentStateQueryChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/ComponentStateQueryChecks.java', component_query_source)
    construction_source = out / 'ActivityConstructionChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/ActivityConstructionChecks.java', construction_source)
    stopped_source = out / 'StoppedExecutionRecoveryChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/StoppedExecutionRecoveryChecks.java', stopped_source)
    isolated_source = out / 'IsolatedSessionChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/IsolatedSessionChecks.java', isolated_source)
    native_source = out / 'IsolatedNativePublicationChecks.java'
    shutil.copyfile(repo / 'android/compatibility-test/IsolatedNativePublicationChecks.java', native_source)
    tools = args.sdk / 'build-tools/37.0.0'
    platform = args.sdk / 'platforms/android-37.0/android.jar'
    env = dict(os.environ, JAVA_HOME=str(args.java_home))
    env['PATH'] = str(args.java_home / 'bin') + os.pathsep + env.get('PATH', '')

    def run(*command, timeout=120):
        value = subprocess.run(list(map(str, command)), env=env, capture_output=True, text=True, timeout=timeout)
        if value.returncode:
            raise RuntimeError(str(command[0]) + ': ' + value.stdout + value.stderr)
        return value.stdout

    test_package = package + '.tests'
    manifest = out / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{test_package}">
<uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/>
<instrumentation android:name="dev.tripoder.runtime.CompatibilityIntegration" android:targetPackage="{package}"/>
<application android:label="Tripoder compatibility checks"/></manifest>''')
    classes, dex = out / 'classes', out / 'dex'
    classes.mkdir()
    dex.mkdir()
    product_classes = Path(engine['path']).with_suffix('') / 'classes'
    run(args.java_home / 'bin/javac', '--release', '17', '-cp', str(platform) + os.pathsep + str(product_classes), '-d', classes, source, restart_source, settings_source, context_source, empty_task_source, space_source, thin_source, launcher_source, service_source, component_query_source, construction_source, stopped_source, isolated_source, native_source)
    run(tools / 'd8', '--release', '--min-api', '36', '--lib', platform, '--classpath', product_classes,
        '--output', dex, *sorted(classes.rglob('*.class')))
    unsigned, aligned, apk = out / 'unsigned.apk', out / 'aligned.apk', out / 'compatibility-tests.apk'
    alias_manifest, alias_unsigned, alias_apk = out / 'AliasManifest.xml', out / 'aliases-unsigned.apk', out / 'aliases.apk'
    aliases = ''.join(f'<activity-alias android:name=".Alias{i}" android:targetActivity=".Main" android:enabled="true" android:exported="true"/>' for i in range(12))
    alias_manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.tripoder.aliasfixture" android:versionCode="1">
<uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><application android:hasCode="false"><uses-native-library android:name="libOpenCL.so" android:required="false"/><activity android:name=".Main" android:exported="true"/><activity android:name=".ReceiptTarget" android:process=":contexttarget" android:exported="false"/>{aliases}</application></manifest>''')
    run(tools / 'aapt2', 'link', '-I', platform, '--manifest', alias_manifest, '-o', alias_unsigned)
    run(tools / 'apksigner', 'sign', '--ks', repo / 'android/runtime-probe/build/acceptance-only.p12',
        '--ks-pass', 'pass:tripoder-test-only', '--ks-key-alias', 'p0', '--out', alias_apk, alias_unsigned)
    assets = out / 'assets'
    assets.mkdir()
    shutil.copyfile(alias_apk, assets / 'aliases.apk')
    fixture_hashes = {}
    for directory, name in [('owned-fixture-v54', 'thin-target.apk'), ('owned-factory-fixture-v1', 'thin-source.apk'), ('owned-launcher-fixture-v5', 'launcher-contract.apk'), ('owned-launcher-fixture-v2', 'launcher-literals.apk'), ('phase70-isolated-service-fixture-v1', 'isolated-service.apk')]:
        frozen = repo / 'docs/evidence/compatibility-closure-2026-10-23' / directory
        frozen_receipt = json.loads((frozen / 'receipt.json').read_text())
        digest = sha(frozen / 'fixture.apk')
        if digest != frozen_receipt['apkSha256']:
            raise RuntimeError('FROZEN_OWNED_FIXTURE_CHANGED:' + directory)
        shutil.copyfile(frozen / 'fixture.apk', assets / name)
        fixture_hashes[name] = digest
    if args.native_publication_apk is not None:
        native_receipt = json.loads((repo / 'android/isolated-fd-probe/build/receipt.json').read_text())
        if native_receipt['package'] != 'dev.tripoder.isolatedfdprobe' or sha(args.native_publication_apk) != native_receipt['apkSha256']:
            raise RuntimeError('OWNED_NATIVE_CURRENT_APK_CHANGED')
        original = repo / 'docs/evidence/compatibility-closure-2026-10-23/phase78-isolated-public-apk-v8'
        if sha(original / 'probe.apk') != json.loads((original / 'build-receipt.json').read_text())['apkSha256']:
            raise RuntimeError('OWNED_NATIVE_STALE_APK_CHANGED')
        companion = native_receipt['companions'][0]
        if companion['package'] != 'dev.tripoder.isolatedfdpayload' or sha(companion['apk']) != companion['sha256']:
            raise RuntimeError('OWNED_UNPUBLISHED_NATIVE_APK_CHANGED')
        for name, path in [('native-current.apk', args.native_publication_apk), ('native-stale.apk', original / 'probe.apk'), ('native-unpublished.apk', Path(companion['apk']))]:
            shutil.copyfile(path, assets / name)
            fixture_hashes[name] = sha(path)
    run(tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-A', assets, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        for file in dex.glob('*.dex'):
            archive.write(file, file.name)
    run(tools / 'zipalign', '-f', '4', unsigned, aligned)
    run(tools / 'apksigner', 'sign', '--ks', repo / 'android/runtime-probe/build/acceptance-only.p12',
        '--ks-pass', 'pass:tripoder-test-only', '--ks-key-alias', 'p0', '--out', apk, aligned)
    adb = ['adb', '-s', args.serial]
    before = run(*adb, 'shell', 'dumpsys', 'package', 'dev.tripoder.engine')
    (out / 'original-package-before.txt').write_text(before)
    run(*adb, 'install', '--no-incremental', '-r', out / 'engine.apk')
    run(*adb, 'install', '--no-incremental', '-r', apk)
    command = [*adb, 'shell', 'am', 'instrument', '-w', '-e', 'nativePublication', str(args.native_publication_apk is not None).lower(), '-e', 'imports', str(args.imports).lower(),
               test_package + '/dev.tripoder.runtime.CompatibilityIntegration']
    process = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        time.sleep(2)
        # HyperOS may freeze an instrumentation process with no visible Activity.
        # Keep this QA app visible while its scratch-DB / APK checks run.
        run(*adb, 'shell', 'input', 'keyevent', '224')
        run(*adb, 'shell', 'am', 'start', '-n', package + '/dev.tripoder.hooklab.WorkbenchActivity')
        stdout, stderr = process.communicate(timeout=1200)
        raw = stdout + stderr
    except BaseException:
        process.kill()
        process.wait()
        raise
    (out / 'instrumentation.txt').write_text(raw)
    payload = next((line.split('report=', 1)[1] for line in raw.splitlines() if line.startswith('INSTRUMENTATION_RESULT: report=')), None)
    report = json.loads(payload) if payload else {'passed': False, 'error': 'INSTRUMENTATION_RESULT_MISSING'}
    report.update(productApkSha256=engine['sha256'], package=package, testSourceSha256=sha(source),
                  testSourcesSha256={source.name:sha(source), restart_source.name:sha(restart_source), settings_source.name:sha(settings_source), context_source.name:sha(context_source), empty_task_source.name:sha(empty_task_source), space_source.name:sha(space_source), thin_source.name:sha(thin_source), launcher_source.name:sha(launcher_source), service_source.name:sha(service_source), component_query_source.name:sha(component_query_source), construction_source.name:sha(construction_source), stopped_source.name:sha(stopped_source), isolated_source.name:sha(isolated_source)},
                  nativePublicationChecksRequested=args.native_publication_apk is not None,
                  ownedFixtureSha256=fixture_hashes,
                  testApkSha256=sha(apk), originalDataCleared=False, productionAcceptance=False)
    report['testSourcesSha256'][native_source.name] = sha(native_source)
    (out / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
