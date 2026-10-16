"""Independent SDK-signed P1 catalog fixtures; these metadata APKs have no executable dex.
Call build_fixtures from the main probe builder. No device calls or product modifications.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import warnings
import zipfile


def build_fixtures(*, tools, platform, java_home, output_dir, signing_key, native_library):
    tools, platform, java_home, output_dir, signing_key, native_library = map(
        Path, (tools, platform, java_home, output_dir, signing_key, native_library))
    output_dir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(java_home))
    env['PATH'] = str(java_home / 'bin') + os.pathsep + env.get('PATH', '')
    package = 'dev.tripoder.p1.split'
    metadata = {}

    def run(*command):
        result = subprocess.run(list(map(str, command)), env=env, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError('P1 fixture tool failed: ' + result.stderr + result.stdout)
        return result.stdout

    def record(name, **fields):
        file = output_dir / ('p1-' + name + '.apk')
        metadata[name] = dict(asset=file.name, sha256=hashlib.sha256(file.read_bytes()).hexdigest(), **fields)
        return file

    with tempfile.TemporaryDirectory(prefix='tripoder-p1-fixtures-') as temporary:
        root = Path(temporary)
        other_key = root / 'other.p12'
        run(java_home / 'bin/keytool', '-genkeypair', '-keystore', other_key, '-storetype', 'PKCS12',
            '-storepass', 'tripoder-test-only', '-keypass', 'tripoder-test-only', '-alias', 'p0', '-keyalg',
            'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=Tripoder P1 Other Test Only', '-noprompt')

        def apk(name, split='', attrs='', children='', app='', app_attrs='', version=1,
                pkg=package, key=signing_key, libraries=None, resources=None):
            directory = root / name
            directory.mkdir()
            manifest = directory / 'AndroidManifest.xml'
            split_attr = f' split="{split}"' if split else ''
            manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}" android:versionCode="{version}"{split_attr} {attrs}>
<uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/>{children}
<application android:hasCode="false" {app_attrs}>{app}</application></manifest>''')
            unsigned, aligned = directory / 'unsigned.apk', directory / 'aligned.apk'
            resource_args=[]
            if resources:
                res=directory/'res';(res/'values').mkdir(parents=True)
                (res/'values/flags.xml').write_text(resources)
                run(tools/'aapt2','compile','--dir',res,'-o',directory/'resources.zip')
                resource_args=[directory/'resources.zip']
            run(tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned,*resource_args)
            with zipfile.ZipFile(unsigned, 'a') as archive:
                for path, data in (libraries or {}).items():
                    archive.writestr(path, data)
            run(tools / 'zipalign', '-f', '4', unsigned, aligned)
            output = output_dir / ('p1-' + name + '.apk')
            run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'p0', '--ks-pass',
                'pass:tripoder-test-only', '--out', output, aligned)
            signature = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', output)
            return record(name, packageName=pkg, versionCode=version, splitName=split,
                          signatureVerified=True, signatureReport=signature)

        declarations = '''<activity android:name=".Main" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter></activity>
<activity-alias android:name=".Alias" android:targetActivity=".Main" android:exported="false"/>
<service android:name=".Worker" android:process=":worker"/><receiver android:name=".Receiver"/>
<provider android:name=".Provider" android:authorities="dev.tripoder.p1.split.provider"/>'''
        apk('base', app=declarations, app_attrs='android:process=":app"')
        apk('resource-flags', app='<service android:name=".Allowed" android:enabled="@bool/allowed" android:exported="@bool/denied"/><service android:name=".Disabled" android:enabled="@bool/denied"/>',resources='<resources><bool name="allowed">true</bool><bool name="denied">false</bool></resources>')
        apk('feature', split='feature', attrs='android:isFeatureSplit="true"',
            app='<activity android:name=".Feature" android:exported="false"/>')
        apk('config', split='config.en', attrs='configForSplit="feature"')
        apk('dependent', split='dependent', attrs='android:isFeatureSplit="true"', children='<uses-split android:name="absent"/>')
        apk('version2', split='feature', attrs='android:isFeatureSplit="true"', version=2)
        apk('other-signer', split='feature', attrs='android:isFeatureSplit="true"', key=other_key)
        apk('other-package', split='feature', attrs='android:isFeatureSplit="true"', pkg='dev.tripoder.p1.other')
        apk('native', libraries={'lib/arm64-v8a/libp0fixture.so': native_library.read_bytes()})
        apk('bad-native', libraries={'lib/arm64-v8a/libbad.so': b'\x7fELF' + bytes(16)})
        apk('wrong-abi', libraries={'lib/x86_64/libbad.so': b'wrong ABI'})
        apk('cycle-a', split='cycle_a', attrs='android:isFeatureSplit="true"', children='<uses-split android:name="cycle_b"/>')
        apk('cycle-b', split='cycle_b', attrs='android:isFeatureSplit="true"', children='<uses-split android:name="cycle_a"/>')
        apk('required', app_attrs='android:isSplitRequired="true"')
        tampered = output_dir / 'p1-tampered.apk'
        shutil.copyfile(output_dir / 'p1-base.apk', tampered)
        with zipfile.ZipFile(tampered, 'a') as archive:
            archive.writestr('tampered.txt', 'changed after signing')
        record('tampered', signatureVerified=False)
        for name in ['zip-traversal', 'zip-symlink', 'zip-duplicate']:
            with zipfile.ZipFile(output_dir / ('p1-' + name + '.apk'), 'w') as archive:
                if name == 'zip-traversal':
                    archive.writestr('../outside', 'unsafe')
                elif name == 'zip-symlink':
                    entry = zipfile.ZipInfo('link')
                    entry.create_system = 3
                    entry.external_attr = 0o120777 << 16
                    archive.writestr(entry, '../outside')
                else:
                    archive.writestr('duplicate', 'first')
                    with warnings.catch_warnings():
                        warnings.simplefilter('ignore', UserWarning)
                        archive.writestr('duplicate', 'second')
            record(name, signatureVerified=False)

    cases = {}

    def case(name, parts, accepted=False, error='', expected_parts=0):
        cases[name] = dict(files=[dict(asset=metadata[p]['asset'], sha256=metadata[p]['sha256']) for p in parts],
                           accepted=accepted, error=error, parts=expected_parts)

    case('base-only', ['base'], True, expected_parts=1)
    case('resource-flags', ['resource-flags'], True, expected_parts=1)
    case('complete-splits', ['base', 'feature', 'config'], True, expected_parts=3)
    case('native', ['native'], True, expected_parts=1)
    case('missing-feature', ['base', 'config'], error='SPLIT_DEPENDENCY_MISSING')
    case('missing-dependency', ['base', 'dependent'], error='SPLIT_DEPENDENCY_MISSING')
    case('version-mismatch', ['base', 'version2'], error='APK_SET_IDENTITY_MISMATCH')
    case('signer-mismatch', ['base', 'other-signer'], error='APK_SET_SIGNER_MISMATCH')
    case('package-mismatch', ['base', 'other-package'], error='APK_SET_IDENTITY_MISMATCH')
    case('dependency-cycle', ['base', 'cycle-a', 'cycle-b'], error='SPLIT_DEPENDENCY_CYCLE')
    case('required-split', ['required'], error='REQUIRED_SPLIT_MISSING')
    case('bad-native', ['bad-native'], error='NATIVE_ELF_NOT_ARM64_SHARED')
    case('wrong-abi', ['wrong-abi'], error='ABI_OR_LIBRARY_UNSUPPORTED')
    case('bad-signature', ['tampered'], error='APK_SIGNATURE_INVALID')
    case('zip-traversal', ['zip-traversal'], error='UNSAFE_ZIP_PATH')
    case('zip-symlink', ['zip-symlink'], error='ZIP_LINK_OR_SPECIAL_FILE')
    case('zip-duplicate', ['zip-duplicate'], error='ZIP_DUPLICATE_ENTRY')
    result = dict(schema=1, packageName=package, cases=cases, apkMetadata=metadata,
                  scope='signed-metadata-installation-only-no-component-execution')
    (output_dir / 'p1-fixtures.json').write_text(json.dumps(result, indent=2))
    return result
