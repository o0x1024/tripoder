#!/usr/bin/env python3
"""Build isolated signed metadata fixtures and exercise the production artifact implementation.
Only writes a new temporary directory. Does not build/install the product or access a device.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


# Compile-time and stub-only: the production classes name android.os/android.util/org.json types on paths this host test does not execute.
ANDROID_JAR = '/opt/homebrew/share/android-commandlinetools/platforms/android-37.0/android.jar'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    tools = args.sdk / 'build-tools/37.0.0'
    platform = args.sdk / 'platforms/android-37.0/android.jar'
    java = args.java_home / 'bin'
    env = dict(os.environ, JAVA_HOME=str(args.java_home))
    root = Path(tempfile.mkdtemp(prefix='tripoder-artifact-test-')).resolve()
    fixtures = root / 'fixtures'
    fixtures.mkdir()

    def run(*command, capture=False):
        result = subprocess.run(list(map(str, command)), env=env, capture_output=True, text=True)
        if result.returncode:
            print(result.stdout, end='')
            print(result.stderr, end='')
            result.check_returncode()
        return result

    for key in ['main', 'other']:
        run(java / 'keytool', '-genkeypair', '-keystore', root / (key + '.p12'), '-storetype', 'PKCS12',
            '-storepass', 'test-only', '-keypass', 'test-only', '-alias', 'test', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '2', '-dname', 'CN=Artifact Test ' + key, '-noprompt')

    def apk(name, split='', attributes='', children='', package='dev.tripoder.set', version=1,
            application='', app_attrs='', libs=None, signer='main', signed=True, resources=None):
        directory = root / name
        directory.mkdir()
        manifest = directory / 'AndroidManifest.xml'
        split_attribute = f' split="{split}"' if split else ''
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{version}"{split_attribute} {attributes}>
<uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/>{children}
<application android:hasCode="false" {app_attrs}>{application}</application></manifest>''')
        unsigned = directory / 'unsigned.apk'
        resource_args=[]
        if resources:
            res=directory/'res';(res/'values').mkdir(parents=True)
            (res/'values/flags.xml').write_text(resources)
            run(tools/'aapt2','compile','--dir',res,'-o',directory/'resources.zip')
            resource_args=[directory/'resources.zip']
        run(tools / 'aapt2', 'link', '-I', platform, '--manifest', manifest, '-o', unsigned,*resource_args)
        with zipfile.ZipFile(unsigned, 'a') as archive:
            for path, content in (libs or {}).items():
                archive.writestr(path, content)
        output = fixtures / (name + '.apk')
        if signed:
            aligned = directory / 'aligned.apk'
            run(tools / 'zipalign', '-f', '4', unsigned, aligned)
            run(tools / 'apksigner', 'sign', '--ks', root / (signer + '.p12'), '--ks-pass', 'pass:test-only',
                '--ks-key-alias', 'test', '--out', output, aligned)
        else:
            shutil.copyfile(unsigned, output)

    declarations = '''<activity android:name=".Main" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter></activity>
<activity-alias android:name=".Alias" android:targetActivity=".Main" android:exported="false"/>
<service android:name=".Service" android:process=":remote"/><receiver android:name=".Receiver"/>
<provider android:name=".Provider" android:authorities="dev.tripoder.set.provider"/>'''
    apk('base', app_attrs='android:process=":app"', application=declarations)
    apk('typed', app_attrs='android:maxAspectRatio="2.4"', application='''
<meta-data android:name="dimension" android:value="12dp"/>
<meta-data android:name="fraction" android:value="50%"/>
<meta-data android:name="color" android:value="#ff123456"/>''')
    apk('repeated', application='''<activity android:name=".Repeated"/><activity android:name=".Repeated"/>
<provider android:name=".SharedProvider" android:authorities="dev.tripoder.first"/>
<provider android:name=".SharedProvider" android:authorities="dev.tripoder.second"/>''')
    apk('conflicting-activity', application='''<activity android:name=".Conflict" android:exported="false" android:process=":old"><intent-filter><action android:name="dev.tripoder.FIRST"/></intent-filter></activity>
<activity android:name=".Conflict" android:exported="true" android:process=":last"><intent-filter><action android:name="dev.tripoder.LAST"/></intent-filter></activity>
<service android:name=".RepeatedService" android:enabled="false"/><service android:name=".RepeatedService" android:enabled="true"/>
<receiver android:name=".RepeatedReceiver" android:exported="false"/><receiver android:name=".RepeatedReceiver" android:exported="true"/>''')
    apk('base-split-hint', children='<uses-split android:name="video"/>')
    apk('resource-flags',application='<service android:name=".Allowed" android:enabled="@bool/allowed" android:exported="@bool/denied"/>',resources='<resources><bool name="allowed">true</bool><bool name="denied">false</bool></resources>')
    shutil.copyfile(fixtures / 'base.apk', fixtures / 'basecopy.apk')
    apk('feature', split='feature', attributes='android:isFeatureSplit="true"', application='<activity android:name=".Feature" android:exported="false"/>')
    apk('config', split='config.en', attributes='configForSplit="feature"')
    apk('wrongpackage', split='wrong', package='dev.tripoder.other')
    apk('wrongversion', split='wrong', version=2)
    apk('wrongsigner', split='wrong', signer='other')
    apk('dependent', split='dependent', attributes='android:isFeatureSplit="true"', children='<uses-split android:name="absent"/>')
    apk('cyclea', split='cyclea', attributes='android:isFeatureSplit="true"', children='<uses-split android:name="cycleb"/>')
    apk('cycleb', split='cycleb', attributes='android:isFeatureSplit="true"', children='<uses-split android:name="cyclea"/>')
    apk('required', app_attrs='android:isSplitRequired="true"')
    apk('unsigned', signed=False)
    # The existing independently compiled P0 fixture supplies an actual ARM64 ELF, not fake magic.
    shutil.copyfile(repo / 'android/runtime-probe/build/a.apk', fixtures / 'native.apk')
    with zipfile.ZipFile(fixtures / 'native.apk') as native_fixture:
        owned_elf = native_fixture.read('lib/arm64-v8a/libp0fixture.so')
    apk('mixedabi', libs={'lib/arm64-v8a/libapp.$cso.so': owned_elf,
                         'lib/armeabi-v7a/libunused.so': b'opaque unselected ABI payload'})
    apk('wrongabi', libs={'lib/x86_64/libbad.so': b'bad'})
    apk('fakeelf', libs={'lib/arm64-v8a/libbad.so': b'\x7fELF' + bytes(16)})
    apk('opaque-native-resource', libs={'lib/arm64-v8a/libpacked.so': b'owned compressed-data fixture'})
    apk('non-native-lib-resources', libs={'lib/Windows/helper.dll': b'windows resource',
                                        'lib/arm64-v8a/test.so': b'non-extracted resource',
                                        'lib/arm64-v8a/nested/libnested.so': b'nested resource'})
    apk('native-with-resources', libs={'lib/arm64-v8a/libowned.so': owned_elf,
                                     'lib/Windows/helper.dll': b'windows resource',
                                     'lib/arm64-v8a/test.so': b'non-extracted resource'})
    shutil.copyfile(fixtures / 'base.apk', fixtures / 'tampered.apk')
    with zipfile.ZipFile(fixtures / 'tampered.apk', 'a') as archive:
        archive.writestr('tampered.txt', 'changed after signing')
    with zipfile.ZipFile(fixtures / 'ziptraversal.apk', 'w') as archive:
        archive.writestr('../escape', 'unsafe')
    with zipfile.ZipFile(fixtures / 'zipsymlink.apk', 'w') as archive:
        entry = zipfile.ZipInfo('link')
        entry.create_system = 3
        entry.external_attr = 0o120777 << 16
        archive.writestr(entry, '../outside')
    with zipfile.ZipFile(fixtures / 'zipduplicate.apk', 'w') as archive:
        archive.writestr('duplicate', 'one')
        archive.writestr('duplicate', 'two')
    with zipfile.ZipFile(fixtures / 'zipbomb.apk', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        with archive.open('too-large', 'w') as output:
            for _ in range(513):
                output.write(bytes(1024 * 1024))
    classes = root / 'classes'
    classes.mkdir()
    source = repo / 'android/runtime/src/dev/tripoder/runtime'
    android_jar = platform if platform.exists() else Path(ANDROID_JAR)
    run(java / 'javac', '--release', '17', '-Xlint:all', '-classpath', str(tools / 'lib/apksigner.jar') + os.pathsep + str(android_jar), '-d', classes,
        source / 'ArtifactSet.java', source / 'ArtifactSetZip.java', source / 'ApkManifest.java', source / 'BinXmlTypedValues.java', source / 'ArscValues.java', source / 'ArtifactIntegrityCache.java',
        repo / 'android/runtime/test/dev/tripoder/runtime/ArtifactSetTest.java')
    completed = run(java / 'java', '-cp', str(classes) + os.pathsep + str(tools / 'lib/apksigner.jar') + os.pathsep + str(android_jar),
                    'dev.tripoder.runtime.ArtifactSetTest', fixtures, root / 'work', capture=True)
    print(completed.stdout, end='')
    print('Evidence directory: ' + str(root))


if __name__ == '__main__':
    main()
