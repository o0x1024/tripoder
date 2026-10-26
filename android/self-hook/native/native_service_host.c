#include <android/dlext.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ANativeService (NDK API 37) is five callback slots. The host owns an equivalent record: the entry
 * point stores its callbacks into it through the public setters, and the host reads them back. The
 * slot of each setter is measured with sentinels from the platform's own setters instead of assumed. */
enum { BIND, UNBIND, REBIND, DESTROY, TRIM, SLOTS };
#define RECORD_BYTES 256

typedef void (*create_fn)(void *service);
typedef void *(*bind_fn)(void *service, uint64_t token, const char *action, const char *data);
typedef int (*unbind_fn)(void *service, uint64_t token);
typedef void (*rebind_fn)(void *service, uint64_t token);
typedef void (*destroy_fn)(void *service);
typedef void (*trim_fn)(void *service, int32_t level);
typedef void (*set_fn)(void *service, void *callback);
typedef void *(*to_java_fn)(JNIEnv *env, void *binder);
typedef void (*dec_strong_fn)(void *binder);

struct host {
    void **record;
    size_t slot[SLOTS];
    void *library;
};

static const char *const setters[SLOTS] = {"ANativeService_setOnBindCallback", "ANativeService_setOnUnbindCallback",
    "ANativeService_setOnRebindCallback", "ANativeService_setOnDestroyCallback", "ANativeService_setOnTrimMemoryCallback"};

static void fail(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass failure = (*env)->FindClass(env, type);
    if (failure) (*env)->ThrowNew(env, failure, message);
}

/* Measure where each public setter stores its callback, on a scratch record. */
static int measure(struct host *host) {
    void **scratch = calloc(RECORD_BYTES / sizeof(void *), sizeof(void *));
    if (!scratch) return -1;
    int result = 0;
    void *android = dlopen("libandroid.so", RTLD_NOW);
    if (!android) result = -5;
    for (int index = 0; index < SLOTS && !result; index++) {
        set_fn set = (set_fn)dlsym(android, setters[index]);
        if (!set) { result = -2; break; }
        void *sentinel = (void *)(uintptr_t)(0x5eed0000u + (uint32_t)index + 1u);
        memset(scratch, 0, RECORD_BYTES);
        set(scratch, sentinel);
        size_t found = SLOTS * 100, hits = 0;
        for (size_t at = 0; at < RECORD_BYTES / sizeof(void *); at++) if (scratch[at] == sentinel) { found = at; hits++; }
        if (hits != 1 || found >= 16) result = -3; else host->slot[index] = found;
    }
    for (int a = 0; a < SLOTS && !result; a++) for (int b = a + 1; b < SLOTS; b++) if (host->slot[a] == host->slot[b]) result = -4;
    free(scratch);
    return result;
}

JNIEXPORT jlong JNICALL Java_dev_tripoder_selfhook_NativeServiceHost_create(JNIEnv *env, jclass type, jint fd, jlong offset, jstring library, jstring function) {
    (void)type;
    const char *name = (*env)->GetStringUTFChars(env, library, NULL), *entry = (*env)->GetStringUTFChars(env, function, NULL);
    struct host *host = calloc(1, sizeof *host);
    jlong result = 0;
    const char *problem = NULL;
    char message[512];
    if (!name || !entry || !host) problem = "NATIVE_SERVICE_HOST_ALLOCATION";
    int measured = problem ? 0 : measure(host);
    if (!problem && measured) problem = "NATIVE_SERVICE_RECORD_LAYOUT_UNMEASURABLE";
    if (!problem) {
        host->record = calloc(RECORD_BYTES / sizeof(void *), sizeof(void *));
        android_dlextinfo info;
        memset(&info, 0, sizeof info);
        info.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_USE_LIBRARY_FD_OFFSET;
        info.library_fd = fd;
        info.library_fd_offset = (off64_t)offset;
        /* The file was verified by the caller; the load never falls back to another source. */
        host->library = host->record ? android_dlopen_ext(name, RTLD_NOW | RTLD_LOCAL, &info) : NULL;
        if (!host->library) {
            const char *reason = dlerror();
            snprintf(message, sizeof message, "NATIVE_SERVICE_LIBRARY_LOAD_FAILED:%s", reason ? reason : "(none)");
            problem = message;
        }
    }
    create_fn create = NULL;
    if (!problem) {
        create = (create_fn)dlsym(host->library, entry);
        if (!create) problem = "NATIVE_SERVICE_ENTRY_MISSING";
    }
    if (!problem) {
        create(host->record);
        if (!host->record[host->slot[BIND]]) problem = "NATIVE_SERVICE_ONBIND_REQUIRED";
    }
    if (problem) fail(env, "java/lang/IllegalStateException", problem);
    else result = (jlong)(intptr_t)host;
    if (name) (*env)->ReleaseStringUTFChars(env, library, name);
    if (entry) (*env)->ReleaseStringUTFChars(env, function, entry);
    if (problem) { free(host ? host->record : NULL); free(host); }
    return result;
}

JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_NativeServiceHost_bind(JNIEnv *env, jclass type, jlong handle, jlong token, jstring action, jstring data) {
    (void)type;
    struct host *host = (struct host *)(intptr_t)handle;
    const char *a = action ? (*env)->GetStringUTFChars(env, action, NULL) : NULL, *d = data ? (*env)->GetStringUTFChars(env, data, NULL) : NULL;
    void *binder = ((bind_fn)host->record[host->slot[BIND]])(host->record, (uint64_t)token, a, d);
    if (a) (*env)->ReleaseStringUTFChars(env, action, a);
    if (d) (*env)->ReleaseStringUTFChars(env, data, d);
    if (!binder) return NULL;
    /* The platform adopts the returned reference. The Java object takes its own, so ours is released. */
    void *ndk = dlopen("libbinder_ndk.so", RTLD_NOW);
    to_java_fn to_java = ndk ? (to_java_fn)dlsym(ndk, "AIBinder_toJavaBinder") : NULL;
    dec_strong_fn release = ndk ? (dec_strong_fn)dlsym(ndk, "AIBinder_decStrong") : NULL;
    if (!to_java || !release) { fail(env, "java/lang/IllegalStateException", "NATIVE_SERVICE_BINDER_BRIDGE_MISSING"); return NULL; }
    jobject java = (jobject)to_java(env, binder);
    release(binder);
    return java;
}

JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_NativeServiceHost_unbind(JNIEnv *env, jclass type, jlong handle, jlong token) {
    (void)env; (void)type;
    struct host *host = (struct host *)(intptr_t)handle;
    void *callback = host->record[host->slot[UNBIND]];
    return callback && ((unbind_fn)callback)(host->record, (uint64_t)token) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_NativeServiceHost_rebind(JNIEnv *env, jclass type, jlong handle, jlong token) {
    (void)env; (void)type;
    struct host *host = (struct host *)(intptr_t)handle;
    void *callback = host->record[host->slot[REBIND]];
    if (callback) ((rebind_fn)callback)(host->record, (uint64_t)token);
}

JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_NativeServiceHost_destroy(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    struct host *host = (struct host *)(intptr_t)handle;
    void *callback = host->record[host->slot[DESTROY]];
    if (callback) ((destroy_fn)callback)(host->record);
}

JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_NativeServiceHost_trim(JNIEnv *env, jclass type, jlong handle, jint level) {
    (void)env; (void)type;
    struct host *host = (struct host *)(intptr_t)handle;
    void *callback = host->record[host->slot[TRIM]];
    if (callback) ((trim_fn)callback)(host->record, (int32_t)level);
}
