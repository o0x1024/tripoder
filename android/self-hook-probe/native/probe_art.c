#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static const char *art_path = "/apex/com.android.art/lib64/libart.so";
static int find_art(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    if (strcmp(info->dlpi_name, art_path) != 0) return 0;
    *(uintptr_t *)data = info->dlpi_addr;
    return 1;
}
static void escape(const char *input, char *output, size_t bytes) {
    size_t at = 0;
    if (input != NULL) for (const unsigned char *p = (const unsigned char *)input; *p && at + 7 < bytes; p++) {
        if (*p == '"' || *p == '\\') { output[at++] = '\\'; output[at++] = (char)*p; }
        else if (*p < 32) at += (size_t)snprintf(output + at, bytes - at, "\\u%04x", *p);
        else output[at++] = (char)*p;
    }
    output[at] = 0;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_discover(JNIEnv *env, jclass cls) {
    (void)cls;
    const char *names[] = {
        "_ZN3art7Runtime9instance_E", "_ZN3art6Thread14CurrentFromGdbEv",
        "_ZN3art16ScopedSuspendAllC1EPKcb", "_ZN3art16ScopedSuspendAllD1Ev",
        "_ZN3art3jni12JniIdManager14DecodeMethodIdEP10_jmethodID",
        "_ZN3art15instrumentation15Instrumentation10DeoptimizeEPNS_9ArtMethodE"
    };
    uintptr_t base = 0;
    dl_iterate_phdr(find_art, &base);
    dlerror();
    /* This is an observation of the app namespace, not a loader bypass. */
    void *handle = dlopen(art_path, RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    char error[2048];
    escape(dlerror(), error, sizeof(error));
    char report[8192];
    int count = snprintf(report, sizeof(report), "{\"mappedBase\":\"%lx\",\"namespaceHandle\":%s,\"namespaceError\":\"%s\",\"symbols\":[",
                         (unsigned long)base, handle ? "true" : "false", error);
    size_t at = count > 0 ? (size_t)count : sizeof(report);
    for (unsigned i = 0; i < sizeof(names) / sizeof(names[0]); i++) {
        void *global = dlsym(RTLD_DEFAULT, names[i]);
        void *local = handle ? dlsym(handle, names[i]) : NULL;
        Dl_info location = {0};
        int source = global != NULL && dladdr(global, &location) && strcmp(location.dli_fname, art_path) == 0;
        if (at >= sizeof(report)) break;
        count = snprintf(report + at, sizeof(report) - at,
            "%s{\"name\":\"%s\",\"defaultAddress\":\"%lx\",\"handleAddress\":\"%lx\",\"defaultIsArt\":%s}",
            i ? "," : "", names[i], (unsigned long)global, (unsigned long)local, source ? "true" : "false");
        if (count < 0) { at = sizeof(report); break; }
        at += (size_t)count;
    }
    if (handle != NULL && dlclose(handle) != 0) return NULL;
    if (at + 3 >= sizeof(report)) return NULL;
    memcpy(report + at, "]}", 3);
    return (*env)->NewStringUTF(env, report);
}
JNIEXPORT jlong JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_methodId(JNIEnv *env, jclass cls, jobject method) {
    (void)cls;
    return (jlong)(uintptr_t)(*env)->FromReflectedMethod(env, method); /* Opaque, never dereferenced. */
}
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_reflected(
        JNIEnv *env, jclass cls, jclass owner, jobject method, jboolean is_static) {
    (void)cls;
    jmethodID id = (*env)->FromReflectedMethod(env, method);
    return id == NULL ? NULL : (*env)->ToReflectedMethod(env, owner, id, is_static);
}
JNIEXPORT jlong JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_reflectedArtMethod(JNIEnv *env, jclass cls, jobject method) {
    (void)cls;
    jclass executable = (*env)->FindClass(env, "java/lang/reflect/Executable");
    if (executable == NULL) return 0;
    jfieldID field = (*env)->GetFieldID(env, executable, "artMethod", "J");
    return field == NULL ? 0 : (*env)->GetLongField(env, method, field);
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_callInt(
        JNIEnv *env, jclass cls, jclass owner, jobject receiver, jobject method, jint value) {
    (void)cls;
    jmethodID id = (*env)->FromReflectedMethod(env, method);
    if (id == NULL) return 0;
    jvalue argument = {.i = value};
    return receiver == NULL ? (*env)->CallStaticIntMethodA(env, owner, id, &argument)
                            : (*env)->CallIntMethodA(env, receiver, id, &argument);
}
JNIEXPORT jlong JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_callLong(JNIEnv *env, jclass cls, jclass owner, jobject method) {
    (void)cls;
    jmethodID id = (*env)->FromReflectedMethod(env, method);
    return id == NULL ? 0 : (*env)->CallStaticLongMethodA(env, owner, id, NULL);
}
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_callObject(
        JNIEnv *env, jclass cls, jclass owner, jobject method, jobject value) {
    (void)cls;
    jmethodID id = (*env)->FromReflectedMethod(env, method);
    if (id == NULL) return NULL;
    jvalue argument = {.l = value};
    return (*env)->CallStaticObjectMethodA(env, owner, id, &argument);
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_callThrow(
        JNIEnv *env, jclass cls, jclass owner, jobject method, jobject value) {
    (void)cls;
    jmethodID id = (*env)->FromReflectedMethod(env, method);
    if (id == NULL) return;
    jvalue argument = {.l = value};
    (*env)->CallStaticVoidMethodA(env, owner, id, &argument); /* Preserve pending original exception. */
}
