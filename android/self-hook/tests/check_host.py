#!/usr/bin/env python3
"""Sanitizers plus an independent LLVM assembler oracle; never executes A64 on host."""
import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / 'android/self-hook'


def run(*args):
    return subprocess.run(list(map(str, args)), check=True, capture_output=True, text=True).stdout


def hashes(paths):
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--toolchain', type=Path, required=True)
    parser.add_argument('--host-clang', default='/usr/bin/clang')
    args = parser.parse_args()
    build = CORE / 'build/host'
    build.mkdir(parents=True, exist_ok=True)
    report_path = build / 'host-report.json'
    report_path.unlink(missing_ok=True)  # A failed rerun must not leave a stale completion receipt.
    run_id = str(uuid.uuid4())
    flags = ['-std=c11', '-Wall', '-Wextra', '-Werror', '-O1', '-g',
             '-I' + str(CORE / 'include'), '-I' + str(CORE / 'tests')]
    native = [CORE / 'native/a64_relocate.c', CORE / 'native/a64_entry.c']
    inputs = [*native, CORE / 'include/tripoder_a64.h', CORE / 'native/a64_internal.h',
              CORE / 'tests/core_checks.c', CORE / 'tests/core_checks.h',
              CORE / 'tests/host_main.c', Path(__file__).resolve()]
    source_hashes = hashes(inputs)
    executable = build / 'core-checks'
    run(args.host_clang, *flags, '-fsanitize=address,undefined', *native,
        CORE / 'tests/core_checks.c', CORE / 'tests/host_main.c', '-o', executable)
    checks = json.loads(run(executable))
    library = build / 'libplanner.dylib'
    run(args.host_clang, *flags, '-dynamiclib', *native, '-o', library)
    planner = ctypes.CDLL(str(library))
    word = ctypes.c_uint32
    planner.tp_a64_relocate.argtypes = [ctypes.POINTER(word), ctypes.c_size_t,
                                      ctypes.c_uint64, ctypes.c_uint64, ctypes.POINTER(word)]
    planner.tp_a64_relocate.restype = ctypes.c_int
    planner.tp_a64_branch.argtypes = [ctypes.c_uint64, ctypes.c_uint64, ctypes.POINTER(word)]
    planner.tp_a64_branch.restype = ctypes.c_int
    # Numeric local displacements produce fully resolved .text, independent of our encoder.
    pairs = []
    for template in ('b {d}', 'b.eq {d}', 'b.ne {d}', 'b.lt {d}',
                     'cbz x7, {d}', 'cbnz w9, {d}', 'tbz x11, #45, {d}',
                     'tbnz w12, #15, {d}', 'adr x3, {d}', 'ldr w1, {d}',
                     'ldr x2, {d}', 'ldrsw x4, {d}'):
        for offset in (128, -64):
            pairs.append((template.format(d='.' + f'{offset:+d}'),
                          template.format(d='.' + f'{offset - 4096:+d}')))
    pairs += [('adrp x4, 0x2000', 'adrp x4, 0x1000'),
              ('adrp x5, -0x1000', 'adrp x5, -0x2000')]
    for instruction in ('bti c', 'bti jc', 'nop', 'add x0, x0, #1',
                        'sub sp, sp, #32', 'adds w1, w2, #4095', 'sub x3, x4, #3, lsl #12'):
        pairs.append((instruction, instruction))
    branch_offsets = (0, 4, -4, 134217724, -134217728)
    instructions = [item for pair in pairs for item in pair]
    instructions += ['b .' + f'{offset:+d}' for offset in branch_offsets]
    assembly = build / 'oracle.S'
    assembly.write_text('.text\n.p2align 2\n' + '\n'.join(instructions) + '\n')
    compiler = args.toolchain / 'aarch64-linux-android36-clang'
    obj, binary = build / 'oracle.o', build / 'oracle.bin'
    run(compiler, '-c', assembly, '-o', obj)
    relocations = run(args.toolchain / 'llvm-readelf', '-r', obj)
    if 'There are no relocations' not in relocations:
        raise RuntimeError('ORACLE_HAS_UNRESOLVED_RELOCATIONS:' + relocations)
    run(args.toolchain / 'llvm-objcopy', '-O', 'binary', '--only-section=.text', obj, binary)
    raw = binary.read_bytes()
    if len(raw) != len(instructions) * 4:
        raise RuntimeError('ORACLE_SIZE_MISMATCH')
    words = struct.unpack('<' + 'I' * len(instructions), raw)
    results = []
    for index, (source, expected) in enumerate(pairs):
        before, after = word(words[index * 2]), word()
        status = planner.tp_a64_relocate(ctypes.byref(before), 1, 0x100000, 0x101000, ctypes.byref(after))
        if status != 0 or after.value != words[index * 2 + 1]:
            raise AssertionError((source, expected, status, hex(after.value), hex(words[index * 2 + 1])))
        results.append({'source': source, 'expected': expected, 'word': f'{after.value:08x}'})
    for index, offset in enumerate(branch_offsets):
        output = word()
        status = planner.tp_a64_branch(0x10000000, 0x10000000 + offset, ctypes.byref(output))
        if status != 0 or output.value != words[len(pairs) * 2 + index]:
            raise AssertionError(('branch', offset, status, hex(output.value)))
        results.append({'branchOffset': offset, 'word': f'{output.value:08x}'})
    if source_hashes != hashes(inputs):
        raise RuntimeError('SOURCES_CHANGED_DURING_CHECK')
    report = {'schema': 1, 'runId': run_id, 'timestamp': datetime.now(timezone.utc).isoformat(),
              'sourceHashes': source_hashes, 'core': checks, 'oracleChecks': len(results), 'oracle': results,
              'sanitizers': ['address', 'undefined'], 'hostCompiler': run(args.host_clang, '--version'),
              'assembler': run(compiler, '--version'),
              'oracleSha256': hashlib.sha256(raw).hexdigest(), 'liveCodePatched': False}
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'core': checks, 'oracleChecks': len(results), 'report': str(build / 'host-report.json')}))


if __name__ == '__main__':
    main()
