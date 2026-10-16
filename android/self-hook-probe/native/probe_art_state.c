#include "art_access.h"
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_ArtProbe_readLockedMethod(
        JNIEnv *env, jclass cls, jobject method) {
    (void)cls;
    struct tp_art_access access;
    if (!tp_art_access_bind(&access)) return (*env)->NewStringUTF(env, "{\"bound\":false}");
    jclass executable = (*env)->FindClass(env, "java/lang/reflect/Executable");
    if (executable == NULL) return NULL;
    jfieldID field = (*env)->GetFieldID(env, executable, "artMethod", "J");
    if (field == NULL) return NULL;
    uintptr_t address = (uintptr_t)(*env)->GetLongField(env, method, field);
    if ((*env)->ExceptionCheck(env)) return NULL;
    if ((address & 7) != 0 || !tp_art_readable(address, 32))
        return (*env)->NewStringUTF(env, "{\"bound\":true,\"methodReadable\":false}");
    void *thread = access.current();
    if (thread == NULL) return (*env)->NewStringUTF(env, "{\"bound\":true,\"threadPresent\":false}");
    uint32_t words[8];
    unsigned cycles = 0;
    /* Disassembly proves this exact ctor/dtor ignores `this` and forwards to
     * Runtime's ThreadList. Ordinary JNI has transitioned this thread to Native.
     * There are no JNI/libc calls inside the exclusive interval. */
    char scope = 0;
    for (unsigned i = 0; i < 10; i++) {
        access.suspend(&scope, "tripoder-owned-art-read", false);
        for (unsigned word = 0; word < 8; word++) words[word] = ((volatile uint32_t *)address)[word];
        access.resume(&scope);
        cycles++;
    }
    char report[1024];
    snprintf(report, sizeof(report),
        "{\"bound\":true,\"methodReadable\":true,\"threadPresent\":true,\"base\":\"%lx\","
        "\"thread\":\"%lx\",\"method\":\"%lx\",\"suspendResumeCycles\":%u,"
        "\"words\":[%u,%u,%u,%u,%u,%u,%u,%u],\"methodMemoryModified\":false}",
        (unsigned long)access.base, (unsigned long)thread, (unsigned long)address, cycles,
        words[0], words[1], words[2], words[3], words[4], words[5], words[6], words[7]);
    return (*env)->NewStringUTF(env, report);
}
