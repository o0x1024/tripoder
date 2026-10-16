#ifndef TRIPODER_SCALAR_CAPTURE_H
#define TRIPODER_SCALAR_CAPTURE_H
#include "scalar_abi.h"
struct tp_scalar_capture_state {
    struct tp_scalar_frame frame;
    uint64_t entry_sp,return_gpr,return_fpr;
    int *errno_cell;
    int32_t entry_errno,return_errno;
};
extern struct tp_scalar_capture_state tp_scalar_capture_state;
struct tp_scalar_fixture_report {uint32_t passed,checks,failures,cases;uint64_t concurrent_calls;uint32_t concurrent_errors,hook_installs;};
int tp_scalar_fixture_run(struct tp_scalar_fixture_report *report);
#endif
