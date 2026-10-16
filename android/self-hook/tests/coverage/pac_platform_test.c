#include "pac_platform.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks;
static void check(int condition, const char *label) {
    checks++;
    if (!condition) { fprintf(stderr, "FAIL:%s\n", label); exit(1); }
}
static struct tp_pac_platform_report base(void) {
    struct tp_pac_platform_report r = {0};
    r.schema = 1; r.pointer_bits = 64;
    strcpy(r.machine, "aarch64"); strcpy(r.kernel_release, "test-kernel");
    strcpy(r.kernel_version, "quoted\"slash\\newline\n");
    r.hwcap = (struct tp_pac_value){(UINT64_C(1) << 30) | (UINT64_C(1) << 11), TP_PAC_VALUE_PRESENT, 0};
    r.hwcap2 = (struct tp_pac_value){UINT64_C(1) << 17, TP_PAC_VALUE_PRESENT, 0};
    r.hwcap3 = (struct tp_pac_value){UINT64_MAX, TP_PAC_VALUE_PRESENT, 0};
    r.hwcap4 = (struct tp_pac_value){0, TP_PAC_VALUE_MISSING, ENOENT};
    r.page_size = (struct tp_pac_value){4096, TP_PAC_VALUE_PRESENT, 0};
    r.enabled_keys = (struct tp_pac_value){1, TP_PAC_VALUE_PRESENT, 0};
    r.id_status = TP_PAC_IDS_LINUX_USER_VIEW; r.isar1 = 1u << 4;
    return r;
}
static struct tp_pac_platform_assessment assess(struct tp_pac_platform_report r) {
    struct tp_pac_platform_assessment result;
    tp_pac_platform_assess(&r, &result);
    return result;
}
int main(void) {
    struct tp_pac_platform_report r = base();
    struct tp_pac_platform_assessment a = assess(r);
    check(a.status == TP_PAC_PLATFORM_UNKNOWN && a.reasons == TP_PAC_KERNEL_CONTRACT_UNVERIFIED,
        "apparently classic values cannot self-attest vendor kernel contract");
    for (unsigned level = 1; level <= 5; level++) {
        r = base(); r.isar1 = (uint64_t)level << 4; a = assess(r);
        check(a.status == TP_PAC_PLATFORM_UNKNOWN && a.apa == level, "known level is evidence, not admission");
    }
    for (unsigned field = 0; field < 3; field++) {
        for (unsigned level = 6; level < 16; level++) {
            r = base(); r.isar1 = r.isar2 = 0;
            if (field == 0) r.isar1 = (uint64_t)level << 4;
            else if (field == 1) r.isar1 = (uint64_t)level << 8;
            else r.isar2 = (uint64_t)level << 12;
            a = assess(r);
            check(level == 6 ? a.status == TP_PAC_PLATFORM_REJECTED && (a.reasons & TP_PAC_PAUTH_LR_OBSERVED)
                : a.status == TP_PAC_PLATFORM_UNKNOWN && (a.reasons & TP_PAC_ID_ENCODING_UNKNOWN),
                "PAuth_LR known positive vs future encoding unknown");
        }
    }
    r = base(); r.isar1 = r.isar2 = r.isar3 = 0; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_UNKNOWN && (a.reasons & TP_PAC_ZERO_ID_IS_NOT_ABSENCE), "zero may be masked or reserved");
    r = base(); r.hwcap.status = TP_PAC_VALUE_MISSING; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_UNKNOWN && (a.reasons & TP_PAC_AUXV_UNOBSERVED)
        && !(a.reasons & TP_PAC_ADDRESS_AUTH_NOT_ADVERTISED), "missing auxv is not absent PACA");
    r = base(); r.hwcap.value = 0; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_REJECTED && (a.reasons & TP_PAC_ADDRESS_AUTH_NOT_ADVERTISED), "advertised zero PACA rejects use");
    r = base(); r.hwcap.value &= ~(UINT64_C(1) << 11); r.id_status = TP_PAC_IDS_NO_CPUID_ABI; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_UNKNOWN && (a.reasons & TP_PAC_CPUID_ABI_UNAVAILABLE)
        && (a.reasons & TP_PAC_IDS_UNOBSERVED), "no CPUID produces unknown IDs");
    r = base(); r.enabled_keys.status = TP_PAC_VALUE_ERROR; r.enabled_keys.error_number = EINVAL; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_UNKNOWN && (a.reasons & TP_PAC_KEY_STATE_UNOBSERVED)
        && !(a.reasons & TP_PAC_APIA_KEY_DISABLED), "unsupported prctl is not disabled key proof");
    r = base(); r.enabled_keys.value = 0; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_REJECTED && (a.reasons & TP_PAC_APIA_KEY_DISABLED), "real disabled APIA rejects");
    r = base(); r.page_size.value = 16384; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_REJECTED && (a.reasons & TP_PAC_PAGE_SIZE_UNSUPPORTED), "fixed publisher is 4K only");
    r = base(); r.hwcap2.status = TP_PAC_VALUE_MISSING; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_UNKNOWN && !(a.reasons & TP_PAC_BTI_NOT_ADVERTISED), "missing HWCAP2 not unsupported BTI");
    r = base(); r.hwcap2.value = 0; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_REJECTED && (a.reasons & TP_PAC_BTI_NOT_ADVERTISED), "known BTI unadvertised rejects");
    r = base(); r.uname_error = EACCES; r.machine[0] = 0; a = assess(r);
    check(a.status == TP_PAC_PLATFORM_UNKNOWN, "failed uname not architecture disproof");

    char output[4096]; r = base(); a = assess(r);
    size_t length = tp_pac_platform_json(&r, &a, output, sizeof(output));
    check(length > 0 && length == strlen(output), "complete bounded JSON");
    check(strstr(output, "0xffffffffffffffff") != NULL, "unsigned 64-bit precision preserved");
    check(strstr(output, "\\\"") && strstr(output, "\\\\") && strstr(output, "\\u000a"), "identity text escaped");
    struct { char tiny[8]; unsigned guard; } short_output = {{0}, 0x12345678};
    check(tp_pac_platform_json(&r, &a, short_output.tiny, sizeof(short_output.tiny)) == 0
        && !short_output.tiny[0] && short_output.guard == 0x12345678, "small buffer cannot yield partial report");
    memset(r.kernel_version, 'x', sizeof(r.kernel_version));
    check(tp_pac_platform_json(&r, &a, output, sizeof(output)) == 0 && !output[0], "unterminated identity rejected");
    r = base(); r.hwcap.value &= ~(UINT64_C(1) << 11); r.id_status = TP_PAC_IDS_NO_CPUID_ABI; a = assess(r);
    check(tp_pac_platform_json(&r, &a, output, sizeof(output)) > 0 && strstr(output, "\"apa\":null"), "unobserved ID fields serialize null");
    printf("{\"checks\":%u,\"deviceExecuted\":false,\"profileAdmitted\":false,\"sample\":%s}\n", checks, output);
    return 0;
}
