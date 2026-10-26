#!/usr/bin/env python3
"""Generate private local test material, run real JVM mTLS, build the owned guest APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'android/scripts'))
from runtime_apk_builder import ApkBuilder


def run(*args):
    return subprocess.run(list(map(str, args)), check=True, capture_output=True, text=True).stdout


def material(directory):
    for ca in ('ca', 'other-ca'):
        run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
            '-subj', '/CN=owned-' + ca, '-keyout', directory / (ca + '.key'),
            '-out', directory / (ca + '.crt'), '-addext', 'basicConstraints=critical,CA:TRUE',
            '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
    for identity in ('server', 'client'):
        run('openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=owned-' + identity,
            '-keyout', directory / (identity + '.key'), '-out', directory / (identity + '.csr'))
        ext = directory / 'extensions.txt'
        ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=' +
                       ('serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n' if identity == 'server' else 'clientAuth\n'))
        run('openssl', 'x509', '-req', '-in', directory / (identity + '.csr'), '-CA', directory / 'ca.crt',
            '-CAkey', directory / 'ca.key', '-CAcreateserial', '-days', '2', '-extfile', ext,
            '-out', directory / (identity + '.crt'))
        run('openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', directory / (identity + '.key'),
            '-outform', 'DER', '-out', directory / (identity + '.der'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    os.umask(0o077)
    source = REPO / 'android/compatibility-test/mtls-fixture'
    build = REPO / 'android/compatibility-test/build/mtls-fixture'
    build.mkdir(parents=True, exist_ok=True)
    java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home')
    with tempfile.TemporaryDirectory(prefix='private-mtls-', dir=build) as temporary:
        private = Path(temporary)
        material(private)
        classes = private / 'classes'
        classes.mkdir()
        core = [source / (name + '.java') for name in ('TlsMaterial', 'OwnedHttp', 'MtlsChecks', 'HostCheck')]
        run(java / 'bin/javac', '--release', '17', '-d', classes, *core)
        result = json.loads(run(java / 'bin/java', '-cp', classes, 'dev.tripoder.mtlsfixture.HostCheck', private))
        (args.evidence / 'host-result.json').write_text(json.dumps(result, indent=2) + '\n')
        assets = private / 'assets'
        assets.mkdir()
        for name in ('ca.crt', 'other-ca.crt', 'server.crt', 'server.der', 'client.crt', 'client.der'):
            (assets / name).write_bytes((private / name).read_bytes())
        builder = ApkBuilder(REPO, build, Path('/opt/homebrew/share/android-commandlinetools'),
                             'android-37.0', '37.0.0', java, REPO / 'android/runtime-probe/build/acceptance-only.p12')
        apk = builder.apk('mtls-fixture', sorted(p for p in source.glob('*.java') if p.name != 'HostCheck.java'),
                          source / 'AndroidManifest.xml', assets=assets)
        inputs = [*sorted(source.glob('*')), Path(__file__)]
        receipt = {'apk': str(apk), 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                   'testCertificatesValidDays': 2, 'testPrivateKeysOnlyInIgnoredBuildApk': True,
                   'sourceSha256': {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}}
        (args.evidence / 'build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
