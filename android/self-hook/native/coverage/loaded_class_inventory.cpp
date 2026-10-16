extern "C" {
#include "../art_access.h"
}
#include "../art_framework_contract.h"
#include "loaded_class_contract.h"
#include "loaded_class_buffer.hpp"
#include <android/set_abort_message.h>
#include <atomic>
#include <cstring>

namespace {
tp_art_access access{};
tp_loaded::RootScope *pool = nullptr;
void *linker = nullptr, *prepared_thread = nullptr;
uintptr_t (*decode)(void *, jobject) = nullptr;
void (*visit)(void *, tp_loaded::ClassVisitorAbi *) = nullptr;
jclass class_root = nullptr;
std::atomic<bool> ready{false}, attempted{false};
std::atomic<unsigned> reservation{0}; // 0 idle, 1 preparing, 2 prepared, 3 capturing.
std::atomic<const char *> stage{"NOT_STARTED"};
uint64_t sequence = 0;

[[noreturn]] void fatal(const char *reason) {
    android_set_abort_message(reason); std::abort();
}
class ScopeLease {
public:
    ScopeLease(void *thread, tp_loaded::RootScope &scope) : scope_(scope),
        top_(reinterpret_cast<uintptr_t *>(static_cast<char *>(thread) + 0x118)) {
        scope_.link = *top_;
        *top_ = reinterpret_cast<uintptr_t>(&scope_);
    }
    ~ScopeLease() {
        if (*top_ != reinterpret_cast<uintptr_t>(&scope_)) fatal("TP_LOADED_SCOPE_OWNERSHIP");
        *top_ = scope_.link;
    }
private:
    tp_loaded::RootScope &scope_;
    uintptr_t *top_;
};

/* This function is installed only on the verified @FastNative method, so ART
 * holds shared mutator access. The class visitor uses only the bounded buffer;
 * all JNI calls are strictly before VisitClasses or after it released its lock. */
jint capture_fast(JNIEnv *env, jclass, jobjectArray output, jlongArray receipt) {
    if (!ready.load(std::memory_order_acquire)) return 2;
    unsigned expected = 2;
    if (!reservation.compare_exchange_strong(expected, 3, std::memory_order_acquire)) return 2;
    void *thread = access.current();
    uint32_t count = 0, seen = 0;
    int status = 2;
    bool prepared = thread && thread == prepared_thread && output && receipt;
    if (prepared) prepared = env->GetArrayLength(output) == static_cast<jsize>(pool->capacity)
        && env->GetArrayLength(receipt) == 6 && env->EnsureLocalCapacity(4) == JNI_OK;
    if (prepared) {
        ScopeLease scope(thread, *pool);
        stage.store("HANDLE_CALIBRATION");
        // Independently round-trip a known strong Class through the newly
        // registered scope before trusting this pool for arbitrary classes.
        uintptr_t known = decode(thread, class_root);
        tp_loaded::BoundedVisitor calibration(*pool);
        bool valid = calibration(tp_loaded::ClassRef{known});
        jobject local = valid ? env->NewLocalRef(reinterpret_cast<jobject>(const_cast<uint32_t *>(&pool->refs[0]))) : nullptr;
        valid = local && !env->ExceptionCheck() && env->IsSameObject(local, class_root);
        if (local) env->DeleteLocalRef(local);
        pool->size = 0; pool->refs[0] = 0;
        if (valid) {
            tp_loaded::BoundedVisitor visitor(*pool);
            stage.store("VISIT_CLASSES");
            visit(linker, &visitor);
            seen = visitor.seen;
            status = visitor.invalid_reference ? 2 : visitor.exhausted ? 1 : 0;
            if (status == 0) {
                stage.store("PUBLISH_CLASS_ROOTS");
                for (uint32_t i = 0; i < pool->size; i++) {
                    // JNI may safepoint here. The remaining entries are still
                    // registered GC roots, and are reread at each iteration.
                    local = env->NewLocalRef(reinterpret_cast<jobject>(const_cast<uint32_t *>(&pool->refs[i])));
                    if (!local || env->ExceptionCheck()) { status = 2; break; }
                    env->SetObjectArrayElement(output, static_cast<jsize>(i), local);
                    env->DeleteLocalRef(local);
                    if (env->ExceptionCheck()) { status = 2; break; }
                    count++;
                }
            }
            stage.store(status == 0 ? "ENUMERATION_COMPLETE" : status == 1 ? "BUDGET_EXHAUSTED" : "ENUMERATION_REJECTED");
        }
    } else stage.store("FAST_ARGUMENTS_REJECTED");
    // Scope is popped only after publication/abandonment; never let its raw
    // Class references escape to Java or use them after this point.
    jlong stats[] = {1, static_cast<jlong>(sequence), static_cast<jlong>(pool->capacity),
        static_cast<jlong>(seen), static_cast<jlong>(status == 0 ? count : 0), status};
    if (receipt && !env->ExceptionCheck() && env->GetArrayLength(receipt) == 6)
        env->SetLongArrayRegion(receipt, 0, 6, stats);
    prepared_thread = nullptr;
    reservation.store(0, std::memory_order_release);
    return status;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tripoder_selfhook_coverage_LoadedClassInventory_bindNative(JNIEnv *env, jclass type, jobject fast) {
    if (attempted.exchange(true)) return JNI_FALSE;
    stage.store("ART_ACCESS_CONTRACT");
    if (!tp_art_access_bind(&access)) return JNI_FALSE;
#define CHECK_BODY(name, NAME) \
    if (!tp_art_validate_code(&access, TP_LOADED_##NAME##_RVA, tp_loaded_##name##_bytes, \
        static_cast<unsigned>(sizeof(tp_loaded_##name##_bytes)))) { stage.store(#NAME); return JNI_FALSE; }
    CHECK_BODY(visit, VISIT); CHECK_BODY(internal, INTERNAL); CHECK_BODY(loader_visit, LOADER_VISIT);
    CHECK_BODY(loader_filter, LOADER_FILTER); CHECK_BODY(scope_roots, SCOPE_ROOTS);
#undef CHECK_BODY
    if (!tp_art_validate_code(&access, TP_FRAMEWORK_DECODE_OBJECT_RVA, tp_framework_decode_object_bytes,
        static_cast<unsigned>(sizeof(tp_framework_decode_object_bytes)))) return JNI_FALSE;
    if (!tp_art_validate_code(&access, TP_FRAMEWORK_METHOD_ID_LAYOUT_RVA, tp_framework_method_id_layout_bytes,
        static_cast<unsigned>(sizeof(tp_framework_method_id_layout_bytes)))
        || !tp_art_validate_code(&access, TP_FRAMEWORK_METHOD_LAYOUT_RVA, tp_framework_method_layout_bytes,
        static_cast<unsigned>(sizeof(tp_framework_method_layout_bytes)))) return JNI_FALSE;
    stage.store("RUNTIME_CLASS_LINKER");
    uintptr_t slot = access.base + TP_FRAMEWORK_RUNTIME_RVA;
    if (!tp_art_readable(slot, 8)) return JNI_FALSE;
    uintptr_t runtime = *reinterpret_cast<uintptr_t *>(slot);
    if (!runtime || !tp_art_readable(runtime + 0x270, 8)) return JNI_FALSE;
    linker = *reinterpret_cast<void **>(runtime + 0x270);
    if (!linker || !tp_art_readable(reinterpret_cast<uintptr_t>(linker) + 0x170, 4)
        || *reinterpret_cast<uint32_t *>(static_cast<char *>(linker) + 0x170) != 8) return JNI_FALSE;
    decode = reinterpret_cast<uintptr_t (*)(void *, jobject)>(access.base + TP_FRAMEWORK_DECODE_OBJECT_RVA);
    visit = reinterpret_cast<void (*)(void *, tp_loaded::ClassVisitorAbi *)>(access.base + TP_LOADED_VISIT_RVA);
    stage.store("FIXED_ROOT_POOL");
    pool = static_cast<tp_loaded::RootScope *>(std::calloc(1, sizeof(tp_loaded::RootScope)));
    if (!pool) return JNI_FALSE;
    jclass klass = env->FindClass("java/lang/Class");
    class_root = klass ? static_cast<jclass>(env->NewGlobalRef(klass)) : nullptr;
    if (klass) env->DeleteLocalRef(klass);
    if (!class_root) return JNI_FALSE;
    tp_art_method_view view{};
    if (!fast || !tp_art_method_snapshot(env, fast, &view) || (view.flags & 0x108u) != 0x108u) return JNI_FALSE;
    JNINativeMethod method{const_cast<char *>("captureFast"), const_cast<char *>("([Ljava/lang/Class;[J)I"), reinterpret_cast<void *>(capture_fast)};
    stage.store("REGISTER_FAST_ENUMERATOR");
    if (env->RegisterNatives(type, &method, 1) != JNI_OK || !tp_art_method_snapshot(env, fast, &view)
        || (view.flags & 0x80108u) != 0x80108u) return JNI_FALSE;
    stage.store("BOUND"); ready.store(true, std::memory_order_release); return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_tripoder_selfhook_coverage_LoadedClassInventory_prepareNative(JNIEnv *, jclass, jint capacity) {
    if (!ready.load(std::memory_order_acquire) || capacity < 1 || capacity > static_cast<jint>(tp_loaded::max_classes)) return JNI_FALSE;
    unsigned expected = 0;
    if (!reservation.compare_exchange_strong(expected, 1, std::memory_order_acquire)) return JNI_FALSE;
    void *thread = access.current();
    if (!thread || !tp_art_readable(reinterpret_cast<uintptr_t>(thread) + 0x118, 8)
        || sequence == INT64_MAX) { reservation.store(0, std::memory_order_release); return JNI_FALSE; }
    std::memset(pool, 0, sizeof(*pool));
    pool->capacity = static_cast<uint32_t>(capacity);
    prepared_thread = thread; sequence++;
    stage.store("PREPARED"); reservation.store(2, std::memory_order_release); return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_selfhook_coverage_LoadedClassInventory_stageNative(JNIEnv *env, jclass) {
    return env->NewStringUTF(stage.load());
}
