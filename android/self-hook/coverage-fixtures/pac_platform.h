#ifndef TP_PAC_PLATFORM_H
#define TP_PAC_PLATFORM_H
#include <stddef.h>
#include <stdint.h>

enum tp_pac_value_status { TP_PAC_VALUE_UNREAD, TP_PAC_VALUE_PRESENT, TP_PAC_VALUE_MISSING, TP_PAC_VALUE_ERROR };
struct tp_pac_value { uint64_t value; enum tp_pac_value_status status; int error_number; };
enum tp_pac_id_status { TP_PAC_IDS_UNREAD, TP_PAC_IDS_NO_CPUID_ABI, TP_PAC_IDS_LINUX_USER_VIEW };
struct tp_pac_platform_report {
    unsigned schema, pointer_bits;
    int pid, tid, cpu_before, cpu_after, uname_error, clock_error;
    uint64_t monotonic_ns;
    char kernel_release[65], kernel_version[65], machine[65];
    struct tp_pac_value hwcap, hwcap2, hwcap3, hwcap4, page_size, enabled_keys;
    enum tp_pac_id_status id_status;
    uint64_t isar1, isar2, isar3, pfr1, midr;
};
enum tp_pac_platform_status { TP_PAC_PLATFORM_UNKNOWN, TP_PAC_PLATFORM_REJECTED };
enum tp_pac_platform_reason {
    TP_PAC_NOT_AARCH64 = 1u << 0,
    TP_PAC_AUXV_UNOBSERVED = 1u << 1,
    TP_PAC_ADDRESS_AUTH_NOT_ADVERTISED = 1u << 2,
    TP_PAC_BTI_NOT_ADVERTISED = 1u << 3,
    TP_PAC_CPUID_ABI_UNAVAILABLE = 1u << 4,
    TP_PAC_KEY_STATE_UNOBSERVED = 1u << 5,
    TP_PAC_APIA_KEY_DISABLED = 1u << 6,
    TP_PAC_IDS_UNOBSERVED = 1u << 7,
    TP_PAC_PAUTH_LR_OBSERVED = 1u << 8,
    TP_PAC_ID_ENCODING_UNKNOWN = 1u << 9,
    TP_PAC_PAGE_SIZE_UNSUPPORTED = 1u << 10,
    TP_PAC_KERNEL_CONTRACT_UNVERIFIED = 1u << 11,
    TP_PAC_ZERO_ID_IS_NOT_ABSENCE = 1u << 12
};
struct tp_pac_platform_assessment {
    enum tp_pac_platform_status status;
    unsigned reasons, apa, api, apa3, pacm;
};

/* Android ARM64-only read-only collector. MRS executes only when the kernel
 * advertises HWCAP_CPUID. No signal handlers, keys, PACM or affinity changes.
 * ID values are the kernel's user-visible/sanitized view, not raw per-CPU facts.
 * A kernel that violates its advertised ABI fails the probe; no SIGILL recovery. */
int tp_pac_platform_collect(struct tp_pac_platform_report *report);
/* No matching vendor-kernel contract is currently bound. Even classic-looking
 * values remain UNKNOWN. This is evidence assessment, not Hook authorization. */
void tp_pac_platform_assess(const struct tp_pac_platform_report *report,
    struct tp_pac_platform_assessment *assessment);
/* Hex strings preserve 64-bit values in JSON consumers. Returns bytes excluding
 * NUL, or 0 on failure; a failed formatting leaves an empty destination. */
size_t tp_pac_platform_json(const struct tp_pac_platform_report *report,
    const struct tp_pac_platform_assessment *assessment, char *output, size_t capacity);
#endif
