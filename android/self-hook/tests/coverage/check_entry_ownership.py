#!/usr/bin/env python3
"""ASan/UBSan routing tests and independent LLVM checks of pinned branch evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import entry_writer_audit as audit

ROOT = Path(__file__).resolve().parents[4]


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--art', type=Path, required=True)
    parser.add_argument('--debug', type=Path, required=True)
    args = parser.parse_args()
    root = ROOT / 'android/self-hook'
    build = root / 'build/coverage-host'
    build.mkdir(parents=True, exist_ok=True)
    receipt = build / 'entry-ownership-report.json'
    receipt.unlink(missing_ok=True)
    compiler_bin = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    native = root / 'native'
    sources = [native / 'coverage/owned_entry_registry.c', native / 'a64_entry.c', native / 'a64_relocate.c',
               Path(__file__).with_name('owned_entry_registry_test.c')]
    inputs = [*sources, native / 'coverage/owned_entry_registry.h', root / 'include/tripoder_a64.h',
              root / 'include/tripoder_hook_limits.h',
              native / 'a64_internal.h', Path(__file__), Path(audit.__file__)]
    hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    binary = build / 'entry-owner-host'
    run(['clang', '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wconversion', '-pthread',
         '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-I', native / 'coverage',
         '-I', root / 'include', *sources, '-o', binary])
    registry = json.loads(run([binary]))
    # Compile the standalone routing component with the same pinned device target.
    run([compiler_bin / 'aarch64-linux-android37-clang', '-std=c11', '-Wall', '-Wextra', '-Werror',
         '-Wconversion', '-fPIC', '-mbranch-protection=standard', '-c', sources[0],
         '-o', build / 'owned_entry_registry.o'])
    report = audit.audit(args.art, args.debug, compiler_bin / 'llvm-nm')
    oracle_checks = 0
    for site in report['directBranchCandidates']:
        pc = int(site['rva'], 16)
        output = run([compiler_bin / 'llvm-objdump', '-d', f'--start-address={pc}',
                      f'--stop-address={pc + 4}', args.art])
        instruction = re.search(r'^\s*' + f'{pc:x}' + r':\s+[0-9a-f]+\s+(b|bl)\s+(0x[0-9a-f]+)', output, re.M)
        if not instruction or instruction[1].upper() != site['instruction'] or int(instruction[2], 16) != int(site['destination'], 16):
            raise RuntimeError('LLVM_BRANCH_ORACLE_DISAGREEMENT:' + site['rva'])
        oracle_checks += 1
    for function in report['functions']:
        pc = int(function['rva'], 16)
        output = run([compiler_bin / 'llvm-objdump', '-d', f'--start-address={pc}',
                      f'--stop-address={pc + 4}', args.art])
        if function['pacPrologue'] != 'PACIASP' or not re.search(r'\bpaciasp\b', output):
            raise RuntimeError('PINNED_PAC_PROLOGUE_CHANGED:' + function['name'])
        oracle_checks += 1
    for writer in report['directWriterCounterexamples']:
        pc = int(writer['writeRva'], 16)
        output = run([compiler_bin / 'llvm-objdump', '-d', f'--start-address={pc}',
                      f'--stop-address={pc + 4}', args.art])
        if not re.search(r'\bstr\s+x(?:8|10),', output):
            raise RuntimeError('DIRECT_WRITER_ORACLE_CHANGED:' + writer['name'])
        oracle_checks += 1
    if hashes != {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}:
        raise RuntimeError('ENTRY_OWNERSHIP_SOURCES_CHANGED')
    result = {'registry': registry, 'llvmOracleChecks': oracle_checks,
              'sourceHashes': hashes, 'artSha256': report['artSha256'],
              'routingObjectSha256': hashlib.sha256((build / 'owned_entry_registry.o').read_bytes()).hexdigest(),
              'installedInterposition': False, 'completeEntryWriterClosure': False,
              'scope': 'Pure bounded policy routing and fixed ELF oracle; this test does not install an ART writer'}
    receipt.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
