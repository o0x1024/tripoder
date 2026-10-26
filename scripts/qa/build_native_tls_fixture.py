#!/usr/bin/env python3
"""Build a loopback-only native OpenSSL fixture from an explicit verified source build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from build_mtls_fixture import material, run, REPO
sys.path.insert(0, str(REPO / 'android/scripts'))
from runtime_apk_builder import ApkBuilder

parser = argparse.ArgumentParser()
parser.add_argument('--openssl', required=True, type=Path)
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
os.umask(0o077)
args.evidence.mkdir(parents=True, exist_ok=False)
source = REPO / 'android/compatibility-test/native-tls-fixture'
build = REPO / 'android/compatibility-test/build/native-tls-fixture'
build.mkdir(parents=True, exist_ok=True)
ssl = args.openssl.resolve()
archive = ssl.parent / 'openssl-3.5.0.tar.gz'
expected = '344d0a79f1a9b08029b0744e2cc401a43f9c90acd1044d09a530b4885a8e9fc0'
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise RuntimeError('OPENSSL_SOURCE_DIGEST_MISMATCH')
ndk = Path('/opt/homebrew/share/android-commandlinetools/ndk/30.0.16248370')
toolchain = ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
library = build / 'libownednativetls.so'
exports = build / 'exports.map'
exports.write_text('{global: Java_dev_tripoder_nativetlsfixture_NativeClient_exchange; Java_dev_tripoder_nativetlsfixture_NativeClient_probe; owned_buffer_case; SSL_read; SSL_write; SSL_new; SSL_free; local: *;};\n')
run(toolchain / 'aarch64-linux-android36-clang', '-shared', '-fPIC', '-O1', '-g',
    '-Wall', '-Wextra', '-Werror', '-I' + str(ssl / 'include'), source / 'client.c',
    ssl / 'libssl.a', ssl / 'libcrypto.a', '-ldl', '-Wl,-Bsymbolic-functions',
    '-Wl,--build-id=sha1,-z,max-page-size=16384,--version-script=' + str(exports), '-o', library)
with tempfile.TemporaryDirectory(prefix='private-native-mtls-', dir=build) as tmp:
    private = Path(tmp)
    material(private)
    assets = private / 'assets'
    assets.mkdir()
    for name in ('ca.crt', 'other-ca.crt', 'server.crt', 'server.der', 'client.crt', 'client.der'):
        (assets / name).write_bytes((private / name).read_bytes())
    builder = ApkBuilder(REPO, build, Path('/opt/homebrew/share/android-commandlinetools'), 'android-37.0', '37.0.0',
                         Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'), REPO / 'android/runtime-probe/build/acceptance-only.p12')
    common = REPO / 'android/compatibility-test/mtls-fixture/TlsMaterial.java'
    apk = builder.apk('native-tls-fixture', [*sorted(source.glob('*.java')), common], source / 'AndroidManifest.xml', assets=assets, libraries=[library])
    files = [*sorted(source.glob('*')), common, Path(__file__), REPO / 'scripts/qa/build_mtls_fixture.py']
    receipt = {'apk': str(apk), 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
               'librarySha256': hashlib.sha256(library.read_bytes()).hexdigest(), 'opensslTestOnlyVersion': '3.5.0',
               'opensslArchiveSha256': expected, 'opensslConfig': run('perl', ssl / 'configdata.pm', '--dump'),
               'sourceSha256': {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
    (args.evidence / 'build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
