"""Explicit Android SDK build primitives for the workbench and engine development APKs."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


class ApkBuilder:
    def __init__(self, repo, build, sdk, platform, build_tools, java_home, key):
        self.repo, self.build = Path(repo).resolve(), Path(build).resolve()
        self.tools, self.java = Path(sdk).resolve() / 'build-tools' / build_tools, Path(java_home).resolve()
        self.platform = Path(sdk).resolve() / 'platforms' / platform / 'android.jar'
        self.key = Path(key).resolve()
        self.env = dict(os.environ, JAVA_HOME=str(self.java))
        self.env['PATH'] = str(self.java / 'bin') + os.pathsep + self.env.get('PATH', '')
        for item in [self.platform, self.key, self.java / 'bin/javac', self.java / 'bin/keytool',
                     *[self.tools / name for name in ('aapt2', 'd8', 'zipalign', 'apksigner')]]:
            if not item.is_file():
                raise RuntimeError('REQUIRED_BUILD_INPUT_MISSING:' + str(item))
        self.build.mkdir(parents=True, exist_ok=True)
        cert = self.build / 'controller-certificate.der'
        self.run(self.java / 'bin/keytool', '-exportcert', '-keystore', self.key,
                 '-storepass', 'tripoder-test-only', '-alias', 'p0', '-file', cert)
        self.cert_sha256 = sha(cert)

    def run(self, *args):
        subprocess.run([str(a) for a in args], cwd=self.repo, env=self.env, check=True)

    def apk(self, name, sources, manifest, resources=None, assets=None, libraries=(), jars=()):
        directory = self.build / name
        if directory.exists():
            shutil.rmtree(directory)  # This builder's generated per-APK directory only.
        classes, dex = directory / 'classes', directory / 'dex'
        classes.mkdir(parents=True)
        dex.mkdir()
        classpath = os.pathsep.join(map(str, [self.platform, *jars]))
        self.run(self.java / 'bin/javac', '--release', '17', '-classpath', classpath,
                 '-d', classes, *sources)
        # A generated component pool can exceed macOS ARG_MAX when an isolated
        # output path is long. D8 consumes the same class bytes in one archive.
        compiled_classes = directory / 'compiled-classes.jar'
        with zipfile.ZipFile(compiled_classes, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(classes.rglob('*.class')):
                archive.write(path, path.relative_to(classes).as_posix())
        self.run(self.tools / 'd8', '--release', '--min-api', '36', '--lib', self.platform,
                 '--output', dex, compiled_classes, *jars)
        unsigned = directory / 'unsigned.apk'
        args = [self.tools / 'aapt2', 'link', '-I', self.platform, '--manifest', manifest, '-o', unsigned]
        if resources:
            compiled = directory / 'resources.zip'
            self.run(self.tools / 'aapt2', 'compile', '--dir', resources, '-o', compiled)
            args.append(compiled)
        if assets:
            args += ['-A', assets]
        self.run(*args)
        with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(dex.glob('*.dex')):
                archive.write(path, path.name)
            for path in libraries:
                archive.write(path, 'lib/arm64-v8a/' + path.name)
        aligned, result = directory / 'aligned.apk', self.build / (name + '.apk')
        self.run(self.tools / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
        self.run(self.tools / 'apksigner', 'sign', '--ks', self.key, '--ks-key-alias', 'p0',
                 '--ks-pass', 'pass:tripoder-test-only', '--out', result, aligned)
        signature = subprocess.run([str(self.tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(result)],
                                   env=self.env, check=True, capture_output=True, text=True).stdout
        (directory / 'signature.txt').write_text(signature)
        print(json.dumps({'artifact': str(result), 'sha256': sha(result), 'developmentSigningOnly': True}), flush=True)
        return result

    def apksig(self):
        result = self.build / 'apksig.jar'
        with zipfile.ZipFile(self.tools / 'lib/apksigner.jar') as source, zipfile.ZipFile(result, 'w', zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                if item.filename.startswith('com/android/apksig/') and item.filename.endswith('.class'):
                    target.writestr(item, source.read(item))
        return result
