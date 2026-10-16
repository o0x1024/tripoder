"""Deterministic method-only model extension for the pinned Frida Java bridge.

No dependency downgrade, alternate engine or error-triggered path. A method hook has
no reason to enumerate fields: receiver-01 records a device JVMTI GetFieldName crash.
Changes apply only to the generated build copy; npm's installed files stay unchanged.
"""
import hashlib
import json
from pathlib import Path
import shutil


PINNED = {
    "lib/class-model.js": "48f6b9da88ead79b109358fb7866aa5d10be1711c8a4a7693f876397b22b5010",
    "lib/class-factory.js": "3905ae235bfd58fa4b1118075df781c090108c399ff19e093f568104dbb0dc3b",
    "lib/android.js": "ee119401893ab7353525fe181c99554cd13776c10a47e24ebf29c2d0de28a11c",
}


def prepare_bridge(repo: Path, build: Path):
    source = repo / "node_modules/frida-java-bridge"
    if json.loads((source / "package.json").read_text())["version"] != "7.0.13":
        raise RuntimeError("BRIDGE_VERSION_NOT_REVIEWED")
    for name, digest in PINNED.items():
        if hashlib.sha256((source / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError("BRIDGE_SOURCE_NOT_REVIEWED:" + name)
    target = build / "frida-java-bridge-method-model"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(source, target)

    def change(name, before, after, count=1):
        path = target / name
        text = path.read_text()
        if text.count(before) != count:
            raise RuntimeError("BRIDGE_EDIT_PRECONDITION:" + name)
        path.write_text(text.replace(before, after))

    factory = "lib/class-factory.js"
    change(factory, "const allowCached = options.cache !== 'skip';", "const methodsOnly = options.members === 'methods';\n    const allowCached = !methodsOnly && options.cache !== 'skip';")
    change(factory, "this._make(className, getClassHandle, env)", "this._make(className, getClassHandle, env, methodsOnly)")
    change(factory, "_make (name, getClassHandle, env)", "_make (name, getClassHandle, env, methodsOnly = false)")
    change(factory, "ClassModel.build(classHandle, env)", "ClassModel.build(classHandle, env, methodsOnly)")
    model = "lib/class-model.js"
    change(model, "           JNIEnv * env)\n{\n  Model * model;", "           JNIEnv * env,\n           jboolean methods_only)\n{\n  Model * model;")
    # Each backend returns its completed method model before touching any fields.
    change(model, "    get_class_fields (jvmti, class_handle, &field_count, &fields);", "    if (methods_only) return model;\n\n    get_class_fields (jvmti, class_handle, &field_count, &fields);")
    change(model, "    for (field_array_cursor = 0;", "    if (methods_only) return model;\n\n    for (field_array_cursor = 0;")
    change(model, "    elements = call_object_method (env, class_handle, java_api.clazz.get_declared_fields);", "    if (methods_only) return model;\n\n    elements = call_object_method (env, class_handle, java_api.clazz.get_declared_fields);")
    change(model, "static build (handle, env)", "static build (handle, env, methodsOnly = false)")
    change(model, "cm.new(handle, object, env)", "cm.new(handle, object, env, methodsOnly ? 1 : 0)")
    change(model, "new NativeFunction(cm.model_new, 'pointer', ['pointer', 'pointer', 'pointer'], reentrantOptions)", "new NativeFunction(cm.model_new, 'pointer', ['pointer', 'pointer', 'pointer', 'bool'], reentrantOptions)")
    # Frida upstream 302a588881b40cba300bcf3acfd35deeb3888ee5 fixes the
    # Android 17 method flags scan and ARM64 transition literal-pool placement.
    # Applied unconditionally to the pinned source, never selected after a crash.
    art = "lib/android.js"
    change(art, "kAccPublicApi | kAccNterpInvokeFastPathFlag) >>> 0;",
           "kAccPublicApi | kAccNterpInvokeFastPathFlag | kAccNterpEntryPointFastPathFlag) >>> 0;")
    change(art, "  writer.putBLabel('performTransition');\n\n  const invokeCallback", "  const invokeCallback")
    change(art, "  writer.putLabel('performTransition');", "  writer.flush();\n\n  const performEntry = pc.add(writer.offset);")
    change(art, "  return new NativeFunction(pc, 'void', ['pointer'], nativeFunctionOptions);\n}\n\nfunction throwThreadStateTransitionParseError",
           "  return new NativeFunction(performEntry, 'void', ['pointer'], nativeFunctionOptions);\n}\n\nfunction throwThreadStateTransitionParseError")
    # API 37 Instrumentation::Deoptimize walks every managed thread stack and
    # requires the mutator lock exclusively. Runnable state holds only its shared
    # side. Use one native call while our attached agent thread stays native;
    # never execute JNI or a JS callback between SuspendAll and ResumeAll.
    change(art, "export function deoptimizeMethod (vm, env, method) {\n  requestDeoptimization(vm, env, kSelectiveDeoptimization, method);\n}", """let tripoderSelectiveDeoptimization = null;
export function deoptimizeMethod (vm, env, method) {
  if (getAndroidApiLevel() !== 37 || Process.arch !== 'arm64') {
    throw new Error('TRIPODER_DEOPT_PLATFORM_UNSUPPORTED');
  }
  const api = getApi();
  if (api.artInstrumentation === null) throw new Error('TRIPODER_ART_INSTRUMENTATION_MISSING');
  if (tripoderSelectiveDeoptimization === null) {
    const module = new CModule(`
      extern void suspend_all(void *, const char *, int);
      extern void resume_all(void *);
      extern void deoptimize(void *, void *);
      void perform(void *threads, void *instrumentation, void *method) {
        suspend_all(threads, "tripoder-selective-deoptimization", 0);
        deoptimize(instrumentation, method);
        resume_all(threads);
      }
    `, {
      suspend_all: api['art::ThreadList::SuspendAll'],
      resume_all: api['art::ThreadList::ResumeAll'],
      deoptimize: api['art::Instrumentation::Deoptimize']
    });
    tripoderSelectiveDeoptimization = { module, perform: new NativeFunction(
      module.perform, 'void', ['pointer', 'pointer', 'pointer'], { exceptions: 'propagate' }) };
  }
  tripoderSelectiveDeoptimization.perform(api.artThreadList, api.artInstrumentation, method);
}""")
    change(art, "    if ('artInterpreterToCompiledCodeBridge' in api) {\n      spec.offset.interpreterCode", """    // A field from the adjacent ArtMethod must never be accepted as this method.
    if (accessFlagsOffset < 4 || accessFlagsOffset >= jniCodeOffset ||
        jniCodeOffset % pointerSize !== 0 || quickCodeOffset + pointerSize > size) {
      throw new Error('TRIPODER_ART_METHOD_LAYOUT_INVALID:' + JSON.stringify(spec));
    }
    let legacyAccessFlagsOffset = null;
    const legacyMask = ~(kAccFastInterpreterToInterpreterInvoke | kAccPublicApi | kAccNterpInvokeFastPathFlag) >>> 0;
    for (let offset = 0; offset < 64; offset += 4) {
      if ((getElapsedCpuTime.add(offset).readU32() & legacyMask) === expectedAccessFlags) {
        legacyAccessFlagsOffset = offset;
        break;
      }
    }
    globalThis.__tripoderArtMethodEvidence = { apiLevel, arch: Process.arch,
      ...spec, probeFlags: getElapsedCpuTime.add(accessFlagsOffset).readU32(), legacyAccessFlagsOffset,
      upstreamFix: '302a588881b40cba300bcf3acfd35deeb3888ee5' };
    // Expose only a validated existing native method entry, without replacing its
    // ArtMethod or creating another managed frame for CallerSensitive methods.
    globalThis.__tripoderArtNativeEntry = methodId => {
      if (apiLevel !== 37 || Process.arch !== 'arm64') throw new Error('ART_NATIVE_PLATFORM_UNSUPPORTED');
      const method = unwrapMethodId(methodId);
      const flags = method.add(spec.offset.accessFlags).readU32();
      if ((flags & kAccNative) === 0) throw new Error('ART_NATIVE_METHOD_REQUIRED');
      const entry = method.add(spec.offset.jniCode).readPointer();
      const module = Process.findModuleByAddress(entry), range = Process.findRangeByAddress(entry);
      if (module === null || module.name !== 'libart.so' || range === null || !range.protection.includes('x'))
        throw new Error('ART_NATIVE_ENTRY_NOT_IN_ART');
      return { entry, flags, offset: entry.sub(module.base).toString() };
    };

    if ('artInterpreterToCompiledCodeBridge' in api) {
      spec.offset.interpreterCode""")
    receipt = {"version": "7.0.13", "extension": "explicit-method-only-model-v1", "upstreamSourceSha256": PINNED,
               "artFix": "https://github.com/frida/frida-java-bridge/commit/302a588881b40cba300bcf3acfd35deeb3888ee5",
               "layoutValidation": "reject-flags-outside-current-method-v1",
               "selectiveDeoptimization": "api37-native-suspend-all-exclusive-mutator-v1",
               "callerSensitiveFields": "native-interceptor-original-managed-frame-v1",
               "generatedSha256": {name: hashlib.sha256((target / name).read_bytes()).hexdigest() for name in PINNED},
               "license": "LGPL-2.0 WITH WxWindows-exception-3.1", "source": "https://github.com/frida/frida-java-bridge"}
    return target / "index.js", receipt
