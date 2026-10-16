/* This fixed JNI entry point has no host filesystem, activity or service bridge. */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <unistd.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

extern void qemu_init(int argc, char **argv, char **envp);
extern void qemu_main_loop(void);
extern void qemu_cleanup(void);
extern void tripoder_coroutine_selftest(void);
extern void tripoder_crash_diagnostics_init(void);
extern void tripoder_tcg_branch_selftest(void);
extern void tripoder_tcg_simd_selftest(void);
extern void tripoder_panel_edid_selftest(void);
extern char **environ;
static atomic_bool started;

JNIEXPORT jstring JNICALL Java_dev_tripoder_vmprobe_NativeEngine_start(
        JNIEnv *env, jclass type, jobjectArray arguments) {
    (void)type;
    if (atomic_exchange(&started, true)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                        "The native engine can start only once per process");
        return NULL;
    }
    int argc = (*env)->GetArrayLength(env, arguments);
    char **argv = calloc((size_t)argc + 1, sizeof(*argv));
    if (!argv) abort();
    for (int i = 0; i < argc; ++i) {
        jstring item = (*env)->GetObjectArrayElement(env, arguments, i);
        const char *value = (*env)->GetStringUTFChars(env, item, NULL);
        if (!value) abort();
        argv[i] = strdup(value);
        (*env)->ReleaseStringUTFChars(env, item, value);
        (*env)->DeleteLocalRef(env, item);
        if (!argv[i]) abort();
    }
    fprintf(stderr, "Tripoder source engine: uid=%d tid=%d PACA=%d PACG=%d; thread-local coroutine stacks\n",
            getuid(), gettid(), !!(getauxval(AT_HWCAP) & HWCAP_PACA),
            !!(getauxval(AT_HWCAP) & HWCAP_PACG));
    tripoder_crash_diagnostics_init();
    tripoder_coroutine_selftest();
    tripoder_tcg_branch_selftest();
    tripoder_tcg_simd_selftest();
    tripoder_panel_edid_selftest();
    qemu_init(argc, argv, environ);
    qemu_main_loop();
    qemu_cleanup();
    for (int i = 0; i < argc; ++i) free(argv[i]);
    free(argv);
    return (*env)->NewStringUTF(env, "Native engine stopped");
}
