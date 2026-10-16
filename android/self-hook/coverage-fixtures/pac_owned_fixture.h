#ifndef TP_PAC_OWNED_FIXTURE_H
#define TP_PAC_OWNED_FIXTURE_H
#include <stdint.h>
#define TP_PAC_FIXTURE_ARGS uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, \
    uint64_t a4, uint64_t a5, uint64_t a6, uint64_t a7, uint64_t stack_arg, double f0, double f1
#define TP_PAC_FIXTURE_PASS a0,a1,a2,a3,a4,a5,a6,a7,stack_arg,f0,f1

struct tp_pac_fixture_report {
    unsigned checks, failures;
    unsigned pac_signing_observed, lr_authenticated, stack_restored;
    uint64_t raw_lr, signed_lr, authenticated_lr;
};
/* Owned fixture only. Caller must verify classic A-key/SP platform profile
 * before running. This function does not patch executable memory or ART. */
int tp_pac_fixture_run(struct tp_pac_fixture_report *report);
uint64_t tp_pac_fixture_baseline_stp(TP_PAC_FIXTURE_ARGS);
uint64_t tp_pac_fixture_baseline_sub(TP_PAC_FIXTURE_ARGS);
uint64_t tp_pac_fixture_routed_stp(TP_PAC_FIXTURE_ARGS);
uint64_t tp_pac_fixture_routed_sub(TP_PAC_FIXTURE_ARGS);
uint64_t tp_pac_fixture_original_stp(TP_PAC_FIXTURE_ARGS);
uint64_t tp_pac_fixture_original_sub(TP_PAC_FIXTURE_ARGS);
void tp_pac_fixture_observe_lr(uint64_t output[4]);
#endif
