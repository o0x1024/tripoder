#!/usr/bin/env python3
"""Build source-auditable H1/H2a probes. No Frida inputs or existing product agents."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile
import uuid
import importlib.util
from runtime_apk_builder import ApkBuilder, sha

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / 'android/self-hook'
PROBE = ROOT / 'android/self-hook-probe'


def captured(*args):
    return subprocess.run(list(map(str, args)), check=True, capture_output=True, text=True).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--key', type=Path, required=True)
    parser.add_argument('--platform-libc', type=Path, required=True)
    parser.add_argument('--platform-art', type=Path, required=True)
    parser.add_argument('--platform-linker', type=Path, required=True)
    args = parser.parse_args()
    build = PROBE / 'build'
    build.mkdir(parents=True, exist_ok=True)
    receipt = build / 'build-receipt.json'
    receipt.unlink(missing_ok=True)  # Failed builds cannot reuse an older successful receipt.
    toolchain = args.ndk.resolve() / 'toolchains/llvm/prebuilt/darwin-x86_64/bin'
    compiler = toolchain / 'aarch64-linux-android36-clang'
    c_sources = [CORE / 'native/a64_relocate.c', CORE / 'native/a64_entry.c',
                 CORE / 'native/code_page.c', CORE / 'native/owned_hook.c',
                 CORE / 'tests/core_checks.c', PROBE / 'native/probe.c', PROBE / 'native/probe_owned.c',
                 PROBE / 'native/probe_maps.c', PROBE / 'native/probe_art.c',
                 CORE / 'native/art_access.c', PROBE / 'native/probe_art_state.c',
                 PROBE / 'native/probe_art_binding.c', PROBE / 'native/probe_art_managed.c',
                 PROBE / 'native/probe_art_deopt.c', CORE / 'native/thread_park.c',
                 PROBE / 'native/probe_thread_park.c', CORE / 'native/park_diagnostic.c', CORE / 'native/page_commit.c',
                 PROBE / 'native/probe_noncooperative.c', CORE / 'native/fixed_libc.c',
                 ROOT / 'android/runtime/native/io_adapter.c', ROOT / 'android/runtime/native/path_policy.c',
                 PROBE / 'native/probe_libc.c', PROBE / 'native/probe_libc_cases.c',
                 CORE / 'native/art_reflection_view.c', PROBE / 'native/probe_reflection_view.c',
                 PROBE / 'native/probe_framework.c', CORE / 'native/art_framework.c', CORE / 'native/application_adapter.c',
                 CORE / 'native/art_int_entry.c', CORE / 'native/art_method_storage.c', CORE / 'native/art_managed_entry.c',
                 CORE / 'native/managed_abi.c', CORE / 'native/art_jni_original.c', PROBE / 'native/probe_gc.c', CORE / 'native/physical_application.c',
                 CORE / 'native/coverage/owned_entry_registry.c', CORE / 'coverage-fixtures/entry_leaf_fixture.c',
                 CORE / 'coverage-fixtures/entry_leaf_unwind.c']
    c_sources += [CORE/'native/art_interpreter_policy.c',CORE/'native/coverage/method_observation.c']
    c_sources += [CORE/'native/coverage/near_entry_leaf.c',CORE/'native/coverage/entry_branch_page.c']
    c_sources += [CORE/'native/coverage/art_entry_writer.c',PROBE/'native/probe_entry_writer.c']
    c_sources.append(PROBE/'native/probe_inflight.c')
    c_sources.append(CORE/'native/service_token_access.c')
    c_sources += sorted((CORE/'native/modules').glob('*.c'))+[PROBE/'native/probe_linker.c',PROBE/'native/probe_module_concurrency.c']
    scalar_fixture=PROBE/'abi-fixtures/native'
    c_sources += sorted((CORE/'native/abi').glob('*.c'))+[scalar_fixture/'scalar_cases.c', scalar_fixture/'scalar_fixture.c', scalar_fixture/'scalar_probe_jni.c',scalar_fixture/'scalar_inbound_fixture.c']
    c_sources.append(PROBE/'native/probe_native_export.c')
    c_sources.append(PROBE/'native/probe_native_race.c')
    c_sources.append(PROBE/'native/probe_native_jni.c')
    c_sources.append(PROBE/'native/probe_module_instances.c')
    c_sources.append(PROBE/'native/probe_native_leaf.c')
    c_sources.append(PROBE/'native/probe_jni_register.c')
    c_sources.append(PROBE/'native/probe_jni_sdk.c')
    c_sources.append(CORE/'native/near_address.c')
    java_sources = sorted((PROBE / 'src').rglob('*.java')) + sorted((CORE / 'java').rglob('*.java'))
    capacity_generator=PROBE/'capacity-fixtures/generate.py'
    spec=importlib.util.spec_from_file_location('capacity_generator',capacity_generator)
    capacity=importlib.util.module_from_spec(spec);spec.loader.exec_module(capacity)
    java_sources += capacity.generate(build/'capacity-fixtures')
    java_sources += sorted((PROBE/'script-fixtures/dev').rglob('*.java'))
    java_sources += sorted((ROOT/'android/runtime/src/dev/tripoder/runtime/script').glob('*.java'))
    java_sources += [ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeJavaObservation.java',
                     ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeJavaHookBackend.java',
                     ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeNativeObservation.java',
                     ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeNativeHookBackend.java',
                     ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeSelfHookPlatform.java',
                     ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeManagedInstaller.java',
                     ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeElfIdentity.java',ROOT/'android/runtime/src/dev/tripoder/runtime/RuntimeElfExports.java']
    java_sources += sorted((PROBE/'abi-fixtures/dev').rglob('*.java'))
    cpp_source = CORE / 'native/coverage/loaded_class_inventory.cpp'
    late_source = PROBE / 'coverage-fixtures/dev/tripoder/selfhookprobe/dynamic/LateCaller.java'
    inputs = [*c_sources, *java_sources, *sorted((CORE / 'include').glob('*.h')),
              CORE / 'native/a64_internal.h', CORE / 'native/code_page.h', CORE / 'native/art_access.h',
              CORE / 'native/park_syscall.h', CORE / 'native/park_diagnostic.h',
              CORE / 'native/libc_fixed_contract.h', ROOT / 'android/runtime/native/io_adapter.h',
              CORE / 'native/art_reflection_view.h',
              CORE / 'native/art_framework_contract.h', CORE / 'tests/check_art_framework_contract.py',
              ROOT / 'android/runtime/native/path_policy.h', PROBE / 'native/probe_libc_cases.h',
              CORE / 'native/art_fixed_contract.h', CORE / 'native/art_managed_contract.h', CORE / 'tests/core_checks.h',
              PROBE / 'native/probe_owned.h', PROBE / 'native/probe_maps.h', PROBE / 'native/fixture.S',
              CORE / 'tests/host_main.c', CORE / 'tests/check_host.py',
              CORE / 'tests/check_dex_graph.py', *sorted((CORE / 'tests/java').rglob('*.java')),
              CORE / 'tests/check_park_binary.py',
              CORE / 'tests/check_libc_contract.py',
              CORE / 'tests/check_int_relay.py',
              CORE / 'native/managed_abi.h', CORE / 'tests/check_helper_dex.py', CORE / 'tests/check_caller_closure.py',
              CORE / 'native/art_method_storage.h', CORE / 'native/art_storage_contract.h',CORE/'native/art_jni_original.h',CORE/'native/art_jni_contract.h',
              CORE / 'tests/check_art_storage_contract.py',
              PROBE / 'AndroidManifest.xml', PROBE / 'platform.json',
              Path(__file__).resolve(), ROOT / 'android/scripts/runtime_apk_builder.py',
              ROOT / 'android/scripts/verify-self-hook-probe.py']
    inputs.append(ROOT / 'android/scripts/verify_self_libc.py')
    inputs.append(ROOT / 'android/scripts/verify-self-framework-probe.py')
    inputs.append(ROOT / 'android/scripts/verify-self-application-probe.py')
    inputs.append(ROOT / 'android/scripts/verify-self-art-compilation.py')
    inputs.append(ROOT / 'android/scripts/verify-self-active-entry.py')
    inputs.append(ROOT / 'android/scripts/verify-self-managed-abi.py')
    inputs.append(ROOT / 'android/scripts/verify_self_storage.py')
    inputs.append(ROOT / 'android/scripts/verify-self-cold-start.py')
    inputs += [cpp_source, CORE / 'native/coverage/loaded_class_buffer.hpp', CORE / 'native/coverage/loaded_class_contract.h',
               late_source, ROOT / 'android/scripts/verify-self-coverage.py',
               CORE / 'tests/coverage/check_loaded_classes.py', CORE / 'tests/coverage/loaded_class_contract.py',
               CORE / 'tests/coverage/entry_writer_audit.py', CORE / 'tests/coverage/loaded_class_buffer_test.cpp',
               CORE / 'tests/coverage/LoadedClassInventoryHostTest.java']
    leaf_source=CORE/'coverage-fixtures/entry_leaf_fixture.S'
    inputs += [leaf_source, CORE/'native/coverage/owned_entry_registry.h', CORE/'tests/coverage/check_entry_leaf.py',
               CORE/'coverage-fixtures/entry_leaf_unwind.h']
    inputs.append(ROOT/'android/scripts/verify-self-script-control.py')
    inputs += [CORE/'native/art_interpreter_policy.h',CORE/'native/art_interpreter_contract.h',
               CORE/'tests/check_art_interpreter_contract.py',ROOT/'android/scripts/verify_self_interpreter.py']
    inputs += [CORE/'native/coverage/entry_leaf_template.S',CORE/'native/coverage/near_entry_leaf.h',
               CORE/'native/coverage/entry_branch_page.h',CORE/'tests/coverage/check_near_leaf.py',
               ROOT/'android/scripts/verify-self-entry-publication.py']
    inputs += [CORE/'native/coverage/art_entry_writer.h',CORE/'native/coverage/art_entry_writer_contract.h',
               CORE/'tests/coverage/check_art_entry_writer_contract.py',ROOT/'android/scripts/verify-self-entry-writer.py']
    inputs.append(ROOT/'android/scripts/verify_self_entry_evidence.py')
    inputs.append(capacity_generator)
    inputs += [ROOT/'android/scripts/verify_self_owned_run.py',ROOT/'android/scripts/verify-self-capacity.py',
               ROOT/'android/scripts/verify-self-manager-inventory.py',ROOT/'android/scripts/verify-self-service-token.py',
               CORE/'manager-inventory.json',CORE/'component-inventory.json',CORE/'runtime-inventory.json']
    inputs.append(ROOT/'android/scripts/verify-self-product-framework.py')
    inputs.append(ROOT/'android/scripts/verify-self-manager-installer.py')
    inputs.append(ROOT/'android/scripts/verify-self-constructor.py')
    inputs.append(ROOT/'android/scripts/verify-self-early-application.py')
    inputs.append(ROOT/'android/scripts/verify-self-jni-register-abi.py')
    jni_guest=PROBE/'jni-fixtures/dev/tripoder/selfhookprobe/jniguest/JniSdkTarget.java'
    inputs += [jni_guest,ROOT/'android/scripts/verify-self-jni-sdk.py']
    inputs.append(ROOT/'android/scripts/verify-self-product-java.py')
    inputs.append(CORE/'native/near_address.h')
    inputs += [CORE/'tests/libc_entry_audit.py',CORE/'tests/check_libc_absolute.py']
    inputs += sorted((CORE/'native/modules').glob('*.h'))+sorted((CORE/'native/modules').glob('*.S'))
    inputs += sorted((CORE/'tests/modules').glob('*.py'))+sorted((CORE/'tests/modules').glob('*.c'))
    inputs += [PROBE/'native/linker_failure.S',PROBE/'native/notifier_register_oracle.S',ROOT/'android/scripts/verify-self-module-lifetime.py']
    inputs += [PROBE/'native/probe_module_concurrency.h',ROOT/'android/scripts/verify-self-module-death.py',ROOT/'android/scripts/self_module_death.py']
    inputs += [CORE/'native/abi/scalar_inbound.h',CORE/'native/abi/scalar_inbound_a64.S',CORE/'tests/abi/check_inbound.py',ROOT/'android/scripts/verify-self-native-inbound.py']
    inputs += sorted((CORE/'native/abi').glob('*.h'))+sorted((CORE/'native/abi').glob('*.S'))
    inputs += [CORE/'tests/abi/check_native_prefix.py',CORE/'tests/abi/check_native_template.py',CORE/'tests/abi/native_prefix_test.c',
               ROOT/'android/runtime-fixtures/native/hook_target.c',ROOT/'android/scripts/verify-self-native-export.py']
    inputs.append(ROOT/'android/scripts/verify-self-native-race.py')
    inputs.append(ROOT/'android/scripts/verify-self-native-jni.py')
    inputs.append(ROOT/'android/scripts/verify-self-product-native.py')
    inputs.append(ROOT/'android/scripts/verify-self-product-mixed.py')
    inputs += [PROBE/'native/leaf_target.c',ROOT/'android/scripts/verify-self-native-leaf.py',ROOT/'android/scripts/verify-self-module-catalog.py']
    inputs.append(PROBE/'native/probe_module_instances.h')
    inputs.append(ROOT/'android/scripts/verify-self-inflight.py')
    inputs += [CORE/'native/abi/scalar_abi.h',CORE/'native/abi/scalar_call_a64.S',scalar_fixture/'scalar_capture_a64.S',
               scalar_fixture/'scalar_cases.h',scalar_fixture/'scalar_capture.h',CORE/'tests/abi/check_scalar.py',
               CORE/'tests/abi/scalar_host_test.c',CORE/'tests/abi/generate_cases.py',ROOT/'android/scripts/verify-self-scalar.py']
    source_hashes = {str(path.relative_to(ROOT)): sha(path) for path in sorted(set(inputs))}
    identity = hashlib.sha256(json.dumps(source_hashes, sort_keys=True).encode()).hexdigest()
    subprocess.run([sys.executable,str(CORE/'tests/modules/check_linker_contract.py'),'--linker',str(args.platform_linker),
                    '--output',str(build/'linker-contract.json')],check=True)
    subprocess.run([sys.executable,str(CORE/'tests/modules/check_notifier_template.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'notifier-template.json')],check=True)
    lifetime_host=build/'module-lifetime-host'
    subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-pthread',str(CORE/'tests/modules/lifetime_test.c'),
                    str(CORE/'native/modules/module_lifetime.c'),'-o',str(lifetime_host)],check=True)
    (build/'module-lifetime-host.json').write_text(captured(lifetime_host))
    subprocess.run([sys.executable, str(CORE / 'tests/check_libc_contract.py'), '--libc', str(args.platform_libc),
                    '--output', str(build / 'libc-contract.json')], check=True)
    subprocess.run([sys.executable,str(CORE/'tests/check_libc_absolute.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'libc-absolute-oracle.json')],check=True)
    subprocess.run([sys.executable, str(CORE / 'tests/check_art_framework_contract.py'), '--art', str(args.platform_art),
                    '--output', str(build / 'art-framework-contract.json')], check=True)
    subprocess.run([sys.executable, str(CORE / 'tests/check_art_storage_contract.py'), '--art', str(args.platform_art),
                    '--output', str(build / 'art-storage-contract.json')], check=True)
    subprocess.run([sys.executable,str(CORE/'tests/check_art_interpreter_contract.py'),'--art',str(args.platform_art),
                    '--output',str(build/'art-interpreter-contract.json')],check=True)
    subprocess.run([sys.executable,str(CORE/'tests/coverage/check_art_entry_writer_contract.py'),'--art',str(args.platform_art),
                    '--output',str(build/'art-entry-writer-contract.json')],check=True)
    subprocess.run([sys.executable, str(CORE / 'tests/check_int_relay.py'), '--ndk', str(args.ndk),
                    '--output', str(build / 'int-relay-oracle.json')], check=True)
    subprocess.run([sys.executable, str(CORE / 'tests/check_helper_dex.py'), '--sdk', str(args.sdk),
                    '--java-home', str(args.java_home), '--output', str(build / 'helper-dex-oracle.json')], check=True)
    subprocess.run([sys.executable, str(CORE / 'tests/check_caller_closure.py'), '--sdk', str(args.sdk),
                    '--java-home', str(args.java_home), '--output', str(build / 'caller-closure-oracle.json')], check=True)
    subprocess.run([sys.executable, str(CORE / 'tests/coverage/check_loaded_classes.py'), '--ndk', str(args.ndk),
                    '--sdk', str(args.sdk), '--java-home', str(args.java_home), '--art', str(args.platform_art)], check=True)
    shutil.copyfile(CORE / 'build/coverage-host/loaded-class-report.json', build / 'loaded-class-report.json')
    subprocess.run([sys.executable,str(CORE/'tests/coverage/check_entry_leaf.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'entry-leaf-audit.json')],check=True)
    subprocess.run([sys.executable,str(CORE/'tests/coverage/check_near_leaf.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'near-leaf-audit.json')],check=True)
    subprocess.run([sys.executable,str(CORE/'tests/abi/check_scalar.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'scalar-host-report.json')],check=True)
    subprocess.run([sys.executable,str(CORE/'tests/abi/check_inbound.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'inbound-audit.json')],check=True)
    subprocess.run([sys.executable,str(CORE/'tests/abi/check_native_prefix.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'native-prefix-audit.json')],check=True)
    subprocess.run([sys.executable,str(CORE/'tests/abi/check_native_template.py'),'--ndk',str(args.ndk),
                    '--output',str(build/'native-template-audit.json')],check=True)
    cpp_object = build / 'loaded_class_inventory.o'
    cpp_command = [toolchain / 'aarch64-linux-android36-clang++', '-std=c++20', '-O2', '-g', '-fPIC',
                   '-fno-exceptions', '-fno-rtti', '-fno-sized-deallocation', '-mbranch-protection=standard',
                   '-mno-outline-atomics', '-Wall', '-Wextra', '-Werror', '-Wconversion', '-c', cpp_source, '-o', cpp_object]
    subprocess.run(list(map(str, cpp_command)), check=True)
    library = build / 'libtripoderselfhook.so'
    fixture = build / 'libtripoderhookfixture.so'
    fixture_command = [compiler, '-shared', '-nostdlib', '-fPIC',
                       '-Wl,-z,max-page-size=4096,-z,common-page-size=4096,-z,noexecstack,--build-id=sha1',
                       PROBE / 'native/fixture.S', '-o', fixture]
    subprocess.run(list(map(str, fixture_command)), check=True)
    linker_failure=build/'libtripoderlinkerfailure.so'
    subprocess.run(list(map(str,[compiler,'-shared','-nostdlib','-fPIC',
        '-Wl,-z,max-page-size=4096,-z,common-page-size=4096,-z,noexecstack,--build-id=sha1',
        PROBE/'native/linker_failure.S','-o',linker_failure])),check=True)
    owned_native=build/'libtripoderownedhooks.so'
    owned_native_command=[compiler,'-O2','-Wall','-Wextra','-Werror','-fPIC','-shared',
        '-Wl,-z,max-page-size=16384','-Wl,--build-id=sha1',ROOT/'android/runtime-fixtures/native/hook_target.c','-o',owned_native]
    subprocess.run(list(map(str,owned_native_command)),check=True)
    owned_native_pac=build/'libtripoderownedhookspac.so'
    owned_native_pac_command=[*owned_native_command[:-2],'-mbranch-protection=standard','-o',owned_native_pac]
    subprocess.run(list(map(str,owned_native_pac_command)),check=True)
    leaf_native=build/'libtripoderleafhooks.so'
    leaf_native_command=[compiler,'-shared','-nostdlib','-O2','-Wall','-Wextra','-Werror','-fPIC',
        '-Wl,-z,max-page-size=4096,-z,common-page-size=4096,-z,noexecstack,--build-id=sha1',PROBE/'native/leaf_target.c','-o',leaf_native]
    subprocess.run(list(map(str,leaf_native_command)),check=True)
    if not 0<leaf_native.stat().st_size<4096:raise RuntimeError('NATURAL_LEAF_PARTIAL_PAGE_REQUIRED')
    fixture_dynamic = captured(toolchain / 'llvm-readelf', '-d', fixture)
    if '(NEEDED)' in fixture_dynamic:
        raise RuntimeError('FIXTURE_MUST_HAVE_NO_DEPENDENCIES')
    if any(tag in fixture_dynamic for tag in ('(INIT)', '(INIT_ARRAY)', '(FINI)', '(FINI_ARRAY)')):
        raise RuntimeError('FIXTURE_MUST_HAVE_NO_LIFECYCLE_CODE')
    fixture_exports = captured(toolchain / 'llvm-nm', '--dynamic', '--defined-only', fixture)
    if {line.split()[-1] for line in fixture_exports.splitlines() if line.split()} != {
            'tp_fixture_target', 'tp_fixture_trampoline', 'tp_fixture_replacement', 'tp_fixture_callback'}:
        raise RuntimeError('UNEXPECTED_FIXTURE_EXPORTS')
    (build / 'fixture-elf.txt').write_text(captured(toolchain / 'llvm-readelf', '-l', '-n', '-s', fixture)
                                          + '\n' + fixture_dynamic + '\n' + fixture_exports)
    command = [compiler, '-std=c11', '-O2', '-g', '-Wall', '-Wextra', '-Werror', '-fPIC',
               '-shared', '-fvisibility=hidden', '-mbranch-protection=standard', '-mno-outline-atomics',
               '-DTP_SELF_HOOK_TESTING=1',
               '-I' + str(CORE / 'include'), '-I' + str(CORE / 'tests'), '-I' + str(CORE / 'native'),
               '-I' + str(ROOT / 'android/runtime/native'),
               '-I'+str(CORE/'native/abi'),'-I'+str(scalar_fixture),
               '-I'+str(build),
               '-Wl,--no-undefined,-z,relro,-z,now,-z,noexecstack,--build-id=sha1',
               *c_sources, PROBE/'native/notifier_register_oracle.S',cpp_object, build/'entry_leaf_fixture.o', build/'scalar_call.o',build/'scalar_capture.o',build/'scalar_inbound.o','-o', library]
    subprocess.run(list(map(str, command)), check=True)
    subprocess.run([sys.executable, str(CORE / 'tests/check_park_binary.py'), '--library', str(library),
                    '--objdump', str(toolchain / 'llvm-objdump'), '--output', str(build / 'park-binary-audit.json')], check=True)
    dynamic = captured(toolchain / 'llvm-readelf', '-d', library)
    dependencies = re.findall(r'\(NEEDED\).*\[(.*?)\]', dynamic)
    if not dependencies or set(dependencies) - {'libc.so', 'libm.so', 'libdl.so'}:
        raise RuntimeError('UNEXPECTED_ELF_DEPENDENCIES:' + repr(dependencies))
    (build / 'native-dynamic.txt').write_text(dynamic)
    (build / 'native-notes.txt').write_text(captured(toolchain / 'llvm-readelf', '-n', library))
    assets = build / 'assets'
    assets.mkdir(exist_ok=True)
    build_identity = {'schema': 1, 'buildId': str(uuid.uuid4()),
                      'timestamp': datetime.now(timezone.utc).isoformat(),
                      'sourceDigest': identity, 'nativeSha256': sha(library), 'fixtureSha256': sha(fixture),
                      'ownedNativeBuildId':re.search(r'Build ID: ([a-f0-9]+)',captured(toolchain/'llvm-readelf','-n',owned_native)).group(1),
                      'ownedNativePacBuildId':re.search(r'Build ID: ([a-f0-9]+)',captured(toolchain/'llvm-readelf','-n',owned_native_pac)).group(1),
                      'scope': 'H1/H2a owned ELF, native thread parking and fixed-platform ART with static caller indexing'}
    (assets / 'build.json').write_text(json.dumps(build_identity, indent=2) + '\n')
    shutil.copyfile(PROBE / 'platform.json', assets / 'platform.json')
    late_classes, late_dex = build / 'late-classes', build / 'late-dex'
    late_classes.mkdir(exist_ok=True); late_dex.mkdir(exist_ok=True)
    subprocess.run(list(map(str, [args.java_home / 'bin/javac', '--release', '17', '-d', late_classes,
        PROBE / 'src/dev/tripoder/selfhookprobe/CoverageTarget.java', late_source])), check=True)
    subprocess.run(list(map(str, [args.java_home / 'bin/java', '-cp', args.sdk / 'build-tools/37.0.0/lib/d8.jar',
        'com.android.tools.r8.D8', '--min-api', '28', '--output', late_dex,
        late_classes / 'dev/tripoder/selfhookprobe/dynamic/LateCaller.class'])), check=True)
    shutil.copyfile(late_dex / 'classes.dex', assets / 'late-caller.dex')
    jni_classes,jni_dex=build/'jni-classes',build/'jni-dex'
    jni_classes.mkdir(exist_ok=True);jni_dex.mkdir(exist_ok=True)
    subprocess.run(list(map(str,[args.java_home/'bin/javac','--release','17','-classpath',args.sdk/'platforms/android-37.0/android.jar','-d',jni_classes,jni_guest])),check=True)
    subprocess.run(list(map(str,[args.java_home/'bin/java','-cp',args.sdk/'build-tools/37.0.0/lib/d8.jar','com.android.tools.r8.D8','--min-api','36','--output',jni_dex,jni_classes/'dev/tripoder/selfhookprobe/jniguest/JniSdkTarget.class'])),check=True)
    shutil.copyfile(jni_dex/'classes.dex',assets/'jni-guest.dex')
    # Reject old/foreign assets rather than silently packaging them.
    if {path.name for path in assets.iterdir()} != {'build.json', 'platform.json', 'late-caller.dex','jni-guest.dex'}:
        raise RuntimeError('UNEXPECTED_ASSET')
    builder = ApkBuilder(ROOT, build, args.sdk, 'android-37.0', '37.0.0', args.java_home, args.key)
    apk = builder.apk('self-hook-probe', java_sources, PROBE / 'AndroidManifest.xml',
                      assets=assets, libraries=[library, fixture,linker_failure,owned_native,owned_native_pac,leaf_native])
    with zipfile.ZipFile(apk) as archive:
        entries = sorted(archive.namelist())
        allowed = {'AndroidManifest.xml', 'resources.arsc', 'classes.dex', 'assets/build.json', 'assets/platform.json', 'assets/late-caller.dex','assets/jni-guest.dex',
                   'lib/arm64-v8a/libtripoderselfhook.so', 'lib/arm64-v8a/libtripoderhookfixture.so','lib/arm64-v8a/libtripoderlinkerfailure.so','lib/arm64-v8a/libtripoderownedhooks.so','lib/arm64-v8a/libtripoderownedhookspac.so','lib/arm64-v8a/libtripoderleafhooks.so'}
        unexpected = [name for name in entries if name not in allowed and not name.startswith('META-INF/')]
        if unexpected or not allowed.issubset(entries):
            raise RuntimeError('APK_ENTRY_ALLOWLIST:' + repr(unexpected))
        if hashlib.sha256(archive.read('lib/arm64-v8a/libtripoderselfhook.so')).hexdigest() != sha(library):
            raise RuntimeError('PACKAGED_NATIVE_IDENTITY_MISMATCH')
        if hashlib.sha256(archive.read('lib/arm64-v8a/libtripoderhookfixture.so')).hexdigest() != sha(fixture):
            raise RuntimeError('PACKAGED_FIXTURE_IDENTITY_MISMATCH')
        if hashlib.sha256(archive.read('lib/arm64-v8a/libtripoderlinkerfailure.so')).hexdigest()!=sha(linker_failure):
            raise RuntimeError('PACKAGED_LINKER_FAILURE_IDENTITY_MISMATCH')
        if hashlib.sha256(archive.read('lib/arm64-v8a/libtripoderownedhooks.so')).hexdigest()!=sha(owned_native):
            raise RuntimeError('PACKAGED_OWNED_NATIVE_IDENTITY_MISMATCH')
        if hashlib.sha256(archive.read('lib/arm64-v8a/libtripoderownedhookspac.so')).hexdigest()!=sha(owned_native_pac):
            raise RuntimeError('PACKAGED_OWNED_NATIVE_PAC_IDENTITY_MISMATCH')
        if hashlib.sha256(archive.read('lib/arm64-v8a/libtripoderleafhooks.so')).hexdigest()!=sha(leaf_native):
            raise RuntimeError('PACKAGED_LEAF_NATIVE_IDENTITY_MISMATCH')
    if source_hashes != {str(path.relative_to(ROOT)): sha(path) for path in sorted(set(inputs))}:
        raise RuntimeError('SOURCES_CHANGED_DURING_BUILD')
    report = {'schema': 1, **build_identity, 'sources': source_hashes, 'apkSha256': sha(apk),
              'linkerFailureSha256':sha(linker_failure),
              'ownedNativeSha256':sha(owned_native),'ownedNativeCompileCommand':list(map(str,owned_native_command)),
              'ownedNativePacSha256':sha(owned_native_pac),'ownedNativePacCompileCommand':list(map(str,owned_native_pac_command)),
              'leafNativeSha256':sha(leaf_native),'leafNativeCompileCommand':list(map(str,leaf_native_command)),
              'apk': str(apk), 'apkEntries': entries, 'nativeDependencies': dependencies,
              'certificateSha256': builder.cert_sha256, 'developmentSigningOnly': True,
              'compiler': captured(compiler, '--version'),
              'ndk': (args.ndk / 'source.properties').read_text(),
              'compileCommand': list(map(str, command)), 'fixtureCompileCommand': list(map(str, fixture_command)),
              'cppCompileCommand': list(map(str, cpp_command)), 'lateDexSha256': sha(assets / 'late-caller.dex'),
              'fixtureDependencies': [], 'fridaInputs': [],
              'productFridaRemoved': False}
    report['platformInputs'] = {'libc': str(args.platform_libc.resolve()), 'libcSha256': sha(args.platform_libc)}
    report['platformInputs'].update({'art': str(args.platform_art.resolve()), 'artSha256': sha(args.platform_art)})
    receipt.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'receipt': str(build / 'build-receipt.json'), 'sourceDigest': identity}))


if __name__ == '__main__':
    main()
