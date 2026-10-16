#include "pac_owned_fixture.h"
#include <string.h>

#define STP_MARKER UINT64_C(0x55aa55aa55aa55aa)
#define SUB_MARKER UINT64_C(0xaa55aa55aa55aa55)
__attribute__((noinline,visibility("hidden")))
uint64_t tp_pac_fixture_replace_stp(TP_PAC_FIXTURE_ARGS) {
    return tp_pac_fixture_original_stp(TP_PAC_FIXTURE_PASS) ^ STP_MARKER;
}
__attribute__((noinline,visibility("hidden")))
uint64_t tp_pac_fixture_replace_sub(TP_PAC_FIXTURE_ARGS) {
    return tp_pac_fixture_original_sub(TP_PAC_FIXTURE_PASS) ^ SUB_MARKER;
}
static uint64_t stack_pointer(void) {
    uint64_t result;
    __asm__ volatile("mov %0,sp" : "=r"(result));
    return result;
}
static void check(struct tp_pac_fixture_report *report, int condition) {
    report->checks++;
    if (!condition) report->failures++;
}
int tp_pac_fixture_run(struct tp_pac_fixture_report *report) {
    if (!report) return 0;
    memset(report, 0, sizeof(*report));
    uint64_t lr[4] = {0};
    tp_pac_fixture_observe_lr(lr);
    report->raw_lr = lr[0]; report->signed_lr = lr[1]; report->authenticated_lr = lr[2];
    report->pac_signing_observed = lr[0] != lr[1];
    report->lr_authenticated = lr[0] == lr[2];
    check(report, report->pac_signing_observed && report->lr_authenticated && !(lr[3] & 15));
    uint64_t before = stack_pointer();
    for (unsigned iteration = 0; iteration < 256; iteration++) {
        uint64_t a0 = UINT64_C(0x8123456789abcdef) ^ iteration;
        uint64_t a1 = 17u + iteration, a2 = UINT64_C(0x100000001) + iteration;
        uint64_t a3 = UINT64_MAX - iteration, a4 = UINT64_C(0x8000000000000000);
        uint64_t a5 = 31, a6 = iteration * 13u, a7 = UINT64_C(0xfedcba9876543210);
        uint64_t stack_arg = UINT64_C(0x3333444455556666) ^ iteration;
        uint64_t f0_bits = UINT64_C(0x7ff8000000001234) ^ iteration; // Quiet NaN payload, passed without arithmetic.
        uint64_t f1_bits = UINT64_C(0x8000000000000000) | iteration;
        double f0, f1;
        memcpy(&f0, &f0_bits, sizeof(f0)); memcpy(&f1, &f1_bits, sizeof(f1));
        uint64_t wanted = (a0+a1+a2+a3+a4+a5+a6+a7+stack_arg) ^ f0_bits ^ f1_bits;
        check(report, tp_pac_fixture_baseline_stp(TP_PAC_FIXTURE_PASS) == wanted);
        check(report, tp_pac_fixture_baseline_sub(TP_PAC_FIXTURE_PASS) == wanted);
        check(report, tp_pac_fixture_routed_stp(TP_PAC_FIXTURE_PASS) == (wanted ^ STP_MARKER));
        check(report, tp_pac_fixture_routed_sub(TP_PAC_FIXTURE_PASS) == (wanted ^ SUB_MARKER));
    }
    report->stack_restored = before == stack_pointer();
    check(report, report->stack_restored == 1);
    return report->failures == 0;
}
