#include "pac_platform.h"
#include <string.h>

/* Numeric values are the Linux ARM64 UAPI, asserted against NDK headers in
 * the collector; the evaluator remains portable for independent host tests. */
#define PACA (UINT64_C(1) << 30)
#define CPUID (UINT64_C(1) << 11)
#define BTI (UINT64_C(1) << 17)

void tp_pac_platform_assess(const struct tp_pac_platform_report *r,
    struct tp_pac_platform_assessment *out) {
    if (!out) return;
    memset(out, 0, sizeof(*out));
    out->reasons = TP_PAC_KERNEL_CONTRACT_UNVERIFIED;
    if (!r || r->schema != 1) { out->reasons |= TP_PAC_AUXV_UNOBSERVED; return; }
    if (r->pointer_bits != 64 || memcmp(r->machine, "aarch64", sizeof("aarch64")) != 0) {
        out->reasons |= TP_PAC_NOT_AARCH64;
        if (!r->uname_error) out->status = TP_PAC_PLATFORM_REJECTED;
    }
    if (r->hwcap.status != TP_PAC_VALUE_PRESENT || r->hwcap2.status != TP_PAC_VALUE_PRESENT)
        out->reasons |= TP_PAC_AUXV_UNOBSERVED;
    if (r->hwcap.status == TP_PAC_VALUE_PRESENT) {
        if (!(r->hwcap.value & PACA)) { out->reasons |= TP_PAC_ADDRESS_AUTH_NOT_ADVERTISED; out->status = TP_PAC_PLATFORM_REJECTED; }
        if (!(r->hwcap.value & CPUID)) out->reasons |= TP_PAC_CPUID_ABI_UNAVAILABLE;
    }
    if (r->hwcap2.status == TP_PAC_VALUE_PRESENT && !(r->hwcap2.value & BTI)) {
        out->reasons |= TP_PAC_BTI_NOT_ADVERTISED; out->status = TP_PAC_PLATFORM_REJECTED;
    }
    if (r->enabled_keys.status != TP_PAC_VALUE_PRESENT) out->reasons |= TP_PAC_KEY_STATE_UNOBSERVED;
    else if (!(r->enabled_keys.value & 1)) { out->reasons |= TP_PAC_APIA_KEY_DISABLED; out->status = TP_PAC_PLATFORM_REJECTED; }
    if (r->page_size.status != TP_PAC_VALUE_PRESENT) out->reasons |= TP_PAC_AUXV_UNOBSERVED;
    else if (r->page_size.value != 4096) { out->reasons |= TP_PAC_PAGE_SIZE_UNSUPPORTED; out->status = TP_PAC_PLATFORM_REJECTED; }
    if (r->id_status != TP_PAC_IDS_LINUX_USER_VIEW) { out->reasons |= TP_PAC_IDS_UNOBSERVED; return; }
    out->apa = (unsigned)((r->isar1 >> 4) & 15);
    out->api = (unsigned)((r->isar1 >> 8) & 15);
    out->apa3 = (unsigned)((r->isar2 >> 12) & 15);
    out->pacm = (unsigned)((r->isar3 >> 12) & 15);
    if (out->apa == 6 || out->api == 6 || out->apa3 == 6) {
        out->reasons |= TP_PAC_PAUTH_LR_OBSERVED; out->status = TP_PAC_PLATFORM_REJECTED;
    }
    if (out->apa > 6 || out->api > 6 || out->apa3 > 6 || out->pacm > 2)
        out->reasons |= TP_PAC_ID_ENCODING_UNKNOWN;
    if (!(out->apa | out->api | out->apa3)) out->reasons |= TP_PAC_ZERO_ID_IS_NOT_ABSENCE;
    // Even apparently classic 1..5 values need the actual vendor kernel's
    // exposure/sanitization/heterogeneous-CPU contract. ISAR3.PACM=0 may just be
    // masked or reserved in that kernel. Do not return an admitted profile.
}
