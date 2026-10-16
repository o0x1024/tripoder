#include "tripoder_a64.h"
#include "core_checks.h"
#include "probe_owned.h"
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <unistd.h>

#if !defined(__aarch64__)
#error This probe is AArch64 only.
#endif

struct execution_result {
    unsigned checks, failures;
    const char *first_failure;
    int system_errno;
    int rw_observed, rx_observed, executed;
};

static void check(struct execution_result *result, int condition, const char *name) {
    result->checks++;
    if (!condition) {
        result->failures++;
        if (result->first_failure == NULL) result->first_failure = name;
    }
}

static int permissions(void *memory, size_t bytes, const char *expected) {
    FILE *stream = fopen("/proc/self/maps", "r");
    if (stream == NULL) return 0;
    char line[1024], mode[5];
    unsigned long first, last;
    int found = 0;
    while (fgets(line, sizeof(line), stream) != NULL) {
        if (sscanf(line, "%lx-%lx %4s", &first, &last, mode) == 3 &&
            first <= (uintptr_t)memory && last >= (uintptr_t)memory + bytes) {
            found = strncmp(mode, expected, 4) == 0;
            break;
        }
    }
    fclose(stream);
    return found;
}

static void function(uint8_t *page, size_t at, uint32_t instruction) {
    const uint32_t words[] = {TP_A64_BTI_C, instruction, UINT32_C(0xd65f03c0)};
    memcpy(page + at, words, sizeof(words));
}

static struct execution_result execute_synthetic(void) {
    struct execution_result result = {0};
    long page_size = sysconf(_SC_PAGESIZE);
    check(&result, page_size == 4096, "PAGE_SIZE");
    if (result.failures) return result;
    uint8_t *page = mmap(NULL, (size_t)page_size, PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (page == MAP_FAILED) {
        result.system_errno = errno;
        check(&result, 0, "MMAP_RW");
        return result;
    }
    check(&result, 1, "MMAP_RW");
    result.rw_observed = permissions(page, (size_t)page_size, "rw-p");
    check(&result, result.rw_observed, "RW_NOT_EXECUTABLE");
    if (result.failures) goto cleanup;
    /* All code is constructed before the page becomes executable. No live patch. */
    function(page, 0, UINT32_C(0x91000400));   /* add x0, x0, #1 */
    function(page, 64, UINT32_C(0x91019400)); /* add x0, x0, #101 */
    function(page, 256, UINT32_C(0x91000400));
    struct tp_a64_entry_plan plan;
    enum tp_a64_status status = tp_a64_plan_entry((uint32_t *)(page + 256), 3,
        (uintptr_t)(page + 256), (uintptr_t)(page + 64), (uintptr_t)(page + 128), &plan);
    check(&result, status == TP_A64_OK, "ENTRY_PLAN");
    if (result.failures) goto cleanup;
    memcpy(page + 260, &plan.branch_word, sizeof(uint32_t));
    memcpy(page + 128, plan.trampoline, sizeof(plan.trampoline));

    function(page, 512, UINT32_C(0x10000400)); /* adr x0, PC + 128 */
    function(page, 768, TP_A64_NOP);
    status = tp_a64_relocate((uint32_t *)(page + 516), 1,
        (uintptr_t)(page + 516), (uintptr_t)(page + 772), (uint32_t *)(page + 772));
    check(&result, status == TP_A64_OK, "ADR_PLAN");
    function(page, 1024, UINT32_C(0x58000400)); /* ldr x0, PC + 128 */
    function(page, 1280, TP_A64_NOP);
    const uint64_t literal = UINT64_C(0x123456789abcdef0);
    memcpy(page + 1156, &literal, sizeof(literal));
    status = tp_a64_relocate((uint32_t *)(page + 1028), 1,
        (uintptr_t)(page + 1028), (uintptr_t)(page + 1284), (uint32_t *)(page + 1284));
    check(&result, status == TP_A64_OK, "LITERAL_PLAN");
    if (result.failures) goto cleanup;
    __builtin___clear_cache((char *)page, (char *)page + page_size);
    if (mprotect(page, (size_t)page_size, PROT_READ | PROT_EXEC) != 0) {
        result.system_errno = errno;
        check(&result, 0, "MPROTECT_RX");
        goto cleanup;
    }
    check(&result, 1, "MPROTECT_RX");
    result.rx_observed = permissions(page, (size_t)page_size, "r-xp");
    check(&result, result.rx_observed, "RX_NOT_WRITABLE");
    if (result.failures) goto cleanup;
    __builtin___clear_cache((char *)page, (char *)page + page_size);
    typedef uint64_t (*value_fn)(uint64_t);
    typedef uint64_t (*address_fn)(void);
    check(&result, ((value_fn)(page + 0))(7) == 8, "ORIGINAL_ADD");
    check(&result, ((value_fn)(page + 256))(7) == 108, "SYNTHETIC_DETOUR");
    check(&result, ((value_fn)(page + 128))(7) == 8, "SYNTHETIC_TRAMPOLINE");
    check(&result, ((address_fn)(page + 512))() == (uintptr_t)(page + 644), "ORIGINAL_ADR");
    check(&result, ((address_fn)(page + 768))() == (uintptr_t)(page + 644), "RELOCATED_ADR");
    check(&result, ((address_fn)(page + 1024))() == literal, "ORIGINAL_LITERAL");
    check(&result, ((address_fn)(page + 1280))() == literal, "RELOCATED_LITERAL");
    result.executed = 1;
cleanup:
    if (munmap(page, (size_t)page_size) != 0) {
        result.system_errno = errno;
        check(&result, 0, "MUNMAP");
    } else check(&result, 1, "MUNMAP");
    return result;
}

JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_ProbeActivity_runNative(
        JNIEnv *env, jclass type, jstring fixture_path, jstring evidence_directory) {
    (void)type;
    struct tp_check_result core = tp_core_checks();
    struct execution_result execution = {0};
    if (core.failures == 0) execution = execute_synthetic();
    if (fixture_path == NULL || evidence_directory == NULL) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, fixture_path, NULL);
    if (path == NULL) return NULL;
    const char *directory = (*env)->GetStringUTFChars(env, evidence_directory, NULL);
    if (directory == NULL) { (*env)->ReleaseStringUTFChars(env, fixture_path, path); return NULL; }
    const char *owned = "{\"checks\":0,\"failures\":1,\"firstFailure\":\"H1_NOT_PASSED\"}";
    if (core.failures == 0 && execution.failures == 0 && execution.executed) owned = tp_probe_owned(path, directory);
    (*env)->ReleaseStringUTFChars(env, evidence_directory, directory);
    (*env)->ReleaseStringUTFChars(env, fixture_path, path);
    char report[4096];
    int length = snprintf(report, sizeof(report),
        "{\"core\":{\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\"},"
        "\"execution\":{\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"errno\":%d,"
        "\"rwObserved\":%s,\"rxObserved\":%s,\"executed\":%s},"
        "\"hwcap\":\"%lx\",\"hwcap2\":\"%lx\",\"btiEnforcementTested\":false,"
        "\"javaHook\":false,\"ownedHook\":%s}",
        core.checks, core.failures, core.first_failure == NULL ? "" : core.first_failure,
        execution.checks, execution.failures,
        execution.first_failure == NULL ? "" : execution.first_failure, execution.system_errno,
        execution.rw_observed ? "true" : "false", execution.rx_observed ? "true" : "false",
        execution.executed ? "true" : "false", getauxval(AT_HWCAP), getauxval(AT_HWCAP2), owned);
    if (length < 0 || (size_t)length >= sizeof(report)) return NULL;
    return (*env)->NewStringUTF(env, report);
}
