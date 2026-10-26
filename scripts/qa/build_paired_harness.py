#!/usr/bin/env python3
"""Build/update only the existing independent QA instrumentation, never the product."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='d861a4fa')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
sdk = Path('/opt/homebrew/share/android-commandlinetools')
java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')
tools, platform = sdk / 'build-tools/37.0.0', sdk / 'platforms/android-37.0/android.jar'
receipt = json.loads((root / 'android/product/build/build-receipt.json').read_text())
engine = Path(receipt['artifacts']['engine']['path'])
package = receipt['artifacts']['engine']['package']
if not package.startswith('dev.tripoder.compat'):
    raise RuntimeError('OWNED_SEPARATE_QA_REQUIRED')
if hashlib.sha256(engine.read_bytes()).hexdigest() != receipt['artifacts']['engine']['sha256']:
    raise RuntimeError('PAIRED_PRODUCT_CHANGED')
for name, expected in receipt['sourceSha256'].items():
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
        raise RuntimeError('PAIRED_PRODUCT_SOURCE_CHANGED:' + name)
adb = ['adb', '-s', args.serial]
paths = subprocess.run(adb + ['shell', 'pm', 'path', package], capture_output=True, text=True, check=True).stdout.splitlines()
if len(paths) != 1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9/._~+=-]+/base\.apk', paths[0]):
    raise RuntimeError('PAIRED_INSTALLED_PRODUCT_PATH_INVALID')
installed = subprocess.run(adb + ['shell', 'sha256sum', paths[0].removeprefix('package:')], capture_output=True, text=True, check=True).stdout.split()
if not installed or installed[0] != receipt['artifacts']['engine']['sha256']:
    raise RuntimeError('PAIRED_INSTALLED_PRODUCT_MISMATCH')
out = root / 'android/compatibility-test/build'
out.mkdir(exist_ok=True)
classes, dex = out / 'paired-classes', out / 'paired-dex'
for directory in (classes, dex):
    if directory.exists():
        shutil.rmtree(directory)  # This builder's generated classes/dex only.
    directory.mkdir()
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + os.pathsep + env.get('PATH', '')


def run(*command):
    subprocess.run(list(map(str, command)), env=env, check=True, stdout=subprocess.DEVNULL)


names = ['PairedLaunchIntegration.java', 'JobMutationInspection.java', 'LauncherMetadataInspection.java',
         'NativeCodeInspection.java', 'NativeMissingServiceInspection.java', 'BroadcastDeliveryInspection.java',
         'OwnedExitInspection.java', 'StoppedOperationInspection.java', 'IsolatedExecutionInspection.java', 'NativePublicationInspection.java', 'IsolatedRecoveryIntegration.java', 'TaskRootInspection.java', 'http-fixture/HttpChecks.java',
         'launcher-fixture/OrderedResultChecks.java']
sources = [root / 'android/compatibility-test' / name for name in names]
product_classes = engine.with_suffix('') / 'classes'
run(java / 'bin/javac', '--release', '17', '-cp', str(platform) + os.pathsep + str(product_classes), '-d', classes, *sources)
run(tools / 'd8', '--release', '--min-api', '36', '--lib', platform, '--classpath', product_classes, '--output', dex, *classes.rglob('*.class'))
manifest = out / 'PairedManifest.xml'
manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}.tests"><uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><instrumentation android:name="dev.tripoder.hooklab.PairedLaunchIntegration" android:targetPackage="{package}"/><instrumentation android:name="dev.tripoder.hooklab.IsolatedRecoveryIntegration" android:targetPackage="{package}"/><application/></manifest>')
unsigned, aligned, apk = out / 'paired-unsigned.apk', out / 'paired-aligned.apk', out / 'paired.apk'
run(tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned)
with zipfile.ZipFile(unsigned, 'a') as archive:
    for file in dex.glob('*.dex'):
        archive.write(file, file.name)
run(tools / 'zipalign', '-f', '4', unsigned, aligned)
run(tools / 'apksigner', 'sign', '--ks', root / 'android/runtime-probe/build/acceptance-only.p12', '--ks-pass', 'pass:tripoder-test-only', '--ks-key-alias', 'p0', '--out', apk, aligned)
run('adb', '-s', args.serial, 'install', '--no-incremental', '-r', apk)
print(apk)
