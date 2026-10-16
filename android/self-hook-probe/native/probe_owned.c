#define _GNU_SOURCE
#include "probe_owned.h"
#include "probe_maps.h"
#include "tripoder_owned_hook.h"
#include "tripoder_a64.h"
#include <errno.h>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

static struct {
    unsigned checks, failures;
    const char *first;
    _Atomic unsigned callback_failures, calls, hooked_calls, plain_calls;
    _Atomic bool stop, block;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    bool entered, release;
    uint64_t generation;
} test = {.mutex = PTHREAD_MUTEX_INITIALIZER, .condition = PTHREAD_COND_INITIALIZER};
static void check(bool value, const char *name) {
    test.checks++;
    if (!value) { test.failures++; if (test.first == NULL) test.first = name; }
}
static uint64_t callback(uint64_t generation, uint64_t argument) {
    if (atomic_load(&test.block)) {
        pthread_mutex_lock(&test.mutex);
        test.entered = true;
        pthread_cond_broadcast(&test.condition);
        while (!test.release) pthread_cond_wait(&test.condition, &test.mutex);
        pthread_mutex_unlock(&test.mutex);
    }
    uint64_t value = 0, unchanged = 999;
    if (tp_owned_original(generation, argument, &value) != TP_OWNED_OK || value != argument + 1)
        atomic_fetch_add(&test.callback_failures, 1);
    if (tp_owned_original(generation, argument, &unchanged) != TP_OWNED_ORIGINAL_TWICE || unchanged != 999)
        atomic_fetch_add(&test.callback_failures, 1);
    if (tp_owned_enable(generation, false) != TP_OWNED_REENTRANT || tp_owned_close(generation) != TP_OWNED_REENTRANT)
        atomic_fetch_add(&test.callback_failures, 1);
    errno = ERANGE;
    return value + 100;
}
static int find_module(struct dl_phdr_info *info, size_t bytes, void *data) {
    (void)bytes;
    return strcmp(info->dlpi_name, data) == 0;
}
static bool loaded(const char *path) { return dl_iterate_phdr(find_module, (void *)path) != 0; }
static void *one_call(void *unused) {
    (void)unused;
    struct tp_owned_result result;
    if (tp_owned_call(test.generation, 7, &result) != TP_OWNED_OK || result.value != 108 || !result.hooked)
        atomic_fetch_add(&test.callback_failures, 1);
    return NULL;
}
static void *worker(void *number) {
    uint64_t input = (uintptr_t)number;
    while (!atomic_load(&test.stop)) {
        struct tp_owned_result result;
        errno = EDOM;
        enum tp_owned_status status = tp_owned_call(test.generation, input, &result);
        if (status != TP_OWNED_OK || result.value != input + (result.hooked ? 101 : 1)
            || (result.epoch % 2 != 0) != result.hooked
            || result.original_calls != (result.hooked ? 1u : 0u) || errno != (result.hooked ? ERANGE : EDOM))
            atomic_fetch_add(&test.callback_failures, 1);
        if (status == TP_OWNED_OK) {
            atomic_fetch_add(result.hooked ? &test.hooked_calls : &test.plain_calls, 1);
            atomic_fetch_add(&test.calls, 1);
        }
    }
    return NULL;
}

const char *tp_probe_owned(const char *path, const char *directory) {
    /* One invocation per fresh Android process; a repeated invocation fails explicitly. */
    static char report[2048];
    if (test.checks != 0) return "{\"checks\":0,\"failures\":1,\"firstFailure\":\"REPEATED_RUN\"}";
    bool opened = false, enabled = false, executed = false, unloaded = false, reloaded = false;
    unsigned cycles = 0;
    uint64_t generation = 0, next = 0;
    struct tp_owned_info initial = {0}, before = {0}, after = {0};
    struct tp_owned_page_report pages = {0};
    struct tp_owned_result result;
    enum tp_owned_status status = TP_OWNED_STATE;
    check(!loaded(path), "FIXTURE_INITIALLY_UNLOADED");
    if (test.failures) goto done;
    status = tp_owned_open(path, callback, &generation);
    check(status == TP_OWNED_OK, "OPEN_PREPARE_ELF");
    if (test.failures) { unloaded = !loaded(path); goto done; }
    opened = true; test.generation = generation;
    check(loaded(path), "FIXTURE_LOADED");
    uint64_t duplicate = 999;
    check(tp_owned_open(path, callback, &duplicate) == TP_OWNED_BUSY && duplicate == 999, "SINGLE_OWNER");
    check(tp_owned_info(generation, &initial) == TP_OWNED_OK && !initial.enabled && initial.mutations == 1,
          "PREPARED_TRAMPOLINE");
    check(tp_probe_maps_save(directory, "prepared"), "MAPS_PREPARED");
    errno = EDOM;
    check(tp_owned_call(generation, 7, &result) == TP_OWNED_OK && result.value == 8 && !result.hooked
          && result.original_calls == 0 && errno == EDOM, "UNHOOKED_ORIGINAL");
    uint64_t untouched = 123;
    check(tp_owned_original(generation, 7, &untouched) == TP_OWNED_STATE && untouched == 123, "ORIGINAL_OUTSIDE_CALLBACK");
    check(tp_owned_enable(generation + 1, true) == TP_OWNED_STALE, "STALE_INSTALL_REJECTED");
    tp_owned_test_reject_next_prepare();
    check(tp_owned_enable(generation, true) == TP_OWNED_PERMISSION, "PREPARE_FAILURE_REJECTED");
    check(tp_owned_info(generation, &after) == TP_OWNED_OK && !after.enabled
          && after.epoch == initial.epoch && after.mutations == initial.mutations, "PREPARE_FAILURE_NO_PUBLICATION");
    check(tp_owned_call(generation, 7, &result) == TP_OWNED_OK && result.value == 8
          && !result.hooked, "PREPARE_FAILURE_ORIGINAL_EXECUTABLE");
    status = tp_owned_enable(generation, true);
    check(status == TP_OWNED_OK, "INSTALL_REAL_ENTRY");
    if (status != TP_OWNED_OK) goto cleanup;
    enabled = true;
    check(tp_owned_info(generation, &before) == TP_OWNED_OK && before.enabled && before.epoch == 1, "INSTALLED_EPOCH");
    check(*(const uint32_t *)(uintptr_t)initial.target == TP_A64_BTI_C &&
          (*(const uint32_t *)(uintptr_t)(initial.target + 4) & 0xfc000000) == 0x14000000, "REAL_B_WITH_BTI_PRESERVED");
    check(before.target == initial.target && before.trampoline == initial.trampoline, "UNCHANGED_ENTRY_ADDRESS");
    check(tp_probe_maps_save(directory, "active"), "MAPS_ACTIVE");
    check(tp_owned_call(generation, 7, &result) == TP_OWNED_OK && result.value == 108 && result.hooked
          && result.original_calls == 1 && errno == ERANGE, "REPLACEMENT_AND_ORIGINAL");
    check(tp_owned_enable(generation, true) == TP_OWNED_STATE, "DUPLICATE_INSTALL_REJECTED");
    check(tp_owned_close(generation) == TP_OWNED_STATE, "ACTIVE_UNLOAD_REJECTED");
    executed = true;

    /* Hold a real callback in flight: timeout must leave bytes/epoch/ownership intact. */
    atomic_store(&test.block, true);
    pthread_t paused;
    int created = pthread_create(&paused, NULL, one_call, NULL);
    check(created == 0, "CREATE_PAUSED_CALL");
    if (created != 0) goto cleanup;
    pthread_mutex_lock(&test.mutex);
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline); deadline.tv_sec += 2;
    while (!test.entered) {
        if (pthread_cond_timedwait(&test.condition, &test.mutex, &deadline) != 0) break;
    }
    bool entered = test.entered;
    pthread_mutex_unlock(&test.mutex);
    check(entered, "CALLBACK_IN_FLIGHT");
    if (entered) {
        check(tp_owned_enable(generation, false) == TP_OWNED_BUSY, "DRAIN_TIMEOUT_NO_WRITE");
        check(tp_owned_info(generation, &after) == TP_OWNED_OK && after.epoch == before.epoch
              && after.enabled && after.active_calls == 1 && after.mutations == before.mutations, "TIMEOUT_STATE_UNCHANGED");
    }
    pthread_mutex_lock(&test.mutex); test.release = true;
    pthread_cond_broadcast(&test.condition); pthread_mutex_unlock(&test.mutex);
    pthread_join(paused, NULL);
    atomic_store(&test.block, false);
    if (test.failures) goto cleanup;
    status = tp_owned_enable(generation, false);
    check(status == TP_OWNED_OK, "DISABLE_RESTORE_REAL_ENTRY");
    if (status != TP_OWNED_OK) goto cleanup;
    enabled = false;
    check(tp_owned_call(generation, 7, &result) == TP_OWNED_OK && result.value == 8 && !result.hooked, "RESTORED_ORIGINAL");
    check(tp_probe_maps_save(directory, "disabled"), "MAPS_DISABLED");

    pthread_t threads[4];
    unsigned made = 0;
    for (; made < 4; made++) if (pthread_create(&threads[made], NULL, worker, (void *)(uintptr_t)(made + 11))) break;
    check(made == 4, "FOUR_CONCURRENT_CALLERS");
    for (unsigned i = 0; i < 50 && made == 4; i++) {
        status = tp_owned_enable(generation, true);
        if (status != TP_OWNED_OK) { check(false, "CONCURRENT_INSTALL"); break; }
        enabled = true;
        struct timespec dwell = {0, 1000000}; nanosleep(&dwell, NULL);
        status = tp_owned_enable(generation, false);
        if (status != TP_OWNED_OK) { check(false, "CONCURRENT_DISABLE"); break; }
        enabled = false;
        nanosleep(&dwell, NULL);
        cycles++;
    }
    atomic_store(&test.stop, true);
    for (unsigned i = 0; i < made; i++) pthread_join(threads[i], NULL);
    check(cycles == 50, "FIFTY_CONCURRENT_CYCLES");
    check(atomic_load(&test.hooked_calls) > 0 && atomic_load(&test.plain_calls) > 0, "BOTH_GENERATIONS_EXECUTED");
    check(atomic_load(&test.callback_failures) == 0, "VALUES_ORIGINAL_ONCE_REENTRY_ERRNO");
cleanup:
    if (enabled) {
        status = tp_owned_enable(generation, false);
        check(status == TP_OWNED_OK, "CLEANUP_DISABLE");
        if (status != TP_OWNED_OK) goto done;
    }
    if (opened) {
        /* A deliberate observation lease permits independent inspection after
         * the owner closes. It is released immediately before the unload check. */
        void *observation = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        check(observation != NULL, "OBSERVATION_LEASE");
        check(tp_owned_close(generation) == TP_OWNED_OK, "CLOSE_AND_RECLAIM");
        if (observation != NULL) {
            check(tp_probe_maps_save(directory, "restored"), "MAPS_RESTORED_FILE");
            const uint32_t *target = (const uint32_t *)(uintptr_t)initial.target;
            const uint32_t *trampoline = (const uint32_t *)(uintptr_t)initial.trampoline;
            bool bytes = target[0] == TP_A64_BTI_C && target[1] == UINT32_C(0x91000400)
                && target[2] == UINT32_C(0xd65f03c0);
            for (unsigned i = 0; i < 1024; i++)
                if (trampoline[i] != TP_A64_NOP || (i >= 3 && target[i] != TP_A64_NOP)) bytes = false;
            check(bytes, "WHOLE_ORIGINAL_PAGES_RESTORED");
            check(dlclose(observation) == 0, "OBSERVATION_LEASE_RELEASED");
        }
        unloaded = !loaded(path);
        check(unloaded, "ACTUAL_MODULE_UNLOAD");
        check(tp_owned_call(generation, 7, &result) == TP_OWNED_STALE, "CALL_AFTER_CLOSE_REJECTED");
        check(tp_owned_enable(generation, true) == TP_OWNED_STALE, "INSTALL_AFTER_CLOSE_REJECTED");
    }
    if (test.failures) goto done;
    status = tp_owned_open(path, callback, &next);
    check(status == TP_OWNED_OK && next > generation, "RELOAD_NEW_GENERATION");
    if (status != TP_OWNED_OK) goto done;
    reloaded = true;
    check(tp_owned_enable(generation, true) == TP_OWNED_STALE, "OLD_GENERATION_CANNOT_PATCH_RELOAD");
    check(tp_owned_call(next, 7, &result) == TP_OWNED_OK && result.value == 8, "RELOAD_ORIGINAL_BYTES");
    check(tp_owned_close(next) == TP_OWNED_OK && !loaded(path), "RELOAD_LEASE_RELEASED");
done:
    tp_owned_pages(&pages);
    if (test.failures == 0) {
        check(pages.staged_rw == pages.staged_rx + 1 && pages.staged_rx == pages.published
              && pages.published == 104 && pages.restored == 3 && pages.prepare_errno == 0
              && pages.injected_prepare_rejections == 1, "PAGE_PUBLICATION_COUNTS");
        check(pages.live_staging_pages == 0 && pages.live_anonymous_pages == 0
              && pages.peak_staging_pages == 3, "BOUNDED_PAGES_RECLAIMED");
    }
    snprintf(report, sizeof(report),
        "{\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"lastStatus\":%d,"
        "\"executed\":%s,\"liveElfEntryPatched\":%s,\"cooperativeDomainOnly\":true,"
        "\"cycles\":%u,\"workerCalls\":%u,\"hookedCalls\":%u,\"plainCalls\":%u,\"callbackFailures\":%u,"
        "\"unloaded\":%s,\"reloaded\":%s,\"firstGeneration\":%llu,\"secondGeneration\":%llu,"
        "\"target\":\"%llx\",\"trampoline\":\"%llx\",\"arbitraryThreadsStopped\":false,"
        "\"btiEnforcementTested\":false,\"javaHook\":false,"
        "\"pages\":{\"model\":\"owned-anonymous-page-v1\",\"stagedRW\":%u,\"stagedRX\":%u,"
        "\"published\":%u,\"restored\":%u,\"liveStagingPages\":%u,\"peakStagingPages\":%u,"
        "\"liveAnonymousPages\":%u,\"prepareErrno\":%d,\"injectedPrepareRejections\":%u}}",
        test.checks, test.failures, test.first == NULL ? "" : test.first, status,
        executed ? "true" : "false", executed ? "true" : "false", cycles,
        atomic_load(&test.calls), atomic_load(&test.hooked_calls), atomic_load(&test.plain_calls),
        atomic_load(&test.callback_failures), unloaded ? "true" : "false", reloaded ? "true" : "false",
        (unsigned long long)generation, (unsigned long long)next,
        (unsigned long long)initial.target, (unsigned long long)initial.trampoline,
        pages.staged_rw, pages.staged_rx, pages.published, pages.restored, pages.live_staging_pages,
        pages.peak_staging_pages, pages.live_anonymous_pages, pages.prepare_errno, pages.injected_prepare_rejections);
    return report;
}
