#ifndef TP_PAC_A_SP_PLAN_H
#define TP_PAC_A_SP_PLAN_H
#include "tripoder_a64.h"

/* Offline plans only. The caller must establish this profile from real
 * platform evidence. A PACIASP encoding alone is not sufficient: PAuth_LR
 * with PACM=1 adds a PC modifier and changes AUTIASP's X16 semantics. */
enum tp_pac_profile {
    TP_PAC_PROFILE_UNKNOWN = 0,
    TP_PAC_A_SP_WITHOUT_PAUTH_LR = 1,
    TP_PAC_PAUTH_LR_PRESENT = 2
};
enum tp_pac_frame_kind { TP_PAC_SUB_SP, TP_PAC_STP_FP_LR_PREINDEX };

struct tp_pac_unwind_row {
    unsigned pc_offset;
    unsigned cfa_sp_offset;
    int saved_lr_cfa_offset; /* 0 means LR remains in x30. */
    int saved_fp_cfa_offset; /* 0 means FP remains in x29. */
    unsigned lr_signed_with_entry_sp;
};
struct tp_pac_a_sp_plan {
    uint64_t entry, patch_address;
    uint32_t preserved_paciasp, expected_word, branch_word;
    uint32_t relay[6]; /* BTI c; AUTIASP; LDR x16,literal; BR x16; 64-bit target */
    uint32_t trampoline[4]; /* BTI c; PACIASP; original frame instruction; B entry+8 */
    enum tp_pac_frame_kind frame_kind;
    unsigned stack_bytes;
    struct tp_pac_unwind_row relay_unwind[2];
    struct tp_pac_unwind_row trampoline_unwind[3];
};

/* Exact admitted shapes: PACIASP followed by aligned SUB SP,SP,#imm or
 * STP x29,x30,[SP,#negative]!. Replacement must itself start PACIASP.
 * The original entry's PAC/BTI semantics are preserved at entry+0.
 * Requires real BTI mappings, registered per-PC unwind metadata, verified
 * executable mappings/code identity, no foreign entry into entry+4, and a
 * safe publication/lifetime protocol before any installation. This API
 * proves none of those runtime properties and performs no code writes.
 * Every output byte is unchanged on failure. */
enum tp_a64_status tp_pac_a_sp_plan_entry(enum tp_pac_profile profile,
    const uint32_t *input, size_t available, uint32_t replacement_first_word,
    uint64_t entry, uint64_t replacement, uint64_t relay, uint64_t trampoline,
    struct tp_pac_a_sp_plan *output);
#endif
