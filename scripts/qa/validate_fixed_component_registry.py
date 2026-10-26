#!/usr/bin/env python3
"""Run existing fixed-adapter physical oracles inside the installed QA package; never clears data."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import uuid
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
sdk = Path('/opt/homebrew/share/android-commandlinetools')
java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')
tools = sdk / 'build-tools/37.0.0'
platform = sdk / 'platforms/android-37.0/android.jar'
receipt_path = repo / 'android/product/build/build-receipt.json'
receipt = json.loads(receipt_path.read_text())
engine = receipt['artifacts']['engine']
package = engine['package']
if not package.startswith('dev.tripoder.compat'):
    raise RuntimeError('OWNED_QA_PACKAGE_REQUIRED')
sha = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
for name, digest in receipt['sourceSha256'].items():
    if sha(repo / name) != digest:
        raise RuntimeError('SOURCE_CHANGED_SINCE_BUILD:' + name)
if sha(engine['path']) != engine['sha256']:
    raise RuntimeError('PRODUCT_APK_CHANGED')
out = args.evidence.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(receipt_path, out / 'product-receipt.json')
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + os.pathsep + env.get('PATH', '')


def run(*command, timeout=120):
    value = subprocess.run(list(map(str, command)), capture_output=True, text=True, env=env, timeout=timeout)
    if value.returncode:
        raise RuntimeError(value.stdout + value.stderr)
    return value.stdout


adb = ['adb', '-s', args.serial]
paths = run(*adb, 'shell', 'pm', 'path', package).splitlines()
base = next((p.removeprefix('package:') for p in paths if p.endswith('/base.apk')), None)
if base is None or not base.startswith('/data/app/') or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/._-=+~' for c in base):
    raise RuntimeError('INSTALLED_QA_BASE_PATH_INVALID')
installed_sha = run(*adb, 'shell', 'sha256sum', base).split()[0]
if installed_sha != engine['sha256']:
    raise RuntimeError('INSTALLED_QA_PRODUCT_MISMATCH')
sources = [repo / 'android/compatibility-test/FixedComponentRegistryIntegration.java',
           repo / 'android/compatibility-test/FixedRegistryProgress.java',
           repo / 'android/compatibility-test/InstrumentationEntryChecks.java',
           repo / 'android/self-hook-probe/src/dev/tripoder/runtime/OwnedComponentInstallerChecks.java',
           repo / 'android/self-hook-probe/src/dev/tripoder/selfhook/OwnedProductFrameworkChecks.java',
           repo / 'android/self-hook-probe/src/dev/tripoder/selfhook/OwnedRemainingInventoryChecks.java',
           repo / 'android/self-hook-probe/src/dev/tripoder/selfhook/OwnedServiceTokenChecks.java',
           repo / 'android/self-hook-probe/src/dev/tripoder/selfhook/OwnedSystemReceiverChecks.java']
frozen = []
source_hashes = {}
for source in sources:
    target = out / source.name
    shutil.copyfile(source, target)
    frozen.append(target)
    source_hashes[source.name] = sha(target)
classes, dex = out / 'classes', out / 'dex'
classes.mkdir()
dex.mkdir()
product_classes = Path(engine['path']).with_suffix('') / 'classes'
run(java / 'bin/javac', '--release', '17', '-cp', str(platform) + os.pathsep + str(product_classes), '-d', classes, *frozen)
run(tools / 'd8', '--release', '--min-api', '36', '--lib', platform, '--classpath', product_classes, '--output', dex, *classes.rglob('*.class'))
manifest = out / 'AndroidManifest.xml'
manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}.tests"><uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><instrumentation android:name="dev.tripoder.runtime.FixedComponentRegistryIntegration" android:targetPackage="{package}"/><application/></manifest>')
unsigned, aligned, apk = out / 'unsigned.apk', out / 'aligned.apk', out / 'checks.apk'
run(tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned)
with zipfile.ZipFile(unsigned, 'a') as archive:
    for file in dex.glob('*.dex'):
        archive.write(file, file.name)
run(tools / 'zipalign', '-f', '4', unsigned, aligned)
run(tools / 'apksigner', 'sign', '--ks', repo / 'android/runtime-probe/build/acceptance-only.p12', '--ks-pass', 'pass:tripoder-test-only', '--ks-key-alias', 'p0', '--out', apk, aligned)
run(*adb, 'install', '--no-incremental', '-r', apk)
# These physical oracles prepare process-global ART state. Establish the fresh
# QA process promised by this runner before preparing that state once.
run(*adb, 'shell', 'am', 'force-stop', package)
(out / 'process-precondition.json').write_text(json.dumps({'freshQaProcessRequired': True, 'stoppedPackage': package, 'dataCleared': False}) + '\n')
run_id = str(uuid.uuid4())
(out / 'run.json').write_text(json.dumps({'run': run_id, 'productApkSha256': engine['sha256']}) + '\n')
try:
    raw = run(*adb, 'shell', 'am', 'instrument', '-w', '-e', 'run', run_id, package + '.tests/dev.tripoder.runtime.FixedComponentRegistryIntegration', timeout=180)
except subprocess.TimeoutExpired as error:
    raw = error.stdout or ''
    if isinstance(raw, bytes):
        raw = raw.decode('utf-8', errors='replace')
    (out / 'instrumentation.txt').write_text(raw)
    for suffix in ('progress', 'stacks'):
        observed = subprocess.run([*adb, 'shell', 'cat', '/sdcard/Android/data/' + package + '/files/fixed-registry-' + run_id + '.' + suffix + '.json'], capture_output=True, text=True, timeout=15)
        try:
            value = json.loads(observed.stdout) if observed.returncode == 0 else {'available': False, 'readReturnCode': observed.returncode}
        except ValueError:
            value = {'available': False, 'reason': 'OWNED_DIAGNOSTIC_JSON_INVALID'}
        (out / (suffix + '.json')).write_text(json.dumps(value, indent=2) + '\n')
    raise RuntimeError('OWNED_FIXED_REGISTRY_DEADLINE_WITH_DIAGNOSTICS') from error
(out / 'instrumentation.txt').write_text(raw)
remote = '/sdcard/Android/data/' + package + '/files/fixed-registry-' + run_id + '.json'
result = json.loads(run(*adb, 'shell', 'cat', remote))
result.update(productApkSha256=engine['sha256'], testApkSha256=sha(apk), testSourcesSha256=source_hashes,
              installedProductSha256=installed_sha, run=run_id)
(out / 'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
if result.get('passed') is not True:
    raise RuntimeError(result.get('error', 'OWNED_FIXED_REGISTRY_FAILED'))
component = result['framework']['componentInstall']
if component['fixedMethods'] != 704 or component['groups'] != [18, 28, 6, 28] or component['remainingGroups'] != [6, 11, 10, 517, 62, 1, 2, 2, 4, 5, 1, 1, 2] or component['checks'] != 22:
    raise RuntimeError('OWNED_FIXED_REGISTRY_RECEIPT_MISMATCH')
if component['entryOnlyBootRejected'] is not True or component['unselectedIntentServiceRejected'] is not True:
    raise RuntimeError('OWNED_FIXED_BOOT_NEGATIVE_MISSING')
if component['serviceTokenScope']['passed'] is not True or component['serviceTokenScope']['checks'] != 8 or component['serviceTokenScope']['physicalAmsCalled'] is not False:
    raise RuntimeError('OWNED_SERVICE_TOKEN_SCOPE_NEGATIVE_MISSING')
if component['systemReceiverScope']['passed'] is not True or component['systemReceiverScope']['checks'] != 33 or component['systemReceiverScope']['physicalRegistrationCalled'] is not False:
    raise RuntimeError('OWNED_SYSTEM_RECEIVER_ROLE_NEGATIVE_MISSING')
entry = result['instrumentationEntry']
if entry['passed'] is not True or entry['checks'] != 14 or entry['lateCallbackPublications'] != 2 or entry['additionalGlobalInvalidations'] != 0:
    raise RuntimeError('OWNED_INSTRUMENTATION_ENTRY_MISMATCH')
print(json.dumps({'passed': True, 'fixedMethods': component['fixedMethods'], 'componentChecks': component['checks'],
                  'productRoutingAccepted': False, 'result': str(out / 'result.json')}))
