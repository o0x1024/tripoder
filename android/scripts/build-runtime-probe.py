#!/usr/bin/env python3
"""Build the shared runtime acceptance app and independently signed, unmodified guest APKs.
No network, root, VirtualApp sources, device mutation, or alternative build backend.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from runtime_p1_fixtures import build_fixtures
from self_hook_native import build_native, add_platform_inputs


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path)
    add_platform_inputs(parser)
    args = parser.parse_args()
    android = Path(__file__).resolve().parents[1]
    repo = android.parent
    build = android / "runtime-probe/build"
    build.mkdir(parents=True, exist_ok=True)
    tools = args.sdk.resolve() / "build-tools" / args.build_tools
    java = args.java_home.resolve()
    jar = args.sdk.resolve() / "platforms" / args.platform / "android.jar"
    clang = args.ndk.resolve() / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android36-clang"
    required = [jar, clang, java / "bin/javac", java / "bin/keytool"]
    required += [tools / x for x in ["aapt2", "d8", "zipalign", "apksigner"]]
    for item in required:
        if not item.is_file():
            raise SystemExit(f"Required input missing: {item}")
    env = dict(os.environ, JAVA_HOME=str(java))
    env["PATH"] = str(java / "bin") + os.pathsep + env.get("PATH", "")

    def run(*command):
        subprocess.run(list(map(str, command)), check=True, cwd=repo, env=env)

    key = build / "acceptance-only.p12"
    if not key.exists():
        run(java / "bin/keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12",
            "-storepass", "tripoder-test-only", "-keypass", "tripoder-test-only", "-alias", "p0",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Tripoder P0 Test Only", "-noprompt")
    native = build / "native"
    native.mkdir(exist_ok=True)
    # Official Android apksig verification library from the explicitly selected SDK.
    apksigner_jar = tools / "lib/apksigner.jar"
    apksig = build / "apksig.jar"
    with zipfile.ZipFile(apksigner_jar) as source, zipfile.ZipFile(apksig, "w", zipfile.ZIP_DEFLATED) as target:
        for entry in source.infolist():
            if entry.filename.startswith("com/android/apksig/") and entry.filename.endswith(".class"):
                target.writestr(entry, source.read(entry))
    run(clang, "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared", "-Wl,-z,max-page-size=16384",
        android / "runtime-fixtures/native/probe.c", android / "runtime-fixtures/native/storage_probe.c", android / "runtime-fixtures/native/hook_probe.c", "-ldl", "-o", native / "libp0fixture.so")
    run(clang, "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared", "-Wl,-z,max-page-size=16384", "-Wl,--build-id=sha1",
        android / "runtime-fixtures/native/hook_target.c", "-o", native / "libtripoderownedhooks.so")
    self_hook,native_receipt=build_native(repo,native,args.ndk,args.platform_libc,args.platform_art,args.platform_linker)

    def apk(name, sources, manifest, resources=None, assets=None, libs=None, verifier=False):
        directory = build / name
        if directory.exists():
            shutil.rmtree(directory)  # Only this script's generated subdirectory.
        classes, dex = directory / "classes", directory / "dex"
        classes.mkdir(parents=True)
        dex.mkdir()
        run(java / "bin/javac", "--release", "17", "-classpath", str(jar) + (os.pathsep + str(apksig) if verifier else ""), "-d", classes, *sources)
        run(tools / "d8", "--release", "--min-api", "36", "--lib", jar, "--output", dex, *sorted(classes.rglob("*.class")), *([apksig] if verifier else []))
        unsigned = directory / "unsigned.apk"
        command = [tools / "aapt2", "link", "-I", jar, "--manifest", manifest, "-o", unsigned]
        if resources:
            compiled = directory / "resources.zip"
            run(tools / "aapt2", "compile", "--dir", resources, "-o", compiled)
            command.append(compiled)
        if assets:
            command += ["-A", assets]
        run(*command)
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
            for item in sorted(dex.glob("*.dex")):
                archive.write(item, item.name)
            for lib in libs or []:
                archive.write(lib, "lib/arm64-v8a/" + lib.name)
        aligned = directory / "aligned.apk"
        run(tools / "zipalign", "-f", "-P", "16", "4", unsigned, aligned)
        output = build / (name + ".apk")
        run(tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "p0", "--ks-pass", "pass:tripoder-test-only", "--out", output, aligned)
        verified = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(output)], env=env, check=True, capture_output=True, text=True)
        (directory / "signature.txt").write_text(verified.stdout)
        print(json.dumps({"artifact": str(output), "sha256": sha(output)}), flush=True)
        return output

    assets = build / "assets"
    assets.mkdir(exist_ok=True)
    shutil.copytree(android / "runtime-probe/assets", assets, dirs_exist_ok=True)
    fixture_manifest = {}
    for label in ["a", "b"]:
        package = "dev.tripoder.p0." + label
        fixture_dir = build / ("fixture-input-" + label)
        fixture_dir.mkdir(exist_ok=True)
        manifest = fixture_dir / "AndroidManifest.xml"
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="1">
<uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/>
<application android:name="dev.tripoder.p0.shared.ProbeApplication" android:debuggable="false" android:allowBackup="false" android:extractNativeLibs="true" android:theme="@android:style/Theme.Material.Light.NoActionBar">
<activity android:name="{package}.{label.upper()}Activity" android:exported="true"/></application></manifest>''')
        res = fixture_dir / "res"
        (res / "values").mkdir(parents=True, exist_ok=True)
        (res / "layout").mkdir(exist_ok=True)
        (res / "values/strings.xml").write_text(f'<resources><string name="title">Owned {label.upper()} resource</string></resources>')
        shutil.copyfile(android / "runtime-fixtures/res/layout/screen.xml", res / "layout/screen.xml")
        fixture_assets = fixture_dir / "assets"
        fixture_assets.mkdir(exist_ok=True)
        (fixture_assets / "owned.txt").write_text(f"owned-{label}-asset\n")
        sources = sorted((android / "runtime-fixtures/shared").rglob("*.java")) + sorted((android / "runtime-fixtures" / label).rglob("*.java"))
        output = apk(label, sources, manifest, res, fixture_assets, [native / "libp0fixture.so"])
        shutil.copyfile(output, assets / (label + ".apk"))
        fixture_manifest[label] = {"packageName": package, "sha256": sha(output), "apkRewritten": False, "signatureVerifiedBy": "apksigner", "signatureReport": (build / label / "signature.txt").read_text()}
    shutil.copytree(android / "runtime/licenses", assets / "licenses", dirs_exist_ok=True)
    (assets / "fixtures.json").write_text(json.dumps(fixture_manifest, indent=2))
    # Same self-owned A with a genuinely higher manifest version, signed by the same key.
    upgraded_manifest = build / "fixture-input-a/AndroidManifest-upgrade.xml"
    upgraded_manifest.write_text((build / "fixture-input-a/AndroidManifest.xml").read_text().replace('android:versionCode="1"', 'android:versionCode="2"'))
    upgrade = apk("a-upgrade", sorted((android / "runtime-fixtures/shared").rglob("*.java")) + sorted((android / "runtime-fixtures/a").rglob("*.java")), upgraded_manifest,
                  build / "fixture-input-a/res", build / "fixture-input-a/assets", [native / "libp0fixture.so"])
    shutil.copyfile(upgrade, assets / upgrade.name)
    p1 = build_fixtures(tools=tools, platform=jar, java_home=java, output_dir=assets, signing_key=key, native_library=native / "libp0fixture.so")
    p1["upgrade"] = {"asset": upgrade.name, "sha256": sha(upgrade)}
    (assets / "p1-fixtures.json").write_text(json.dumps(p1, indent=2))
    p2_apk = apk("p2", sorted((android / "runtime-fixtures/p2").rglob("*.java")), android / "runtime-fixtures/p2/AndroidManifest.xml", libs=[native / "libp0fixture.so",native / "libtripoderownedhooks.so"])
    shutil.copyfile(p2_apk, assets / p2_apk.name)
    p2 = {"packageName": "dev.tripoder.p2", "asset": p2_apk.name, "sha256": sha(p2_apk), "apkRewritten": False}
    p2_upgrade_manifest = build / "P2Manifest-upgrade.xml"
    p2_upgrade_manifest.write_text((android / "runtime-fixtures/p2/AndroidManifest.xml").read_text().replace('android:versionCode="1"', 'android:versionCode="2"'))
    p2_upgrade = apk("p2-upgrade", sorted((android / "runtime-fixtures/p2").rglob("*.java")), p2_upgrade_manifest, libs=[native / "libp0fixture.so",native / "libtripoderownedhooks.so"])
    shutil.copyfile(p2_upgrade, assets / p2_upgrade.name)
    p2["upgrade"] = {"asset": p2_upgrade.name, "sha256": sha(p2_upgrade)}
    peer = apk("provider-peer", sorted((android / "runtime-fixtures/provider-peer").rglob("*.java")), android / "runtime-fixtures/provider-peer/AndroidManifest.xml")
    shutil.copyfile(peer, assets / peer.name)
    p2["providerPeer"] = {"asset": peer.name, "sha256": sha(peer), "packageName": "dev.tripoder.peer"}
    provider_identity = apk("provider-identity", sorted((android / "runtime-fixtures/provider-identity").rglob("*.java")), android / "runtime-fixtures/provider-identity/AndroidManifest.xml", resources=android / "runtime-fixtures/provider-identity/res")
    shutil.copyfile(provider_identity, assets / provider_identity.name)
    p2["providerIdentity"] = {"asset": provider_identity.name, "sha256": sha(provider_identity), "packageName": "dev.tripoder.provideridentity"}
    world_manifest = build / "PackageWorldManifest.xml"
    world_source = (android / "runtime-fixtures/provider-identity/AndroidManifest.xml").read_text()
    if world_source.count('android:name=".MetadataApplication"') != 1:
        raise RuntimeError("PACKAGE_WORLD_FIXTURE_APPLICATION_DECLARATION_MISMATCH")
    world_manifest.write_text(world_source.replace('android:name=".MetadataApplication"', 'android:name=".PackageWorldApplication"'))
    world = apk("package-world", sorted((android / "runtime-fixtures/provider-identity").rglob("*.java")), world_manifest, resources=android / "runtime-fixtures/provider-identity/res")
    shutil.copyfile(world, assets / world.name)
    p2["packageWorld"] = {"asset": world.name, "sha256": sha(world), "packageName": "dev.tripoder.provideridentity"}
    creation_manifest = build / "InstrumentationManifest.xml"
    creation_manifest.write_text(world_source.replace('android:name=".MetadataApplication"', 'android:name=".InstrumentationApplication"'))
    creation = apk("instrumentation-world", sorted((android / "runtime-fixtures/provider-identity").rglob("*.java")), creation_manifest, resources=android / "runtime-fixtures/provider-identity/res")
    shutil.copyfile(creation, assets / creation.name)
    p2["instrumentationWorld"] = {"asset": creation.name, "sha256": sha(creation), "packageName": "dev.tripoder.provideridentity"}
    startup = apk("service-startup", sorted((android / "runtime-fixtures/service-startup").rglob("*.java")), android / "runtime-fixtures/service-startup/AndroidManifest.xml")
    shutil.copyfile(startup, assets / startup.name)
    p2["serviceStartup"] = {"asset": startup.name, "sha256": sha(startup), "packageName": "dev.tripoder.provideridentity"}
    passive = apk("service-passive", sorted((android / "runtime-fixtures/service-startup").rglob("*.java")), android / "runtime-fixtures/service-startup/PassiveManifest.xml")
    shutil.copyfile(passive, assets / passive.name)
    p2["servicePassive"] = {"asset": passive.name, "sha256": sha(passive), "packageName": "dev.tripoder.provideridentity"}
    jobs = apk("jobs-fixture", sorted((android / "runtime-fixtures/jobs").rglob("*.java")), android / "runtime-fixtures/jobs/AndroidManifest.xml")
    shutil.copyfile(jobs, assets / jobs.name)
    p2["jobs"] = {"asset": jobs.name, "sha256": sha(jobs), "packageName": "dev.tripoder.jobsfixture"}
    for target in (30,31):
        manifest=build/("PendingManifest-"+str(target)+".xml")
        manifest.write_text((android/"runtime-fixtures/pending/AndroidManifest.xml").read_text().replace('android:targetSdkVersion="30"','android:targetSdkVersion="'+str(target)+'"'))
        pending=apk("pending-"+str(target),sorted((android/"runtime-fixtures/pending").rglob("*.java")),manifest)
        shutil.copyfile(pending,assets/pending.name)
        p2["pending"+str(target)]={"asset":pending.name,"sha256":sha(pending),"packageName":"dev.tripoder.pendingfixture"}
    (assets / "p2-fixtures.json").write_text(json.dumps(p2, indent=2))
    preview = apk("preview-guest", sorted((android / "runtime-fixtures/preview").rglob("*.java")), android / "runtime-fixtures/preview/AndroidManifest.xml")
    shutil.copyfile(preview, assets / preview.name)
    (assets / "preview-fixture.json").write_text(json.dumps({"asset": preview.name, "sha256": sha(preview), "packageName": "dev.tripoder.previewguest"}, indent=2))
    source_roots = [android / "runtime/src", android / "control/src", android / "engine/src", android / "runtime-probe/src"]
    sources = sorted(p for root in source_roots for p in root.rglob("*.java"))
    self_hook_java = sorted((android / "self-hook/java").rglob("*.java"))
    sources.extend(self_hook_java)
    sources.append(android / "runtime/test/dev/tripoder/runtime/ProjectBindingsSourceTest.java")
    from runtime_activity_slots import generate_activity_slots
    from runtime_job_slots import generate_job_slots
    job_sources, job_manifest = generate_job_slots(build, android / "runtime-probe/AndroidManifest.xml")
    sources.extend(job_sources)
    activity_sources, runtime_manifest = generate_activity_slots(build, job_manifest, "SelfHookTaskP")
    sources.extend(activity_sources)
    output = apk("runtime-probe", sources, runtime_manifest, resources=android / "runtime-probe/res", assets=assets,
                 libs=[self_hook], verifier=True)
    with zipfile.ZipFile(output) as archive:
        if sorted(name for name in archive.namelist() if name.startswith('lib/'))!=['lib/arm64-v8a/libtripoderselfhook.so']:
            raise RuntimeError('RUNTIME_NATIVE_PACKAGING_MISMATCH')
    relevant = [repo / "src", android / "runtime/licenses", android / "runtime/src", android / "control/src", android / "engine/src", android / "runtime/native", android / "runtime-fixtures", android / "runtime-probe/src", android / "runtime-probe/res", android / "runtime-probe/assets", android / "self-hook/native", android / "self-hook/include"]
    files = sorted(p for root in relevant for p in root.rglob("*") if p.is_file() and "build" not in p.parts)
    files += [android / "runtime/test/dev/tripoder/runtime/ProjectBindingsSourceTest.java", Path(__file__).resolve(), android / "scripts/runtime_p1_fixtures.py", android / "scripts/validate-runtime-probe.py", android / "runtime-probe/AndroidManifest.xml"]
    files.append(android / "scripts/self_hook_native.py")
    files.extend(self_hook_java)
    files.append(android / "scripts/runtime_activity_slots.py")
    files.append(android / "scripts/runtime_job_slots.py")
    files.append(android / "scripts/runtime_crash_evidence.py")
    receipt = {"hostApkSha256": sha(output), "fixtures": fixture_manifest, "sdkPlatform": args.platform, "buildTools": args.build_tools, "selfHookNative":native_receipt,"fridaInputs":[],"productFridaRemoved":True,
               "ndk": (args.ndk / "source.properties").read_text(), "sourceSha256": {str(p.relative_to(repo)): sha(p) for p in files},
               "signature": (build / "runtime-probe/signature.txt").read_text(), "virtualAppCodeUsed": False,
               "p1Fixtures": p1, "p2Fixtures": p2, "apksig": {"sdkJarSha256": sha(apksigner_jar), "packagedClassesSha256": sha(apksig), "source": "Android SDK build-tools " + args.build_tools}}
    (build / "build-receipt.json").write_text(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
