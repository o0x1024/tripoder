#!/usr/bin/env python3
"""Check Java and independent Python code-file identity against adversarial paths."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('compilation', ROOT / 'android/scripts/verify-self-art-compilation.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
apk = '/data/app/~~owned/dev.tripoder.selfhookprobe-owned/base.apk'
maps = '1000-2000 r--p 0 fe:45 123 ' + apk + '\n'
adjacent = apk.rsplit('/', 1)[0] + '/oat/arm64/base.odex'
cache = '/data/dalvik-cache/arm64/' + apk[1:].replace('/', '@') + '@classes.dex'
cases = [(adjacent, maps, True), (cache, maps, True), (cache, '', False),
         (cache.replace('~~owned', '~~old'), maps, False), (adjacent + ' (deleted)', maps, False),
         (cache.replace('selfhookprobe', 'other'), maps, False), (cache, maps.replace('123', '0'), False),
         (adjacent, maps.replace('selfhookprobe', 'other'), False), ('/tmp/dev.tripoder.selfhookprobe-x/base.odex', maps, False),
         (cache, maps.replace('r--p', '---p'), False)]
for path, text, expected in cases:
    assert module.owned_aot(path, text) is expected, (path, expected)
java = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin')
with tempfile.TemporaryDirectory(prefix='tripoder-compilation-paths-') as directory:
    temp = Path(directory)
    body = '\n'.join('if(OwnedCompilationPaths.aot(' + json.dumps(path) + ',' + json.dumps(text) + ')!='
                     + str(expected).lower() + ')throw new AssertionError("case '+str(i)+'");'
                     for i, (path, text, expected) in enumerate(cases))
    source = temp / 'CompilationPathsTest.java'
    source.write_text('package dev.tripoder.selfhookprobe; public final class CompilationPathsTest {'
                      'public static void main(String[] args){' + body + '}}')
    subprocess.run([str(java/'javac'), '-d', directory, str(source), str(ROOT / 'android/self-hook-probe/src/dev/tripoder/selfhookprobe/OwnedCompilationPaths.java')], check=True)
    subprocess.run([str(java/'java'), '-cp', directory, 'dev.tripoder.selfhookprobe.CompilationPathsTest'], check=True)
print(json.dumps({'passed': True, 'pythonCases': len(cases), 'javaCases': len(cases)}))
