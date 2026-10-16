#include "../art_access.h"
#include "../art_framework_contract.h"
#include "../art_storage_contract.h"
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

/* Ordinary JNI only. Caller Method/Class arrays retain strong Java references.
 * Preflight does JNI and mapping I/O; the critical window only reads metadata.
 * It never changes methods, compiles/deoptimizes code, or dereferences saved
 * object tokens after mutators resume. This is not a loaded-class enumerator. */
enum { MAX_METHODS = 65, HEADER_WORDS = 4, METHOD_WORDS = 8 };
static _Atomic uint64_t sequence;
static uint64_t next_sequence(void) {
    uint64_t value = atomic_load_explicit(&sequence, memory_order_relaxed);
    do {
        if (value >= INT64_MAX) return 0;
    } while (!atomic_compare_exchange_weak_explicit(&sequence, &value, value + 1,
        memory_order_relaxed, memory_order_relaxed));
    return value + 1;
}

static void reject(JNIEnv *env, const char *reason) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (error) {
        (*env)->ThrowNew(env, error, reason);
        (*env)->DeleteLocalRef(env, error);
    }
}

JNIEXPORT jlongArray JNICALL
Java_dev_tripoder_selfhook_coverage_MethodObservation_captureNative(JNIEnv *env, jclass unused,
    jobjectArray methods, jobjectArray owners) {
    (void)unused;
    if (!methods || !owners) { reject(env, "COVERAGE_OBSERVATION_ARRAYS"); return NULL; }
    jsize count = (*env)->GetArrayLength(env, methods);
    if (count < 1 || count > MAX_METHODS || (*env)->GetArrayLength(env, owners) != count) {
        reject(env, "COVERAGE_OBSERVATION_COUNT"); return NULL;
    }
    if ((*env)->EnsureLocalCapacity(env, count + 4) != JNI_OK) return NULL;
    struct tp_art_managed_access art;
    if (!tp_art_managed_bind(&art) || !tp_art_validate_code(&art.access,
        TP_FRAMEWORK_DECODE_OBJECT_RVA, tp_framework_decode_object_bytes, sizeof(tp_framework_decode_object_bytes))
        || !tp_art_validate_code(&art.access, TP_STORAGE_READ_ROOT_RVA, tp_storage_read_root_bytes, sizeof(tp_storage_read_root_bytes))
        || !tp_art_validate_code(&art.access, TP_STORAGE_MARK_ROOT_RVA, tp_storage_mark_root_bytes, sizeof(tp_storage_mark_root_bytes))) {
        reject(env, "COVERAGE_OBSERVATION_ART_CONTRACT"); return NULL;
    }
    void *thread = art.access.current();
    if (!thread) { reject(env, "COVERAGE_OBSERVATION_THREAD"); return NULL; }
    uintptr_t (*decode)(void *, jobject) = (uintptr_t (*)(void *, jobject))
        (art.access.base + TP_FRAMEWORK_DECODE_OBJECT_RVA);
    uintptr_t (*read_root)(const uint32_t *) = (uintptr_t (*)(const uint32_t *))(art.access.base + TP_STORAGE_READ_ROOT_RVA);
    uintptr_t addresses[MAX_METHODS] = {0};
    jobject roots[MAX_METHODS] = {0};
    jlong payload[HEADER_WORDS + MAX_METHODS * METHOD_WORDS] = {0};
    jlongArray result = NULL;
    const char *failure = NULL;
    for (jsize i = 0; i < count; i++) {
        jobject method = (*env)->GetObjectArrayElement(env, methods, i);
        roots[i] = (*env)->GetObjectArrayElement(env, owners, i);
        if (method && roots[i]) addresses[i] = tp_art_method_address(env, method);
        if (method) (*env)->DeleteLocalRef(env, method);
        if (!addresses[i] || !roots[i] || (*env)->ExceptionCheck(env)) {
            failure = "COVERAGE_OBSERVATION_PREFLIGHT"; goto done;
        }
        for (jsize j = 0; j < i; j++) if (addresses[j] == addresses[i]) {
            failure = "COVERAGE_OBSERVATION_DUPLICATE_METHOD"; goto done;
        }
    }
    // Allocate Java output before taking any ART lock.
    result = (*env)->NewLongArray(env, HEADER_WORDS + count * METHOD_WORDS);
    if (!result) goto done;
    char jit = 0, mutators = 0;
    art.jit_suspend(&jit);
    art.access.suspend(&mutators, "tripoder-owned-method-observation", false);
    for (jsize i = 0; i < count; i++) {
        const volatile struct tp_art_method_view *method = (const volatile void *)addresses[i];
        uintptr_t owner = decode(thread, roots[i]);
        jsize at = HEADER_WORDS + i * METHOD_WORDS;
        payload[at] = (jlong)addresses[i];
        uintptr_t declaring = read_root((const uint32_t *)&method->owner);
        payload[at + 1] = (jlong)declaring;
        payload[at + 2] = method->flags;
        payload[at + 3] = method->dex_index;
        payload[at + 4] = method->indices;
        payload[at + 5] = (jlong)method->data;
        payload[at + 6] = (jlong)method->quick;
        payload[at + 7] = (jlong)owner;
        if (!owner || owner > UINT32_MAX || declaring != owner) failure = "COVERAGE_OBSERVATION_OWNER_MISMATCH";
    }
    // Sequence belongs to this snapshot's lock interval, not its return order.
    uint64_t observed = next_sequence();
    art.access.resume(&mutators);
    art.jit_resume(&jit);
    if (observed == 0 || observed > INT64_MAX) failure = "COVERAGE_OBSERVATION_SEQUENCE_EXHAUSTED";
    if (!failure) {
        payload[0] = INT64_C(0x5450434f56455231);
        payload[1] = 1;
        payload[2] = (jlong)observed;
        payload[3] = count;
        (*env)->SetLongArrayRegion(env, result, 0, HEADER_WORDS + count * METHOD_WORDS, payload);
    }
done:
    for (jsize i = 0; i < count; i++) if (roots[i]) (*env)->DeleteLocalRef(env, roots[i]);
    if (failure) reject(env, failure);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}
