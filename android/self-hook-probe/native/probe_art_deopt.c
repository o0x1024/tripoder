#include "art_access.h"
#include <android/set_abort_message.h>
#include <stdlib.h>

static jobject caller_roots[64];
static bool attempted;
static void fatal(const char *reason) { android_set_abort_message(reason); abort(); }

JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_ArtManagedProbe_deoptimizeCallers(JNIEnv *env, jclass cls, jobjectArray methods) {
    if (attempted || !methods) return 0;
    attempted = true;
    jsize count = (*env)->GetArrayLength(env, methods);
    if (count <= 0 || count > 64) return 0;
    struct tp_art_deopt_access art;
    if (!tp_art_deopt_bind(&art) || !art.managed.access.current()) return 0;
    jclass class_type = (*env)->FindClass(env, "java/lang/Class");
    jclass method_type = (*env)->FindClass(env, "java/lang/reflect/Method");
    if (!class_type || !method_type) return 0;
    jmethodID loader_id = (*env)->GetMethodID(env, class_type, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jmethodID owner_id = (*env)->GetMethodID(env, method_type, "getDeclaringClass", "()Ljava/lang/Class;");
    if (!loader_id || !owner_id) return 0;
    jobject expected_loader = (*env)->CallObjectMethod(env, cls, loader_id);
    if (!expected_loader || (*env)->ExceptionCheck(env)) return 0;
    uintptr_t addresses[64] = {0};
    bool prepared = true;
    for (jsize i = 0; i < count && prepared; i++) {
        jobject method = (*env)->GetObjectArrayElement(env, methods, i);
        if (!method || !(*env)->IsInstanceOf(env, method, method_type)) { prepared = false; break; }
        jobject owner = (*env)->CallObjectMethod(env, method, owner_id);
        if (!owner || (*env)->ExceptionCheck(env)) { prepared = false; break; }
        jobject loader = (*env)->CallObjectMethod(env, owner, loader_id);
        if ((*env)->ExceptionCheck(env) || !(*env)->IsSameObject(env, loader, expected_loader)) { prepared = false; break; }
        addresses[i] = tp_art_method_address(env, method);
        struct tp_art_method_view view;
        if (!addresses[i] || !tp_art_method_snapshot(env, method, &view) || (view.flags & (0x100 | 0x400))) { prepared = false; break; }
        for (jsize j = 0; j < i; j++) if (addresses[i] == addresses[j]) prepared = false;
        if (prepared) caller_roots[i] = (*env)->NewGlobalRef(env, method);
        if (!caller_roots[i]) prepared = false;
        (*env)->DeleteLocalRef(env, method); (*env)->DeleteLocalRef(env, owner); (*env)->DeleteLocalRef(env, loader);
    }
    if (!prepared) {
        for (jsize i = 0; i < count; i++) if (caller_roots[i]) { (*env)->DeleteGlobalRef(env, caller_roots[i]); caller_roots[i] = NULL; }
        return 0;
    }
    char jit_scope = 0, mutator_scope = 0;
    art.managed.jit_suspend(&jit_scope);
    art.managed.access.suspend(&mutator_scope, "tripoder-owned-caller-deopt", false);
    for (jsize i = 0; i < count; i++)
        if (art.is_deopt(art.instrumentation, (void *)addresses[i])) prepared = false;
    bool valid = true;
    if (prepared) for (jsize i = 0; i < count; i++) {
        art.deopt(art.instrumentation, (void *)addresses[i]);
        valid = valid && art.is_deopt(art.instrumentation, (void *)addresses[i])
            && ((struct tp_art_method_view *)addresses[i])->quick == art.managed.interpreter_bridge;
    }
    art.managed.access.resume(&mutator_scope);
    art.managed.jit_resume(&jit_scope);
    if (!valid) fatal("TP_OWNED_CALLER_DEOPT_STATE_MISMATCH");
    if (!prepared) {
        for (jsize i = 0; i < count; i++) { (*env)->DeleteGlobalRef(env, caller_roots[i]); caller_roots[i] = NULL; }
        return 0;
    }
    // The deoptimized-method registry retains a raw method pointer. Keep its
    // owning class rooted until process exit; no unload/reclaim claim.
    return count;
}
