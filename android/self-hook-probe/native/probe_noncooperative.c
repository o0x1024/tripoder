#define _GNU_SOURCE
#include "code_page.h"
#include "tripoder_a64.h"
#include "tripoder_thread_park.h"
#include "park_syscall.h"
#include "probe_maps.h"
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

typedef uint64_t (*value_function)(uint64_t);
static const uint32_t original[TP_CODE_PAGE_WORDS] = {TP_A64_BTI_C, UINT32_C(0x91000400), UINT32_C(0xd65f03c0), [3 ... TP_CODE_PAGE_WORDS-1] = TP_A64_NOP};
static const uint32_t empty[TP_CODE_PAGE_WORDS] = {[0 ... TP_CODE_PAGE_WORDS-1] = TP_A64_NOP};
static _Thread_local unsigned original_calls;
static struct {
    value_function target, trampoline;
    _Atomic bool stop, hold_callback, block_ready, unblock_signal;
    _Atomic unsigned errors, active_callbacks, held_callbacks, hooked, plain;
    unsigned checks, failures, cycles, commits, rejections;
    const char *first;
} test;
static void check(bool condition, const char *name) {
    test.checks++;
    if (!condition) { test.failures++; if (test.first == NULL) test.first = name; }
}
static uint64_t replacement(uint64_t value) {
    atomic_fetch_add(&test.active_callbacks, 1);
    if (atomic_load(&test.hold_callback)) {
        atomic_fetch_add(&test.held_callbacks, 1);
        while (atomic_load(&test.hold_callback)) tp_park_sleep(100000);
        atomic_fetch_sub(&test.held_callbacks, 1);
    }
    original_calls++;
    uint64_t result = test.trampoline(value);
    atomic_fetch_sub(&test.active_callbacks, 1);
    errno = ERANGE;
    return result + 100;
}
static void *worker(void *number) {
    uint64_t value = (uintptr_t)number;
    while (!atomic_load(&test.stop)) {
        original_calls = 0;
        errno = EDOM;
        /* Direct ELF entry: no mutex, admission gate, per-call ISB or wrapper. */
        uint64_t result = test.target(value);
        int outgoing = errno;
        bool hooked = result == value + 101;
        if ((!hooked && result != value + 1) || original_calls != (hooked ? 1u : 0u) ||
            outgoing != (hooked ? ERANGE : EDOM)) atomic_fetch_add(&test.errors, 1);
        atomic_fetch_add(hooked ? &test.hooked : &test.plain, 1);
    }
    return NULL;
}
static void *blocked_worker(void *unused) {
    (void)unused;
    sigset_t set;
    sigemptyset(&set); sigaddset(&set, TP_PARK_SIGNAL);
    if (pthread_sigmask(SIG_BLOCK, &set, NULL) != 0) atomic_fetch_add(&test.errors, 1);
    atomic_store(&test.block_ready, true);
    while (!atomic_load(&test.unblock_signal)) tp_park_sleep(100000);
    if (pthread_sigmask(SIG_UNBLOCK, &set, NULL) != 0) atomic_fetch_add(&test.errors, 1);
    return NULL;
}
static int module_found(struct dl_phdr_info *info, size_t bytes, void *path) {
    (void)bytes;
    return strcmp(info->dlpi_name, path) == 0;
}

/* The fixed leaf has only one replaced ADD at +4 and an unchanged RET at +8.
 * An old frame returning from the stable trampoline lands at unchanged +8.
 * All relay/trampoline/callback storage and the module lease survive every
 * publication and are reclaimed only after joining every possible caller. */
__attribute__((noinline)) static int commit_and_resume(struct tp_code_page_update *update, unsigned epoch) {
    int changed = tp_code_page_commit(update);
    if (tp_park_end(epoch) != TP_PARK_OK) {
        (void)tp_park_syscall(SYS_exit_group, 194, 0, 0, 0, 0, 0); __builtin_trap();
    }
    return changed;
}
static int parked_publish(struct tp_code_page *page, const uint32_t before[static TP_CODE_PAGE_WORDS], const uint32_t after[static TP_CODE_PAGE_WORDS]) {
    struct tp_code_page_update update = {0};
    if (tp_code_page_prepare(page, before, after, &update) != 1) return 0;
    struct tp_park_snapshot snapshot;
    enum tp_park_status status = tp_park_begin(&snapshot);
    if (status != TP_PARK_OK) {
        tp_code_page_cancel(&update); test.rejections++; return -(int)status;
    }
    int changed = commit_and_resume(&update, snapshot.epoch);
    if (!changed) { tp_code_page_cancel(&update); return 0; }
    tp_code_page_finish(&update);
    test.commits++;
    return 1;
}

static const char *run(const char *path, const char *directory) {
    static char report[2048];
    if (test.checks) return "{\"passed\":false,\"error\":\"REPEATED_RUN\"}";
    void *module = NULL;
    uint64_t *callback = NULL;
    struct tp_code_page entry_page = {0}, trampoline_page = {0};
    struct tp_owned_page_report before, after;
    tp_code_pages_report(&before);
    struct tp_a64_entry_plan plan;
    uint32_t patched[TP_CODE_PAGE_WORDS], displaced[TP_CODE_PAGE_WORDS];
    memcpy(patched, original, sizeof(patched)); memcpy(displaced, empty, sizeof(displaced));
    bool trampoline_ready = false, enabled = false, restored = false, unloaded = false, in_flight = false;
    bool callback_installed = false;
    bool timeout_unchanged = false, commit_unchanged = false;
    unsigned made = 0;
    pthread_t threads[4];
    int last_status = 0;
    uintptr_t target_address = 0, trampoline_address = 0;
    check(!dl_iterate_phdr(module_found, (void *)path), "FIXTURE_INITIALLY_UNLOADED");
    if (test.failures) goto done;
    module = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    check(module != NULL, "EXPLICIT_MODULE_LEASE");
    if (test.failures) goto done;
    test.target = (value_function)dlsym(module, "tp_fixture_target");
    test.trampoline = (value_function)dlsym(module, "tp_fixture_trampoline");
    void *relay = dlsym(module, "tp_fixture_replacement");
    callback = dlsym(module, "tp_fixture_callback");
    target_address = (uintptr_t)test.target; trampoline_address = (uintptr_t)test.trampoline;
    Dl_info target_info, trampoline_info, relay_info, callback_info;
    check(test.target && test.trampoline && relay && callback && dladdr((void *)test.target, &target_info) &&
        dladdr((void *)test.trampoline, &trampoline_info) && dladdr(relay, &relay_info) && dladdr(callback, &callback_info) &&
        strcmp(target_info.dli_fname, path) == 0 && target_info.dli_fbase == trampoline_info.dli_fbase &&
        target_info.dli_fbase == relay_info.dli_fbase && target_info.dli_fbase == callback_info.dli_fbase &&
        trampoline_address == target_address + 4096 && (uintptr_t)relay == trampoline_address + 4096,
        "OWNED_FIXTURE_IDENTITY");
    if (test.failures) goto cleanup;
    check(tp_a64_plan_entry(original, 3, target_address, (uintptr_t)relay, trampoline_address, &plan) == TP_A64_OK,
          "FIXED_LEAF_ENTRY_PLAN");
    if (test.failures) goto cleanup;
    patched[1] = plan.branch_word;
    check(tp_code_page_claim(&entry_page, (void *)test.target, path, original) &&
          tp_code_page_claim(&trampoline_page, (void *)test.trampoline, path, empty), "CLAIM_OWNED_PAGES");
    if (test.failures) goto cleanup;
    memcpy(displaced, plan.trampoline, sizeof(plan.trampoline));
    trampoline_ready = tp_code_page_publish(&trampoline_page, empty, displaced) == 1;
    check(trampoline_ready, "PREPARE_STABLE_TRAMPOLINE_BEFORE_CALLERS");
    if (test.failures) goto cleanup;
    *callback = (uintptr_t)replacement;
    callback_installed = true;
    check(test.target(7) == 8 && test.trampoline(7) == 8, "ORIGINAL_AND_TRAMPOLINE");

    /* Corrupted preimage rejects in the paused critical section, before mremap. */
    struct tp_code_page_update update = {0};
    check(tp_code_page_prepare(&entry_page, original, patched, &update) == 1, "PREPARE_COMMIT_REJECTION");
    if (test.failures) goto cleanup;
    update.expected[1] ^= 1;
    struct tp_park_snapshot snapshot;
    last_status = tp_park_begin(&snapshot);
    if (last_status == TP_PARK_OK) {
        int changed = commit_and_resume(&update, snapshot.epoch);
        commit_unchanged = changed == 0;
        if (changed) tp_code_fatal();
    }
    tp_code_page_cancel(&update);
    check(commit_unchanged && test.target(7) == 8 && tp_code_page_current(&entry_page, original), "PREIMAGE_REJECT_NO_PUBLICATION");
    if (test.failures) goto cleanup;

    pthread_t blocked;
    bool created = pthread_create(&blocked, NULL, blocked_worker, NULL) == 0;
    check(created, "CREATE_BLOCKED_ADMISSION_FIXTURE");
    if (!created) goto cleanup;
    uint64_t deadline = tp_park_now() + UINT64_C(2000000000);
    while (!atomic_load(&test.block_ready) && tp_park_now() < deadline) tp_park_sleep(100000);
    if (atomic_load(&test.block_ready)) last_status = parked_publish(&entry_page, original, patched);
    timeout_unchanged = last_status == -(int)TP_PARK_TIMEOUT && test.target(7) == 8 &&
        tp_code_page_current(&entry_page, original) && test.commits == 0;
    atomic_store(&test.unblock_signal, true); pthread_join(blocked, NULL);
    check(timeout_unchanged, "PAUSE_TIMEOUT_CANDIDATE_CANCELLED_TARGET_UNCHANGED");
    if (test.failures) goto cleanup;

    for (; made < 4; made++) if (pthread_create(&threads[made], NULL, worker, (void *)(uintptr_t)(made + 11))) break;
    check(made == 4, "FOUR_DIRECT_ELF_CALLERS");
    if (test.failures) goto cleanup;
    for (unsigned i = 0; i < 50; i++) {
        last_status = parked_publish(&entry_page, original, patched);
        if (last_status != 1) break;
        enabled = true;
        if (i == 0) {
            check(tp_probe_maps_save(directory, "parked-active"), "ACTIVE_PAGE_SNAPSHOT");
            atomic_store(&test.hold_callback, true);
            deadline = tp_park_now() + UINT64_C(100000000);
            while (!atomic_load(&test.held_callbacks) && tp_park_now() < deadline) tp_park_sleep(100000);
            in_flight = atomic_load(&test.held_callbacks) > 0;
        } else tp_park_sleep(1000000);
        last_status = parked_publish(&entry_page, patched, original);
        atomic_store(&test.hold_callback, false);
        if (last_status != 1) break;
        enabled = false;
        tp_park_sleep(1000000);
        test.cycles++;
    }
    check(test.cycles == 50 && test.commits == 100, "FIFTY_PARKED_ENTRY_INSTALL_DISABLE_CYCLES");
    check(in_flight, "DISABLE_WITH_OLD_CALLBACK_IN_FLIGHT");
cleanup:
    atomic_store(&test.hold_callback, false); atomic_store(&test.stop, true);
    for (unsigned i = 0; i < made; i++) pthread_join(threads[i], NULL);
    check(atomic_load(&test.active_callbacks) == 0 && atomic_load(&test.errors) == 0, "CALLERS_JOINED_VALUES_ORIGINAL_ONCE_ERRNO");
    if (enabled) {
        /* All possible owned callers joined: ordinary drained publication is now safe. */
        if (tp_code_page_publish(&entry_page, patched, original) != 1) tp_code_fatal();
    }
    if (entry_page.address) tp_code_page_release(&entry_page, original, original);
    if (trampoline_page.address) tp_code_page_release(&trampoline_page, trampoline_ready ? displaced : empty, empty);
    if (callback_installed) *callback = 0;
    if (module) {
        restored = tp_probe_maps_save(directory, "parked-restored");
        if (dlclose(module) != 0) tp_code_fatal();
        unloaded = !dl_iterate_phdr(module_found, (void *)path);
    }
    check(restored && unloaded, "RESTORE_FILE_PAGES_AND_RELEASE_LEASE_AFTER_JOIN");
    check(atomic_load(&test.hooked) > 0 && atomic_load(&test.plain) > 0, "BOTH_ENTRY_VERSIONS_EXECUTED");
done:
    tp_code_pages_report(&after);
    if (test.failures == 0) check(after.published - before.published == 101 && after.restored - before.restored == 2 &&
        after.staged_rw - before.staged_rw == 103 && after.staged_rx - before.staged_rx == 103 &&
        after.live_staging_pages == 0 && after.live_anonymous_pages == 0, "CANDIDATES_AND_ANONYMOUS_PAGES_RECLAIMED");
    snprintf(report, sizeof(report),
        "{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"lastStatus\":%d,"
        "\"cycles\":%u,\"commits\":%u,\"admissionRejections\":%u,\"plainCalls\":%u,\"hookedCalls\":%u,\"callErrors\":%u,"
        "\"timeoutTargetUnchanged\":%s,\"commitTargetUnchanged\":%s,\"disabledWithInFlightCallback\":%s,"
        "\"target\":\"%llx\",\"trampoline\":\"%llx\",\"unloaded\":%s,\"fileMappingsRestored\":%s,"
        "\"publishedPages\":%u,\"restoredPages\":%u,\"liveStagingPages\":%u,\"liveAnonymousPages\":%u,"
        "\"directNativeCalls\":true,\"dedicatedOwnedPagesOnly\":true,\"genericNativeHook\":false,\"btiEnforcementTested\":false}",
        test.failures == 0 ? "true" : "false", test.checks, test.failures, test.first ? test.first : "", last_status,
        test.cycles, test.commits, test.rejections, atomic_load(&test.plain), atomic_load(&test.hooked), atomic_load(&test.errors),
        timeout_unchanged ? "true" : "false", commit_unchanged ? "true" : "false", in_flight ? "true" : "false",
        (unsigned long long)target_address, (unsigned long long)trampoline_address,
        unloaded ? "true" : "false", restored ? "true" : "false", after.published - before.published,
        after.restored - before.restored, after.live_staging_pages, after.live_anonymous_pages);
    return report;
}

JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NoncooperativeProbe_runNative(
        JNIEnv *env, jclass type, jstring fixture, jstring directory) {
    (void)type;
    if (!fixture || !directory) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, fixture, NULL);
    if (!path) return NULL;
    const char *output = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!output) { (*env)->ReleaseStringUTFChars(env, fixture, path); return NULL; }
    const char *report = run(path, output);
    (*env)->ReleaseStringUTFChars(env, directory, output);
    (*env)->ReleaseStringUTFChars(env, fixture, path);
    return (*env)->NewStringUTF(env, report);
}
