#include "art_access.h"
#include "art_method_storage.h"
#include <android/set_abort_message.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>

/* Two exact owned int(int) methods. Backups use the shared ClassExt root
 * protocol, outside all declared method arrays. No unload/reclamation claim. */
struct owned_slot {
    struct tp_art_method_view *target, *backup;
    jclass original_class;
    jobject target_ref;
    jmethodID original_id;
    uint32_t saved_flags;
    void *replacement;
};
static struct tp_art_managed_access art;
static struct owned_slot slots[2];
static bool attempted;
static _Atomic bool ready, enabled;
static _Atomic unsigned callbacks, originals;
static unsigned transitions;
static void fatal(const char *reason) { android_set_abort_message(reason); abort(); }

static jint invoke_original(JNIEnv *env, struct owned_slot *slot, jint value) {
    jvalue args[] = {{.i = value}};
    return (*env)->CallStaticIntMethodA(env, slot->original_class, slot->original_id, args);
}
static jint replace(JNIEnv *env, unsigned index, jint value) {
    if (!atomic_load_explicit(&ready, memory_order_acquire)) fatal("TP_MANAGED_NOT_READY");
    bool apply = atomic_load_explicit(&enabled, memory_order_acquire);
    atomic_fetch_add(&callbacks, 1);
    atomic_fetch_add(&originals, 1);
    jint result = invoke_original(env, &slots[index], value);
    if ((*env)->ExceptionCheck(env)) return 0;
    return (jint)((uint32_t)result + (apply ? 100u : 0u));
}
static jint replacement(JNIEnv *env, jclass owner, jint value) { (void)owner; return replace(env, 0, value); }
static jint inline_replacement(JNIEnv *env, jclass owner, jint value) { (void)owner; return replace(env, 1, value); }

static bool prepare_slot(JNIEnv *env, struct owned_slot *slot, const char *class_name,
                         const char *method_name, void *callback) {
    jclass owner = (*env)->FindClass(env, class_name);
    if (!owner) return false;
    jmethodID method_id = (*env)->GetStaticMethodID(env, owner, method_name, "(I)I");
    if (!method_id) return false;
    jobject method = (*env)->ToReflectedMethod(env, owner, method_id, JNI_TRUE);
    if (!method) return false;
    slot->target = (void *)tp_art_method_address(env, method);
    slot->backup = tp_art_backup_lookup(slot->target);
    slot->original_id = (jmethodID)slot->backup; // Pinned kPointer ID contract.
    struct tp_art_method_view before, slot_before;
    if (!slot->target || !slot->backup || slot->target == slot->backup
        || !tp_art_method_snapshot(env, method, &before)
        || !tp_art_backup_snapshot(slot->target, &slot_before)) return false;
    if ((before.flags & 0xffff) != 9 || (before.flags & ~0x10b8ffffu)
        || !before.data || (slot_before.flags & 0xffff) != 9) return false;
    slot->original_class = (*env)->NewGlobalRef(env, owner);
    slot->target_ref = (*env)->NewGlobalRef(env, method);
    if (!slot->original_class || !slot->target_ref) return false;
    slot->replacement = callback;
    return true;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ArtManagedProbe_prepare(JNIEnv *env, jclass cls) {
    (void)cls;
    if (attempted) return JNI_FALSE;
    attempted = true;
    if (!tp_art_managed_bind(&art) || !art.access.current()) return JNI_FALSE;
    bool prepared = prepare_slot(env, &slots[0], "dev/tripoder/selfhookprobe/ManagedFixture", "compute", replacement)
        && prepare_slot(env, &slots[1], "dev/tripoder/selfhookprobe/InlineFixture", "plusOne", inline_replacement);
    if (!prepared) {
        for (unsigned i = 0; i < 2; i++) {
            struct owned_slot *s = &slots[i];
            if (s->original_class) (*env)->DeleteGlobalRef(env, s->original_class);
            if (s->target_ref) (*env)->DeleteGlobalRef(env, s->target_ref);
            *s = (struct owned_slot){0};
        }
        return JNI_FALSE;
    }
    // Drain JIT BEFORE taking exclusive mutator lock; resume in reverse order.
    char jit_scope = 0, mutator_scope = 0;
    art.jit_suspend(&jit_scope);
    art.access.suspend(&mutator_scope, "tripoder-owned-managed-backup", false);
    for (unsigned i = 0; i < 2; i++) {
        struct owned_slot *s = &slots[i];
        s->saved_flags = s->target->flags;

    }
    art.access.resume(&mutator_scope);
    art.jit_resume(&jit_scope);
    atomic_store_explicit(&ready, true, memory_order_release);
    return JNI_TRUE;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_ArtManagedProbe_callOriginal(JNIEnv *env, jclass cls, jint value) {
    (void)cls;
    if (!atomic_load_explicit(&ready, memory_order_acquire)) fatal("TP_MANAGED_ORIGINAL_NOT_READY");
    return invoke_original(env, &slots[0], value);
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ArtManagedProbe_toggle(JNIEnv *env, jclass cls, jboolean active) {
    (void)env; (void)cls;
    if (!atomic_load(&ready) || atomic_load(&enabled) == (bool)active || transitions >= 32) return JNI_FALSE;
    if (!active) atomic_store_explicit(&enabled, false, memory_order_release);
    char jit_scope = 0, mutator_scope = 0;
    art.jit_suspend(&jit_scope);
    art.access.suspend(&mutator_scope, "tripoder-owned-managed-entry", false);
    for (unsigned i = 0; i < 2; i++) {
        struct owned_slot *s = &slots[i];
        if (active) {
            s->target->flags = (s->saved_flags & 0x1000ffffu) | 0x02000100u;
            s->target->data = (uintptr_t)s->replacement;
            s->target->quick = art.jni_bridge;
        } else {
            // GC has updated this root; an old snapshot owner would be stale.
            art.copy(s->target, s->backup, 8);
            s->target->flags = s->saved_flags;
            s->target->quick = art.interpreter_bridge;
        }
    }
    art.access.resume(&mutator_scope);
    art.jit_resume(&jit_scope);
    if (active) atomic_store_explicit(&enabled, true, memory_order_release);
    transitions++;
    return JNI_TRUE;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_ArtManagedProbe_report(JNIEnv *env, jclass cls) {
    (void)cls;
    char text[1024];
    snprintf(text, sizeof(text), "{\"ready\":%s,\"enabled\":%s,\"transitions\":%u,"
        "\"callbacks\":%u,\"originals\":%u,\"retainedGlobalRefs\":%u,\"slots\":2,\"processLifetimeSlot\":true,"
        "\"savedFlags\":%u,\"target\":\"%lx\",\"backup\":\"%lx\",\"genericJavaHook\":false}",
        atomic_load(&ready) ? "true" : "false", atomic_load(&enabled) ? "true" : "false", transitions,
        atomic_load(&callbacks), atomic_load(&originals), atomic_load(&ready) ? 4u : 0u,
        slots[0].saved_flags, (unsigned long)slots[0].target, (unsigned long)slots[0].backup);
    return (*env)->NewStringUTF(env, text);
}
