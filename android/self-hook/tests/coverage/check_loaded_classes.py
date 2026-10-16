#!/usr/bin/env python3
"""Mock roots/Java receipts and independent fixed-ABI compiler checks; no device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import loaded_class_contract

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
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--art', type=Path, required=True)
    args = parser.parse_args()
    root = ROOT / 'android/self-hook'
    native, tests = root / 'native/coverage', Path(__file__).parent
    build = root / 'build/coverage-host'
    build.mkdir(parents=True, exist_ok=True)
    receipt = build / 'loaded-class-report.json'
    receipt.unlink(missing_ok=True)
    header, contract = loaded_class_contract.generate(args.art)
    if header != (native / 'loaded_class_contract.h').read_text():
        raise RuntimeError('LOADED_CONTRACT_CHANGED')
    java_source = root / 'java/dev/tripoder/selfhook/coverage/LoadedClassInventory.java'
    inputs = [native / 'loaded_class_inventory.cpp', native / 'loaded_class_buffer.hpp',
              native / 'loaded_class_contract.h', native.parent / 'art_access.h',
              native.parent / 'art_framework_contract.h', java_source,
              root / 'java/dev/tripoder/selfhook/dex/AbiSignature.java',
              tests / 'loaded_class_buffer_test.cpp', tests / 'LoadedClassInventoryHostTest.java',
              tests / 'loaded_class_contract.py', tests / 'entry_writer_audit.py', Path(__file__)]
    hashes = {str(p.relative_to(ROOT)): sha(p) for p in inputs}
    host = build / 'loaded-class-host'
    run(['clang++', '-std=c++20', '-Wall', '-Wextra', '-Werror', '-Wconversion',
         '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-fno-rtti', '-fno-exceptions',
         '-fno-sized-deallocation', '-I', native, tests / 'loaded_class_buffer_test.cpp', '-o', host])
    mock = json.loads(run([host]))
    java_output = build / 'loaded-java'
    java_output.mkdir(exist_ok=True)
    run([args.java_home / 'bin/javac', '--release', '17', '-cp', args.sdk / 'platforms/android-37.0/android.jar',
         '-d', java_output, root / 'java/dev/tripoder/selfhook/dex/AbiSignature.java', java_source,
         tests / 'LoadedClassInventoryHostTest.java'])
    java_test = json.loads(run([args.java_home / 'bin/java', '-cp', java_output,
                               'dev.tripoder.selfhook.coverage.LoadedClassInventoryHostTest']))
    tools = args.ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    flags = [tools / 'aarch64-linux-android37-clang++', '-std=c++20', '-O2', '-fPIC', '-fno-exceptions',
             '-fno-rtti', '-fno-sized-deallocation', '-mbranch-protection=standard', '-Wall', '-Wextra', '-Werror', '-Wconversion']
    collector = build / 'loaded_class_inventory.o'
    abi_object = build / 'loaded_class_abi.o'
    run(flags + ['-c', native / 'loaded_class_inventory.cpp', '-o', collector])
    run(flags + ['-I', native, '-c', tests / 'loaded_class_buffer_test.cpp', '-o', abi_object])
    disassembly = run([tools / 'llvm-objdump', '-d', '--disassemble-symbols=tp_loaded_visit_abi', abi_object])
    if not re.search(r'ldr\s+x\d+,\s*\[x0\]', disassembly) or not re.search(r'ldr\s+x16,\s*\[x\d+, #0x10\]', disassembly):
        raise RuntimeError('CLASS_VISITOR_VTABLE_SLOT_NOT_TWO')
    if not re.search(r'br\s+x16', disassembly) or re.search(r'\b(?:mov|ldr|str)\s+x1(?:,|\b)', disassembly):
        raise RuntimeError('CLASS_VISITOR_OBJPTR_ARGUMENT_ABI')
    (build / 'loaded-class-abi.txt').write_text(disassembly)
    imports = {line.split()[-1] for line in run([tools / 'llvm-nm', '--undefined-only', collector]).splitlines() if line.split()}
    allowed = {'__aarch64_cas4_acq', '__aarch64_swp1_acq_rel', 'abort', 'android_set_abort_message',
               'calloc', 'memset', 'tp_art_access_bind', 'tp_art_method_snapshot', 'tp_art_readable', 'tp_art_validate_code'}
    if imports - allowed:
        raise RuntimeError('LOADED_COLLECTOR_UNEXPECTED_RUNTIME:' + repr(imports - allowed))
    symbols = run([tools / 'llvm-nm', collector])
    visitor = next((line.split()[-1] for line in symbols.splitlines() if 'BoundedVisitorcl' in line), None)
    if visitor is None:
        raise RuntimeError('CLASS_VISITOR_BODY_MISSING')
    body = run([tools / 'llvm-objdump', '-d', '--disassemble-symbols=' + visitor, collector])
    if re.search(r'\bbl(?:r)?\s', body):
        raise RuntimeError('CLASS_CALLBACK_HAS_CALLS')
    (build / 'loaded-class-callback.txt').write_text(body)
    if hashes != {str(p.relative_to(ROOT)): sha(p) for p in inputs}:
        raise RuntimeError('LOADED_CLASS_SOURCES_CHANGED')
    report = {'mockRootTests': mock, 'javaReceiptTests': java_test, 'fixedArtContract': contract,
              'sourceHashes': hashes, 'collectorObjectSha256': sha(collector), 'imports': sorted(imports),
              'visitorVtableSlot': 2, 'callbackCalls': 0, 'deviceExecuted': False,
              'actualArtGcVerified': False, 'futureClassPublicationCovered': False,
              'scope': 'Compile/mock evidence only; real ClassLinker enumeration, moving roots and CheckJNI await own-device acceptance'}
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'mockChecks': mock['checks'], 'mockRootMoves': mock['mockRootMoves'],
                      'javaChecks': java_test['checks'], 'fixedBodies': len(contract['bodies']),
                      'collectorObjectSha256': report['collectorObjectSha256'], 'deviceExecuted': False,
                      'report': str(receipt)}))


if __name__ == '__main__':
    main()
