#!/usr/bin/env python3
"""Build a native fixture and a host that executes that unchanged APK's scene DEX.

Neither APK includes the runtime, self-hook classes or any native library. This
is an infrastructure experiment, not a way to launch arbitrary guest packages.
"""
import argparse
import json
from pathlib import Path
import shutil
import zipfile
from runtime_apk_builder import ApkBuilder, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sdk', 'java-home', 'build-dir'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--platform', default='android-37.0')
    parser.add_argument('--build-tools', default='37.0.0')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    source = repo/'android/baseline-fixture'
    build = args.build_dir.resolve()
    builder = ApkBuilder(repo, build, args.sdk, args.platform, args.build_tools, args.java_home, repo/'android/runtime-probe/build/acceptance-only.p12')
    files = [source/'BaselineActivity.java', source/'Scene.java', Path(__file__).resolve(), repo/'android/scripts/runtime_apk_builder.py']
    before = {str(p.relative_to(repo)): sha(p) for p in files}
    artifacts = {}
    assets = build/'assets'; assets.mkdir(exist_ok=True)
    for kind in ('fixture', 'host'):
        package = 'dev.tripoder.levelzero.'+kind
        manifest = build/(kind+'-manifest.xml')
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1"><uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/><application android:label="Level 0 {kind}" android:debuggable="true" android:allowBackup="false"><activity android:name="dev.tripoder.baseline.BaselineActivity" android:exported="true"/></application></manifest>''')
        sources = [source/'BaselineActivity.java']+([source/'Scene.java'] if kind=='fixture' else [])
        apk = builder.apk('level-zero-'+kind, sources, manifest, assets=assets if kind=='host' else None)
        with zipfile.ZipFile(apk) as archive:
            if any(name.startswith('lib/') for name in archive.namelist()):
                raise RuntimeError('LEVEL_ZERO_NATIVE_LIBRARY_PRESENT')
            if any(b'dev/tripoder/selfhook' in archive.read(name) or b'dev/tripoder/runtime' in archive.read(name) for name in archive.namelist() if name.endswith('.dex')):
                raise RuntimeError('LEVEL_ZERO_RUNTIME_PRESENT')
        artifacts[kind] = {'path':str(apk), 'sha256':sha(apk), 'package':package}
        if kind=='fixture':shutil.copyfile(apk, assets/'fixture.apk')
    if before != {str(p.relative_to(repo)):sha(p) for p in files}:raise RuntimeError('SOURCES_CHANGED_DURING_BUILD')
    with zipfile.ZipFile(artifacts['host']['path']) as archive:
        import hashlib
        if hashlib.sha256(archive.read('assets/fixture.apk')).hexdigest()!=artifacts['fixture']['sha256']:raise RuntimeError('FIXTURE_CHANGED')
    receipt = {'schema':1, 'level':0, 'sourceSha256':before, 'artifacts':artifacts, 'runtimeCorePackaged':False, 'scope':'owned scene DEX with physical host Context; no guest Application/component emulation'}
    (build/'build-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt))


if __name__=='__main__':main()
