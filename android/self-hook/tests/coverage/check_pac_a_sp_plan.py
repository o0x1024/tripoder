#!/usr/bin/env python3
"""Independent PAC planner, LLVM encoding and owned ELF unwind checks; no device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
from entry_writer_audit import Elf, FUNCTIONS

ROOT = Path(__file__).resolve().parents[4]


def run(command, source=None):
    result = subprocess.run(list(map(str, command)), input=source, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def need(condition, label):
    if not condition:
        raise RuntimeError('PAC_ORACLE:' + label)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    root = ROOT / 'android/self-hook'
    native, fixtures = root / 'native', root / 'coverage-fixtures'
    build = root / 'build/coverage-host'
    build.mkdir(parents=True, exist_ok=True)
    receipt = build / 'pac-plan-report.json'
    receipt.unlink(missing_ok=True)
    tests = Path(__file__).parent
    inputs = [native / 'coverage/pac_a_sp_plan.c', native / 'coverage/pac_a_sp_plan.h',
              native / 'a64_relocate.c', native / 'a64_internal.h', root / 'include/tripoder_a64.h',
              tests / 'pac_a_sp_plan_test.c', tests / 'entry_writer_audit.py', Path(__file__),
              fixtures / 'pac_owned_fixture.c', fixtures / 'pac_owned_fixture.S', fixtures / 'pac_owned_fixture.h']
    hashes = {str(p.relative_to(ROOT)): digest(p) for p in inputs}
    host = build / 'pac-plan-host'
    run(['clang', '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wconversion',
         '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-I', root / 'include',
         '-I', native / 'coverage', native / 'coverage/pac_a_sp_plan.c', native / 'a64_relocate.c',
         tests / 'pac_a_sp_plan_test.c', '-o', host])
    host_report = json.loads(run([host]))
    tools = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    cc = tools / 'aarch64-linux-android37-clang'
    oracle_checks = 0
    art_path = ROOT / 'android/self-hook-probe/build/device-libart.so'
    platform_path = ROOT / 'android/self-hook-probe/platform.json'
    art_sha = json.loads(platform_path.read_text())['binaries']['/apex/com.android.art/lib64/libart.so']
    need(digest(art_path) == art_sha, 'fixed ART image identity')
    art = Elf(art_path.read_bytes())
    art_prefixes = []
    for name, (address, _) in FUNCTIONS.items():
        prefix = struct.unpack('<2I', art.code(address, 8))
        status = json.loads(run([host, f'{prefix[0]:x}', f'{prefix[1]:x}']))
        need(status['status'] == 0, 'fixed ART prefix ' + name)
        art_prefixes.append({'name': name, 'rva': hex(address), 'prefix': [hex(w) for w in prefix],
                             'offlineClassicProfileAdmission': True, 'installed': False})
    for i, frame in enumerate(('sub sp,sp,#32', 'stp x29,x30,[sp,#-32]!')):
        source = f'''.text
.org 0x100000
target:
paciasp
b relay
.org 0x200000
relay:
bti c
autiasp
ldr x16,literal
br x16
literal:
.xword 0x9000000000
.org 0x201000
trampoline:
bti c
paciasp
{frame}
b target+8
'''
        output = build / f'pac-plan-oracle-{i}.o'
        run([cc, '-x', 'assembler', '-c', '-o', output, '-'], source)
        elf = Elf(output.read_bytes())
        sample = host_report['samples'][i]
        for address, wanted, name in ((0x100004, [sample['patchWord']], 'patch'),
                                      (0x200000, sample['relay'], 'relay'),
                                      (0x201000, sample['trampoline'], 'original trampoline')):
            need(elf.code(address, len(wanted) * 4) == struct.pack('<' + 'I' * len(wanted), *wanted), name)
            oracle_checks += 1
    isa = build / 'pac-isa.o'
    run([cc, '-x', 'assembler', '-march=armv9.5-a+pauth-lr', '-c', '-o', isa, '-'],
        '.text\npaciasp\nautiasp\npacibsp\nautibsp\npaciasppc\nautiasppc #0\n')
    need(Elf(isa.read_bytes()).section('.text') == struct.pack('<6I',
        0xd503233f, 0xd50323bf, 0xd503237f, 0xd50323ff, 0xdac1a3fe, 0xf380001f), 'ISA forms')
    oracle_checks += 6
    common = [cc, '-O2', '-fPIC', '-fasynchronous-unwind-tables', '-mbranch-protection=standard',
              '-Wall', '-Wextra', '-Werror', '-Wconversion']
    run(common + ['-I', root / 'include', '-c', native / 'coverage/pac_a_sp_plan.c',
                  '-o', build / 'pac_a_sp_plan.o'])
    for extension, suffix in (('c', 'c'), ('S', 's')):
        run(common + ['-c', fixtures / f'pac_owned_fixture.{extension}', '-o', build / f'pac_owned_fixture_{suffix}.o'])
    library = build / 'libpac-owned-fixture.so'
    run([cc, '-shared', '-Wl,-z,force-bti,--fatal-warnings,--no-undefined',
         build / 'pac_owned_fixture_c.o', build / 'pac_owned_fixture_s.o', '-o', library])
    symbols = {}
    for line in run([tools / 'llvm-nm', '-SC', library]).splitlines():
        match = re.fullmatch(r'([0-9a-f]+) ([0-9a-f]+) [tT] (tp_pac_fixture_\w+)', line)
        if match:
            symbols[match[3]] = (int(match[1], 16), int(match[2], 16))
    elf = Elf(library.read_bytes())
    for suffix in ('stp', 'sub'):
        for prefix in ('replace', 'routed', 'baseline'):
            address, _ = symbols[f'tp_pac_fixture_{prefix}_{suffix}']
            need(elf.code(address, 4) == struct.pack('<I', 0xd503233f), prefix + ' PAC retained')
            oracle_checks += 1
    unwind = run([tools / 'llvm-dwarfdump', '--eh-frame', library])
    (build / 'pac-fixture-unwind.txt').write_text(unwind)
    fdes = {}
    for match in re.finditer(r'FDE cie=.*? pc=([0-9a-f]+)\.\.\.([0-9a-f]+)\n(.*?)(?=\n[0-9a-f]{8} |\Z)', unwind, re.S):
        rows = {int(row[1], 16): row[2] for row in re.finditer(r'^\s+0x([0-9a-f]+): (.+)$', match[3], re.M)}
        fdes[int(match[1], 16)] = rows
    unwind_rows = {}
    for suffix in ('stp', 'sub'):
        relay = symbols[f'tp_pac_fixture_relay_{suffix}'][0]
        rows = fdes[relay]
        need('reg34=1' in rows[relay] and 'CFA=WSP' in rows[relay], 'relay starts with signed LR')
        need('reg34=0' in rows[relay + 8] and 'CFA=WSP' in rows[relay + 8], 'relay authenticates before tail dispatch')
        original = symbols[f'tp_pac_fixture_original_{suffix}'][0]
        original_rows = fdes[original]
        need('reg34=1' not in original_rows[original], 'trampoline enters with unsigned LR')
        need('reg34=1' in original_rows[original + 8], 'trampoline signs after PACIASP')
        need('CFA=WSP+32' in original_rows[original + 12] and 'reg34=1' in original_rows[original + 12], 'trampoline frame CFA')
        if suffix == 'stp':
            need('W30=[CFA-24]' in original_rows[original + 12] and 'W29=[CFA-32]' in original_rows[original + 12], 'saved FP/LR exact slots')
        else:
            need('W30=[' not in original_rows[original + 12], 'SUB leaves signed LR in register')
        oracle_checks += 6
        unwind_rows[suffix] = {'relay': rows, 'trampoline': original_rows}
    notes = run([tools / 'llvm-readelf', '-n', library])
    dynamic = run([tools / 'llvm-readelf', '-d', library])
    need('BTI' in notes and 'TEXTREL' not in dynamic, 'BTI fixture without text relocations')
    oracle_checks += 1
    if hashes != {str(p.relative_to(ROOT)): digest(p) for p in inputs}:
        raise RuntimeError('PAC_SOURCES_CHANGED')
    report = {'host': host_report, 'llvmOracleChecks': oracle_checks, 'sourceHashes': hashes,
              'artSha256': art_sha, 'fixedArtPrefixChecks': len(art_prefixes), 'fixedArtPrefixes': art_prefixes,
              'fixtureSha256': digest(library), 'plannerObjectSha256': digest(build / 'pac_a_sp_plan.o'),
              'unwindRows': unwind_rows, 'deviceExecuted': False, 'installedArtInterposition': False,
              'scope': 'Classic A-key/SP offline planner and prelinked owned fixture only; runtime profile, generated FDE publication, and writer closure unproven'}
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'hostChecks': host_report['checks'], 'llvmOracleChecks': oracle_checks,
                      'fixedArtPrefixChecks': len(art_prefixes),
                      'fixtureSha256': report['fixtureSha256'], 'deviceExecuted': False,
                      'report': str(receipt)}))


if __name__ == '__main__':
    main()
