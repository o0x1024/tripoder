#include "pac_a_sp_plan.h"
#include <stdbool.h>
#include <string.h>

#define PACIASP UINT32_C(0xd503233f)
#define AUTIASP UINT32_C(0xd50323bf)

static bool overlaps(uint64_t left, uint64_t size, uint64_t right, uint64_t other_size) {
    return left < right + other_size && right < left + size;
}
static bool frame(uint32_t word, enum tp_pac_frame_kind *kind, unsigned *bytes) {
    if ((word & UINT32_C(0xffc003ff)) == UINT32_C(0xd10003ff)) {
        unsigned amount = (word >> 10) & 4095u;
        if (!amount || (amount & 15u)) return false;
        *kind = TP_PAC_SUB_SP; *bytes = amount; return true;
    }
    if ((word & UINT32_C(0xffc07fff)) == UINT32_C(0xa9807bfd)) {
        unsigned encoded = (word >> 15) & 127u;
        int amount = ((int)(encoded ^ 64u) - 64) * 8;
        if (amount >= 0 || ((unsigned)(-amount) & 15u)) return false;
        *kind = TP_PAC_STP_FP_LR_PREINDEX; *bytes = (unsigned)(-amount); return true;
    }
    return false;
}

enum tp_a64_status tp_pac_a_sp_plan_entry(enum tp_pac_profile profile,
    const uint32_t *input, size_t available, uint32_t replacement_first_word,
    uint64_t entry, uint64_t replacement, uint64_t relay, uint64_t trampoline,
    struct tp_pac_a_sp_plan *output) {
    if (!input || !output || available < 2 || available > TP_A64_MAX_WORDS)
        return TP_A64_ARGUMENT;
    if (profile != TP_PAC_A_SP_WITHOUT_PAUTH_LR || input[0] != PACIASP || replacement_first_word != PACIASP)
        return TP_A64_UNSUPPORTED;
    if (((entry | replacement | relay | trampoline) & 3u) || (relay & 7u)) return TP_A64_ALIGNMENT;
    if (entry > UINT64_MAX - 8 || replacement > UINT64_MAX - 8 || relay > UINT64_MAX - 24
        || trampoline > UINT64_MAX - 16) return TP_A64_OVERFLOW;
    // The fixed publisher's 4 KiB source-page contract must contain both words.
    if ((entry & 4095u) > 4088) return TP_A64_RANGE;
    if (overlaps(entry, 8, replacement, 8) || overlaps(entry, 8, relay, 24)
        || overlaps(entry, 8, trampoline, 16) || overlaps(replacement, 8, relay, 24)
        || overlaps(replacement, 8, trampoline, 16) || overlaps(relay, 24, trampoline, 16))
        return TP_A64_OVERLAP;
    struct tp_pac_a_sp_plan plan;
    memset(&plan, 0, sizeof(plan));
    if (!frame(input[1], &plan.frame_kind, &plan.stack_bytes)) return TP_A64_UNSUPPORTED;
    plan.entry = entry; plan.patch_address = entry + 4;
    plan.preserved_paciasp = PACIASP; plan.expected_word = input[1];
    enum tp_a64_status status = tp_a64_branch(entry + 4, relay, &plan.branch_word);
    if (status != TP_A64_OK) return status;
    plan.relay[0] = TP_A64_BTI_C; plan.relay[1] = AUTIASP;
    plan.relay[2] = UINT32_C(0x58000050); // LDR x16, PC+8, literal at relay+16.
    plan.relay[3] = UINT32_C(0xd61f0200); // BR x16: ABI scratch, compatible BTI tail call.
    plan.relay[4] = (uint32_t)replacement;
    plan.relay[5] = (uint32_t)(replacement >> 32);
    plan.trampoline[0] = TP_A64_BTI_C; plan.trampoline[1] = PACIASP;
    plan.trampoline[2] = input[1]; // The admitted instructions have no PC-relative operand.
    status = tp_a64_branch(trampoline + 12, entry + 8, &plan.trampoline[3]);
    if (status != TP_A64_OK) return status;
    plan.relay_unwind[0] = (struct tp_pac_unwind_row){0, 0, 0, 0, 1};
    plan.relay_unwind[1] = (struct tp_pac_unwind_row){8, 0, 0, 0, 0};
    plan.trampoline_unwind[0] = (struct tp_pac_unwind_row){0, 0, 0, 0, 0};
    plan.trampoline_unwind[1] = (struct tp_pac_unwind_row){8, 0, 0, 0, 1};
    plan.trampoline_unwind[2] = (struct tp_pac_unwind_row){12, plan.stack_bytes, 0, 0, 1};
    if (plan.frame_kind == TP_PAC_STP_FP_LR_PREINDEX) {
        plan.trampoline_unwind[2].saved_lr_cfa_offset = 8 - (int)plan.stack_bytes;
        plan.trampoline_unwind[2].saved_fp_cfa_offset = -(int)plan.stack_bytes;
    }
    memcpy(output, &plan, sizeof(plan));
    return TP_A64_OK;
}
