#define _GNU_SOURCE
#include "tripoder_thread_park.h"
#include "park_syscall.h"
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>

struct busy_worker { _Alignas(64) _Atomic unsigned long long calls; _Atomic int tid; };
static struct {
    struct busy_worker workers[4];
    _Atomic bool stop, block_ready, unblock, block_done, churn_ready, stop_churn;
    _Atomic unsigned children, worker_errors;
    unsigned checks, failures, cycles, churn_cycles, churn_rejected, peak_threads, contexts;
    const char *first;
} test;
static void check(bool value, const char *name) {
    test.checks++;
    if (!value) { test.failures++; if (test.first == NULL) test.first = name; }
}
static void *busy_worker(void *raw) {
    struct busy_worker *worker = raw;
    atomic_store(&worker->tid, (int)tp_park_syscall(SYS_gettid, 0, 0, 0, 0, 0, 0));
    while (!atomic_load_explicit(&test.stop, memory_order_relaxed))
        atomic_fetch_add_explicit(&worker->calls, 1, memory_order_relaxed);
    return NULL;
}
static void *blocked_worker(void *unused) {
    (void)unused;
    sigset_t set;
    sigemptyset(&set); sigaddset(&set, TP_PARK_SIGNAL);
    int status = pthread_sigmask(SIG_BLOCK, &set, NULL);
    if (status != 0) atomic_fetch_add(&test.worker_errors, 1);
    atomic_store_explicit(&test.block_ready, true, memory_order_release);
    while (!atomic_load(&test.unblock)) tp_park_sleep(100000);
    if (pthread_sigmask(SIG_UNBLOCK, &set, NULL) != 0) atomic_fetch_add(&test.worker_errors, 1);
    atomic_store(&test.block_done, true);
    return NULL;
}
static void *short_lived_worker(void *unused) {
    (void)unused;
    atomic_fetch_add(&test.children, 1);
    tp_park_sleep(100000);
    return NULL;
}
static void *churn_worker(void *unused) {
    (void)unused;
    atomic_store(&test.churn_ready, true);
    while (!atomic_load(&test.stop_churn)) {
        pthread_t thread;
        if (pthread_create(&thread, NULL, short_lived_worker, NULL) != 0) {
            atomic_fetch_add(&test.worker_errors, 1); break;
        }
        if (pthread_join(thread, NULL) != 0) { atomic_fetch_add(&test.worker_errors, 1); break; }
    }
    return NULL;
}
static bool await_flag(_Atomic bool *flag) {
    uint64_t deadline = tp_park_now() + UINT64_C(2000000000);
    while (!atomic_load(flag)) {
        if (tp_park_now() >= deadline) return false;
        tp_park_sleep(100000);
    }
    return true;
}

/* Called only while parked: no check/log/allocator/pthread/JNI/library operation. */
static bool observe_still(const struct tp_park_snapshot *snapshot) {
    unsigned long long before[4];
    unsigned found = 0;
    for (unsigned i = 0; i < 4; i++) before[i] = atomic_load(&test.workers[i].calls);
    for (unsigned i = 0; i < snapshot->observed; i++) {
        struct tp_park_context context;
        if (tp_park_context(snapshot->epoch, i, &context) != TP_PARK_OK) continue; /* exited identities */
        if (context.pc == 0 || context.sp == 0 || (context.pc & 3) || (context.sp & 15)) return false;
        test.contexts++;
        for (unsigned j = 0; j < 4; j++) if (context.tid == atomic_load(&test.workers[j].tid)) found |= 1u << j;
    }
    tp_park_sleep(2000000);
    for (unsigned i = 0; i < 4; i++) if (before[i] == 0 || before[i] != atomic_load(&test.workers[i].calls)) return false;
    return found == 15;
}
__attribute__((noinline)) static bool workers_progress(void) {
    unsigned long long before[4];
    for (unsigned i = 0; i < 4; i++) before[i] = atomic_load(&test.workers[i].calls);
    uint64_t deadline = tp_park_now() + UINT64_C(100000000);
    for (;;) {
        unsigned advanced = 0;
        for (unsigned i = 0; i < 4; i++) if (atomic_load(&test.workers[i].calls) > before[i]) advanced++;
        if (advanced == 4) break;
        if (tp_park_now() >= deadline) return false;
        tp_park_sleep(100000);
    }
    return true;
}
static bool one_cycle(struct tp_park_snapshot *snapshot, int *last_status) {
    enum tp_park_status status = tp_park_begin(snapshot);
    *last_status = status;
    if (status != TP_PARK_OK) return false;
    /* begin is BUSY before it can reach sigaction; stale end leaves current freeze intact. */
    struct tp_park_snapshot unused;
    bool stable = tp_park_begin(&unused) == TP_PARK_BUSY &&
        tp_park_end(snapshot->epoch + 1) == TP_PARK_STALE && observe_still(snapshot);
    status = tp_park_end(snapshot->epoch);
    *last_status = status;
    if (snapshot->parked > test.peak_threads) test.peak_threads = snapshot->parked;
    if (status != TP_PARK_OK) return false;
    return workers_progress() && stable && snapshot->passes >= 2 && snapshot->parked >= 4 &&
        snapshot->observed == snapshot->parked + snapshot->vanished;
}

JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_ThreadParkProbe_runNative(JNIEnv *env, jclass type) {
    (void)type;
    if (test.checks) return (*env)->NewStringUTF(env, "{\"passed\":false,\"error\":\"REPEATED_RUN\"}");
    struct tp_park_snapshot snapshot = {0}, timeout = {0};
    int last_status = TP_PARK_NOT_READY;
    unsigned made = 0, late_before = 0, late_after = 0;
    bool timeout_rejected = false, late_recovered = false;
    uint64_t churn_mask = 0;
    int churn_tid = 0;
    pthread_t workers[4], blocked, churn;
    bool has_blocked = false, has_churn = false;
    struct sigaction initial, occupied = {0};
    check(sigaction(TP_PARK_SIGNAL, NULL, &initial) == 0 && initial.sa_handler == SIG_DFL, "SIGNAL_AVAILABLE");
    if (test.failures) goto done;
    occupied.sa_handler = SIG_IGN; sigemptyset(&occupied.sa_mask);
    check(sigaction(TP_PARK_SIGNAL, &occupied, NULL) == 0, "OWN_SIGNAL_COLLISION_FIXTURE");
    if (test.failures) goto done;
    check(tp_park_init() == TP_PARK_SIGNAL_OWNED, "OCCUPIED_SIGNAL_REJECTED");
    check(sigaction(TP_PARK_SIGNAL, &initial, NULL) == 0, "SIGNAL_FIXTURE_RESTORED");
    if (test.failures) goto done;
    last_status = tp_park_init();
    check(last_status == TP_PARK_OK, "RESERVE_SIGNAL_AND_DIRECTORY");
    if (test.failures) goto done;
    check(tp_park_init() == TP_PARK_BUSY, "DUPLICATE_RESERVATION_REJECTED");
    for (; made < 4; made++) if (pthread_create(&workers[made], NULL, busy_worker, &test.workers[made])) break;
    check(made == 4 && workers_progress(), "FOUR_UNCOOPERATIVE_NATIVE_WORKERS");
    if (test.failures) goto cleanup;
    for (unsigned i = 0; i < 20; i++) {
        if (!one_cycle(&snapshot, &last_status)) break;
        test.cycles++;
    }
    check(test.cycles == 20, "TWENTY_PARK_RESUME_CYCLES");
    if (test.failures) goto cleanup;
    has_churn = pthread_create(&churn, NULL, churn_worker, NULL) == 0;
    check(has_churn && await_flag(&test.churn_ready), "START_THREAD_CREATION_CHURN");
    if (test.failures) goto cleanup;
    for (unsigned i = 0; i < 20; i++) {
        if (one_cycle(&snapshot, &last_status)) { test.churn_cycles++; continue; }
        /* A creator can be parked while its child still blocks signals during
         * pthread startup. That operation must reject and fully resume everyone. */
        if (last_status != TP_PARK_TIMEOUT || !snapshot.pending_mask_valid ||
            !(snapshot.pending_signal_mask & (UINT64_C(1) << (TP_PARK_SIGNAL - 1))) || !workers_progress()) break;
        churn_mask = snapshot.pending_signal_mask; churn_tid = snapshot.pending_tid;
        test.churn_rejected++;
    }
    atomic_store(&test.stop_churn, true);
    pthread_join(churn, NULL); has_churn = false;
    check(test.churn_cycles > 0 && test.churn_cycles + test.churn_rejected == 20 &&
          atomic_load(&test.children) > 0, "TWENTY_CHURN_ADMISSIONS_OR_CLEAN_REJECTIONS");
    if (test.failures) goto cleanup;
    has_blocked = pthread_create(&blocked, NULL, blocked_worker, NULL) == 0;
    check(has_blocked && await_flag(&test.block_ready), "START_SIGNAL_BLOCKING_THREAD");
    if (test.failures) goto cleanup;
    last_status = tp_park_begin(&timeout);
    if (last_status == TP_PARK_OK) tp_park_end(timeout.epoch);
    timeout_rejected = last_status == TP_PARK_TIMEOUT && timeout.parked >= 4 && timeout.pending_mask_valid &&
        (timeout.pending_signal_mask & (UINT64_C(1) << (TP_PARK_SIGNAL - 1))) && workers_progress();
    check(timeout_rejected, "BLOCKED_SIGNAL_TIMEOUT_RELEASES_OTHER_THREADS");
    atomic_store(&test.unblock, true);
    pthread_join(blocked, NULL); has_blocked = false;
    late_after = tp_park_late_signals(timeout.epoch);
    check(atomic_load(&test.block_done) && late_after == late_before + 1, "LATE_CANCELLED_SIGNAL_IGNORED");
    late_recovered = one_cycle(&snapshot, &last_status);
    check(late_recovered, "NEW_EPOCH_AFTER_CANCELLED_SIGNAL");
cleanup:
    if (has_blocked) { atomic_store(&test.unblock, true); pthread_join(blocked, NULL); }
    if (has_churn) { atomic_store(&test.stop_churn, true); pthread_join(churn, NULL); }
    atomic_store(&test.stop, true);
    for (unsigned i = 0; i < made; i++) pthread_join(workers[i], NULL);
    check(atomic_load(&test.worker_errors) == 0, "WORKER_SYSCALLS_SUCCEEDED");
done:;
    char report[2048];
    unsigned long long calls = 0;
    for (unsigned i = 0; i < made; i++) calls += atomic_load(&test.workers[i].calls);
    snprintf(report, sizeof(report),
        "{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"lastStatus\":%d,"
        "\"signal\":%d,\"cycles\":%u,\"churnCycles\":%u,\"churnRejected\":%u,\"churnPendingTid\":%d,"
        "\"churnSignalMask\":\"%llx\",\"createdChildren\":%u,\"workerCalls\":%llu,"
        "\"peakParkedThreads\":%u,\"capturedContexts\":%u,\"timeoutRejected\":%s,\"timeoutParkedThreads\":%u,"
        "\"timeoutSignalMask\":\"%llx\",\"lateSignals\":%u,\"lateSignalRecovery\":%s,\"processLifetimeReservation\":true,"
        "\"codePagesModified\":false,\"noncooperativeHook\":false,\"genericJavaHook\":false}",
        test.failures == 0 ? "true" : "false", test.checks, test.failures, test.first ? test.first : "", last_status,
        TP_PARK_SIGNAL, test.cycles, test.churn_cycles, test.churn_rejected, churn_tid,
        (unsigned long long)churn_mask, atomic_load(&test.children), calls,
        test.peak_threads, test.contexts, timeout_rejected ? "true" : "false", timeout.parked,
        (unsigned long long)timeout.pending_signal_mask, late_after - late_before, late_recovered ? "true" : "false");
    return (*env)->NewStringUTF(env, report);
}
