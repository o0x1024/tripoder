#ifndef TP_ART_ACCESS_H
#define TP_ART_ACCESS_H
#include <stdbool.h>
#include <stdint.h>
#include <jni.h>
/* Fixed-device ART research, callable only from an ordinary JNI transition.
 * Caller must hold Java references to methods/classes and no ART internal lock.
 * No JNI, allocation, callbacks or scripts while the mutator lock is exclusive. */
struct tp_art_access {
    uintptr_t base;
    void *(*current)(void);
    void (*suspend)(void *, const char *, bool);
    void (*resume)(void *);
};
int tp_art_access_bind(struct tp_art_access *result);
int tp_art_validate_code(const struct tp_art_access *access,uintptr_t rva,const void *bytes,unsigned size);
int tp_art_readable(uintptr_t address, unsigned bytes);
uintptr_t tp_art_method_address(JNIEnv *env, jobject method);
struct tp_art_method_view { uint32_t owner, flags, dex_index, indices; uintptr_t data, quick; };
/* One JNI method identity/mapping check and one mutator snapshot. ArtMethod
 * metadata is stable for the Method/Class/loader roots the caller must retain. */
int tp_art_method_observe(JNIEnv *env,jobject method,uintptr_t *address,struct tp_art_method_view *result);
int tp_art_method_snapshot(JNIEnv *env, jobject method, struct tp_art_method_view *result);
struct tp_art_managed_access {
    struct tp_art_access access;
    void (*jit_suspend)(void *);
    void (*jit_resume)(void *);
    void (*copy)(void *, void *, unsigned);
    uintptr_t jni_bridge, interpreter_bridge;
};
int tp_art_managed_bind(struct tp_art_managed_access *result);
struct tp_art_deopt_access {
    struct tp_art_managed_access managed;
    void *instrumentation;
    void (*deopt)(void *, void *);
    bool (*is_deopt)(void *, void *);
};
int tp_art_deopt_bind(struct tp_art_deopt_access *result);
#endif
