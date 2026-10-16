#!/usr/bin/env python3
"""Synthetic assessment tests and read-only Android collector binary audit."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    root = ROOT / 'android/self-hook'
    fixtures = root / 'coverage-fixtures'
    build = root / 'build/coverage-host'
    build.mkdir(parents=True, exist_ok=True)
    receipt = build / 'pac-platform-report.json'
    receipt.unlink(missing_ok=True)
    bin_dir = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    uapi = bin_dir.parent / 'sysroot/usr/include'
    names = ['pac_platform_collect.c', 'pac_platform_assess.c', 'pac_platform_json.c']
    test = Path(__file__).with_name('pac_platform_test.c')
    inputs = [*[fixtures / n for n in names], fixtures / 'pac_platform.h', test, Path(__file__)]
    hashes = {str(p.relative_to(ROOT)): sha(p) for p in inputs}
    host = build / 'pac-platform-host'
    run(['clang', '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wconversion',
         '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-I', fixtures,
         fixtures / 'pac_platform_assess.c', fixtures / 'pac_platform_json.c', test, '-o', host])
    host_report = json.loads(run([host]))
    sample = host_report['sample']
    if sample['auxv']['hwcap3']['valueHex'] != '0xffffffffffffffff' or sample['fields']['apa'] is not None:
        raise RuntimeError('JSON_64_BIT_OR_UNKNOWN_SEMANTICS')
    objects = []
    for name in names:
        output = build / (name + '.o')
        run([bin_dir / 'aarch64-linux-android37-clang', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
             '-Wconversion', '-fPIC', '-mbranch-protection=standard', '-c', fixtures / name, '-o', output])
        objects.append(output)
    library = build / 'libpac-platform-observer.so'
    run([bin_dir / 'aarch64-linux-android37-clang', '-shared', '-Wl,-z,force-bti,--fatal-warnings,--no-undefined',
         *objects, '-o', library])
    disassembly = run([bin_dir / 'llvm-objdump', '-d', objects[0]])
    (build / 'pac-platform-collector.txt').write_text(disassembly)
    ids = re.findall(r'\bmrs\s+x\d+,\s+(ID_AA64ISAR[123]_EL1|ID_AA64PFR1_EL1|MIDR_EL1|S3_0_C0_C6_3)', disassembly)
    if len(ids) != 5 or re.search(r'\b(msr|pacm)\b', disassembly):
        raise RuntimeError('COLLECTOR_SYSTEM_REGISTER_AUDIT')
    allowed = {'__errno', '__stack_chk_fail', '__stack_chk_guard', 'getauxval', 'getpid', 'gettid',
               'sched_getcpu', 'clock_gettime', 'uname', 'prctl', 'memset', 'memcpy', 'strnlen', '__memcpy_chk'}
    undefined = {line.split()[-1] for line in run([bin_dir / 'llvm-nm', '--undefined-only', objects[0]]).splitlines() if line.split()}
    if undefined - allowed:
        raise RuntimeError('COLLECTOR_UNEXPECTED_IMPORTS:' + repr(undefined - allowed))
    if hashes != {str(p.relative_to(ROOT)): sha(p) for p in inputs}:
        raise RuntimeError('PAC_PLATFORM_SOURCES_CHANGED')
    report = {'host': host_report, 'sourceHashes': hashes,
              'uapiHashes': {name: sha(uapi / name) for name in ('linux/auxvec.h', 'linux/prctl.h', 'aarch64-linux-android/asm/hwcap.h')},
              'mrsRegisters': ids, 'collectorImports': sorted(undefined), 'nativeLibrarySha256': sha(library),
              'deviceExecuted': False, 'signalHandlersInstalled': False, 'classicProfileAdmitted': False,
              'scope': 'Synthetic 52-check assessment and Android compile/import/MRS audit; no device evidence or vendor-kernel contract'}
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'hostChecks': host_report['checks'], 'mrsRegisters': ids,
                      'nativeLibrarySha256': report['nativeLibrarySha256'], 'deviceExecuted': False,
                      'classicProfileAdmitted': False, 'report': str(receipt)}))


if __name__ == '__main__':
    main()
