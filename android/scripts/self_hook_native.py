"""The sole product core build. No probe JNI or duplicate IO policy is linked."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from runtime_apk_builder import sha


def build_native(repo, build, ndk, libc, art, linker):
    repo, build, ndk = Path(repo).resolve(), Path(build).resolve(), Path(ndk).resolve()
    core = repo / 'android/self-hook'
    runtime = repo / 'android/runtime/native'
    build.mkdir(parents=True, exist_ok=True)
    receipt = build / 'self-hook-native.json'
    receipt.unlink(missing_ok=True)
    toolchain = ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    clang = toolchain / 'aarch64-linux-android36-clang'
    checks = [
        (core/'tests/check_libc_contract.py', '--libc', libc),
        (core/'tests/check_art_framework_contract.py', '--art', art),
        (core/'tests/check_art_storage_contract.py', '--art', art),
        (core/'tests/check_art_interpreter_contract.py', '--art', art),
        (core/'tests/coverage/check_art_entry_writer_contract.py', '--art', art),
        (core/'tests/modules/check_linker_contract.py', '--linker', linker),
        (core/'tests/coverage/check_near_leaf.py', '--ndk', ndk),
        (core/'tests/modules/check_notifier_template.py', '--ndk', ndk),
        (core/'tests/abi/check_native_template.py', '--ndk', ndk),
        (core/'tests/abi/check_native_upgrade.py', '--ndk', ndk),
        (core/'tests/check_libc_absolute.py', '--ndk', ndk),
    ]
    sources = sorted((core/'native').glob('*.c'))
    for group in ('coverage', 'abi', 'modules'):
        sources += sorted((core/'native'/group).glob('*.c'))
    sources += [runtime/name for name in ('io_adapter.c', 'path_policy.c', 'self_hook_storage.c', 'exit_diagnostics.c', 'syscall_namespace.c', 'ptrace_namespace.c', 'user_memory.c', 'mapping_evidence.c')]
    assembly = [core/'native/abi/scalar_call_a64.S', core/'native/abi/scalar_inbound_a64.S',runtime/'kernel_syscall_a64.S']
    cpp = core/'native/coverage/loaded_class_inventory.cpp'
    inputs = set(sources + assembly + [cpp, Path(__file__).resolve(), core/'tests/check_park_binary.py'])
    java_inputs=sorted((core/'java').rglob('*.java'))+[repo/'android/runtime/src/dev/tripoder/runtime'/name for name in ('RuntimeSelfHookStorage.java','RuntimeNativeMappingEvidence.java','script/JavaObjectSnapshots.java')]
    inputs.update(java_inputs)
    for root in (core/'native', core/'include', runtime):
        inputs.update(path for path in root.rglob('*') if path.suffix in ('.h', '.hpp', '.S'))
    inputs.update(path for path, _, _ in checks)
    inputs.add(core/'tests/coverage/entry_writer_audit.py')
    inputs.add(core/'tests/libc_entry_audit.py')
    inputs.add(repo/'android/scripts/runtime_apk_builder.py')
    inputs.update([core/'tests/abi/native_address_test.c',core/'tests/abi/near_address_test.c'])
    before = {str(path.relative_to(repo)): sha(path) for path in sorted(inputs)}
    def run(*args):
        result=subprocess.run(list(map(str, args)), cwd=repo, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError('SELF_HOOK_NATIVE_BUILD_FAILED:'+str(args[0])+'\n'+result.stdout[-4000:]+'\n'+result.stderr[-8000:])
        return result.stdout
    for index, (script, option, value) in enumerate(checks):
        run(sys.executable, script, option, Path(value).resolve(), '--output', build/f'contract-{index}.json')
    flags = ['-O2', '-g', '-fPIC', '-fvisibility=hidden', '-mbranch-protection=standard', '-mno-outline-atomics', '-Wall', '-Wextra', '-Werror']
    objects = []
    for source in assembly:
        target = build/(source.stem+'.o')
        run(clang, *flags, '-c', source, '-o', target)
        objects.append(target)
    target = build/'loaded_class_inventory.o'
    run(toolchain/'aarch64-linux-android36-clang++', '-std=c++20', *flags,
        '-fno-exceptions', '-fno-rtti', '-fno-sized-deallocation', '-Wconversion', '-c', cpp, '-o', target)
    objects.append(target)
    library = build/'libtripoderselfhook.so'
    command = [clang, '-std=c11', *flags, '-shared', '-fvisibility=hidden',
        '-I'+str(core/'include'), '-I'+str(core/'native'), '-I'+str(core/'native/abi'),
        '-I'+str(runtime), '-I'+str(build),
        '-Wl,--no-undefined,-z,relro,-z,now,-z,noexecstack,--build-id=sha1',
        *sources, *objects, '-o', library]
    run(*command)
    run(sys.executable, core/'tests/check_park_binary.py', '--library', library,
        '--objdump', toolchain/'llvm-objdump', '--output', build/'park-binary-audit.json', '--product')
    dynamic = run(toolchain/'llvm-readelf', '-d', library)
    dependencies = re.findall(r'\(NEEDED\).*\[(.*?)\]', dynamic)
    if not dependencies or set(dependencies)-{'libc.so', 'libm.so', 'libdl.so'}:
        raise RuntimeError('SELF_HOOK_NATIVE_DEPENDENCIES:'+repr(dependencies))
    exports = run(toolchain/'llvm-nm', '--dynamic', '--defined-only', library)
    symbols = {line.split()[-1] for line in exports.splitlines() if line.split()}
    if not symbols or any(not name.startswith(('Java_dev_tripoder_selfhook_', 'Java_dev_tripoder_selfhook_coverage_',
                                               'Java_dev_tripoder_runtime_RuntimeSelfHookStorage_', 'Java_dev_tripoder_runtime_script_JavaObjectSnapshots_'))
                          and name!='Java_dev_tripoder_runtime_RuntimeNativeMappingEvidence_relevantRowsNative' for name in symbols):
        raise RuntimeError('SELF_HOOK_PRODUCT_EXPORTS:'+exports)
    # A JNI method is resolved by name; a declaration whose symbol is absent fails on the device at first use, and a
    # symbol without a declaration is dead code. Neither can be caught by compiling, so the pair is checked here as a
    # bijection instead of being discovered by a guest startup failure.
    declared = {}
    for source in java_inputs:
        text = source.read_text()
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        text = re.sub(r'//[^\n]*', '', text)
        text = ' '.join(text.split())
        package = re.search(r'package\s+([\w.]+)\s*;', text)
        if not package:
            continue
        for method in re.finditer(r'\bnative\b[^;{}]*?(\b\w+)\s*\(', text):
            name = 'Java_'+package.group(1).replace('.','_')+'_'+source.stem+'_'+method.group(1)
            declared[name] = source.name
    java_exports = {name for name in symbols if name.startswith('Java_dev_tripoder_')}
    # The other legal implementation is RegisterNatives from a table of method names; the table entry is the literal,
    # so a declared method is implemented when either the direct export or its registration literal exists.
    registered = set()
    for source in list((core/'native').rglob('*.c'))+list((core/'native').rglob('*.cpp'))+list((core/'native').rglob('*.h')):
        registered.update(re.findall(r'"(\w+)"', source.read_text()))
    missing = sorted(name for name in declared
                     if name not in java_exports and not any(export.startswith(name+'__') for export in java_exports)
                     and name.rsplit('_',1)[-1] not in registered)
    unexpected = sorted(name for name in java_exports
                        if not any(name == expected or name.startswith(expected+'__') for expected in declared))
    if missing or unexpected:
        raise RuntimeError('SELF_HOOK_JNI_CONTRACT_MISMATCH:missing='+repr(missing)+':unexpected='+repr(unexpected))
    if before != {str(path.relative_to(repo)): sha(path) for path in sorted(inputs)}:
        raise RuntimeError('SELF_HOOK_NATIVE_SOURCES_CHANGED')
    report = {'schema':1, 'library':str(library), 'sha256':sha(library), 'sourceSha256':before,
              'compileCommand':list(map(str, command)), 'nativeDependencies':dependencies,
              'exports':sorted(symbols), 'probeJniIncluded':False, 'ioPolicyInstances':1,
              'platformInputs':{name:sha(Path(path)) for name,path in [('libc',libc),('art',art),('linker',linker)]}}
    receipt.write_text(json.dumps(report, indent=2)+'\n')
    return library, report


def add_platform_inputs(parser):
    for name in ('libc', 'art', 'linker'):
        parser.add_argument('--platform-'+name, type=Path, required=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    add_platform_inputs(parser)
    args=parser.parse_args()
    library, report=build_native(Path(__file__).resolve().parents[2], args.build, args.ndk,
                                args.platform_libc, args.platform_art, args.platform_linker)
    print(json.dumps({'library':str(library), 'sha256':report['sha256'], 'probeJniIncluded':False, 'ioPolicyInstances':1}))
