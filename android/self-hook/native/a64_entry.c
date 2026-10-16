#include "tripoder_a64.h"
#include "a64_internal.h"
#include <string.h>

enum tp_a64_status tp_a64_plan_entry(const uint32_t *input, size_t available,
                                    uint64_t entry, uint64_t replacement,
                                    uint64_t trampoline,
                                    struct tp_a64_entry_plan *output) {
    if (input == NULL || output == NULL || available == 0 || available > TP_A64_MAX_WORDS)
        return TP_A64_ARGUMENT;
    if (((entry | replacement | trampoline) & 3) != 0) return TP_A64_ALIGNMENT;
    size_t prefix = (input[0] == TP_A64_BTI_C || input[0] == TP_A64_BTI_JC) ? 1 : 0;
    if (input[0] == TP_A64_BTI_J) return TP_A64_UNSUPPORTED; /* Not a callable BTI entry. */
    if (available <= prefix) return TP_A64_ARGUMENT;
    uint64_t bytes = (prefix + 1) * 4;
    if (entry > UINT64_MAX - bytes || trampoline > UINT64_MAX - 12)
        return TP_A64_OVERFLOW;
    if ((entry < trampoline + 12 && trampoline < entry + bytes) ||
        (replacement >= entry && replacement < entry + bytes) ||
        (replacement >= trampoline && replacement < trampoline + 12))
        return TP_A64_OVERLAP;
    struct tp_a64_entry_plan plan;
    memset(&plan, 0, sizeof(plan));
    plan.patch_address = entry + prefix * 4;
    plan.preserved_prefix_bytes = (uint32_t)(prefix * 4);
    plan.expected_word = input[prefix];
    struct tp_reference ref;
    enum tp_a64_status checked = tp_a64_reference(plan.expected_word, plan.patch_address, &ref);
    if (checked != TP_A64_OK) return checked;
    if ((ref.kind == TP_REF_BRANCH && ref.address >= entry && ref.address < plan.patch_address) ||
        (ref.kind != TP_REF_NONE && ref.address < trampoline + 12 && ref.address + ref.bytes > trampoline))
        return TP_A64_SELF_REFERENCE;
    enum tp_a64_status status = tp_a64_branch(plan.patch_address, replacement, &plan.branch_word);
    if (status != TP_A64_OK) return status;
    plan.trampoline[0] = TP_A64_BTI_C;
    status = tp_a64_relocate(input + prefix, 1, plan.patch_address, trampoline + 4, &plan.trampoline[1]);
    if (status != TP_A64_OK) return status;
    status = tp_a64_branch(trampoline + 8, entry + bytes, &plan.trampoline[2]);
    if (status != TP_A64_OK) return status;
    memcpy(output, &plan, sizeof(plan));
    return TP_A64_OK;
}
const char *tp_a64_status_name(enum tp_a64_status status) {
    switch (status) {
        case TP_A64_OK: return "OK";
        case TP_A64_ARGUMENT: return "ARGUMENT";
        case TP_A64_ALIGNMENT: return "ALIGNMENT";
        case TP_A64_OVERFLOW: return "ADDRESS_OVERFLOW";
        case TP_A64_RANGE: return "DISPLACEMENT_RANGE";
        case TP_A64_UNSUPPORTED: return "INSTRUCTION_UNSUPPORTED";
        case TP_A64_SELF_REFERENCE: return "CODE_SELF_REFERENCE";
        case TP_A64_OVERLAP: return "REGION_OVERLAP";
    }
    return "INVALID_STATUS";
}
