#define _GNU_SOURCE
#include "art_access.h"
#include <dlfcn.h>
#include <android/set_abort_message.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* One process-lifetime research slot. Callback code and its four global roots
 * remain valid after disable, including a pre-entry Native call not yet counted.
 * No reuse/reclamation claim and no arbitrary-signature registration API. */
typedef jobject (*field_get_fn)(JNIEnv *, jobject, jobject);
static field_get_fn original;
static jobject observed_field, replacement_value;
static jclass field_class;
static jobject get_method;
static _Atomic bool enabled;
static _Atomic bool ready;
static _Atomic unsigned calls, replacements, exceptions;
static unsigned transitions;
static uint32_t original_flags;
static uintptr_t original_quick;
static void fatal(const char *message) { android_set_abort_message(message); abort(); }

static jobject field_get(JNIEnv *env, jobject field, jobject object) {
    if (!atomic_load_explicit(&ready, memory_order_acquire)) fatal("TP_ART_CALLBACK_NOT_READY");
    bool apply = atomic_load_explicit(&enabled, memory_order_acquire);
    atomic_fetch_add(&calls, 1);
    jobject result = original(env, field, object);
    if ((*env)->ExceptionCheck(env)) { atomic_fetch_add(&exceptions, 1); return result; }
    if (apply && (*env)->IsSameObject(env, field, observed_field)) {
        atomic_fetch_add(&replacements, 1);
        return (*env)->NewLocalRef(env, replacement_value);
    }
    return result;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_ArtNativeProbe_registeredAdd(JNIEnv *env, jclass cls, jint value) {
    (void)env; (void)cls;
    return value + 1;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ArtNativeProbe_calibrate(JNIEnv *env, jclass cls, jobject method) {
    (void)cls;
    struct tp_art_method_view view;
    return tp_art_method_snapshot(env, method, &view) && (view.flags & 0xffff) == 0x109
        && view.data == (uintptr_t)Java_dev_tripoder_selfhookprobe_ArtNativeProbe_registeredAdd && view.quick != 0;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ArtNativeProbe_prepare(
        JNIEnv *env, jclass cls, jobject field, jobject value) {
    (void)cls;
    if (original != NULL || field == NULL || value == NULL) return JNI_FALSE;
    jclass owner = (*env)->FindClass(env, "java/lang/reflect/Field");
    if (owner == NULL) return JNI_FALSE;
    jmethodID id = (*env)->GetMethodID(env, owner, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (id == NULL) return JNI_FALSE;
    jobject method = (*env)->ToReflectedMethod(env, owner, id, JNI_FALSE);
    if (method == NULL) return JNI_FALSE;
    struct tp_art_method_view view;
    Dl_info location = {0};
    if (!tp_art_method_snapshot(env, method, &view) || !(view.flags & 0x100) || view.data == 0
        || !dladdr((void *)view.data, &location)
        || strcmp(location.dli_fname, "/apex/com.android.art/lib64/libart.so")) return JNI_FALSE;
    observed_field = (*env)->NewGlobalRef(env, field);
    replacement_value = (*env)->NewGlobalRef(env, value);
    field_class = (*env)->NewGlobalRef(env, owner);
    get_method = (*env)->NewGlobalRef(env, method);
    if (observed_field == NULL || replacement_value == NULL || field_class == NULL || get_method == NULL) {
        if (observed_field) (*env)->DeleteGlobalRef(env, observed_field);
        if (replacement_value) (*env)->DeleteGlobalRef(env, replacement_value);
        if (field_class) (*env)->DeleteGlobalRef(env, field_class);
        if (get_method) (*env)->DeleteGlobalRef(env, get_method);
        observed_field = NULL; replacement_value = NULL; field_class = NULL; get_method = NULL;
        return JNI_FALSE;
    }
    original = (field_get_fn)view.data;
    original_flags = view.flags;
    original_quick = view.quick;
    atomic_store_explicit(&ready, true, memory_order_release);
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ArtNativeProbe_toggle(JNIEnv *env, jclass cls, jboolean active) {
    (void)cls;
    if (original == NULL || transitions >= 128 || atomic_load(&enabled) == (bool)active) return JNI_FALSE;
    /* Disabled callbacks that were fetched before rebinding remain safe. A call
     * which already captured true owns that version until it returns. */
    if (!active) atomic_store_explicit(&enabled, false, memory_order_release);
    JNINativeMethod binding = {"get", "(Ljava/lang/Object;)Ljava/lang/Object;", active ? (void *)field_get : (void *)original};
    if ((*env)->RegisterNatives(env, field_class, &binding, 1) != JNI_OK) fatal("TP_ART_REGISTER_NATIVE_FAILED");
    struct tp_art_method_view after;
    if (!tp_art_method_snapshot(env, get_method, &after) || after.data != (uintptr_t)binding.fnPtr
        || after.flags != original_flags || after.quick != original_quick) fatal("TP_ART_REGISTER_NATIVE_STATE_MISMATCH");
    if (active) atomic_store_explicit(&enabled, true, memory_order_release);
    transitions++;
    return JNI_TRUE;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_ArtNativeProbe_report(JNIEnv *env, jclass cls) {
    (void)cls;
    char text[1024];
    snprintf(text, sizeof(text),
        "{\"mechanism\":\"RegisterNatives-exact-Field.get\",\"enabled\":%s,\"transitions\":%u,"
        "\"callbackCalls\":%u,\"replacements\":%u,\"originalExceptions\":%u,\"originalFlags\":%u,"
        "\"original\":\"%lx\",\"originalQuick\":\"%lx\",\"retainedGlobalRefs\":4,"
        "\"processLifetimeSlot\":true,\"genericJavaHook\":false}",
        atomic_load(&enabled) ? "true" : "false", transitions, atomic_load(&calls), atomic_load(&replacements),
        atomic_load(&exceptions), original_flags, (unsigned long)original, (unsigned long)original_quick);
    return (*env)->NewStringUTF(env, text);
}
