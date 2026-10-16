#ifndef TP_NATIVE_PREFIX_H
#define TP_NATIVE_PREFIX_H
#include <stdbool.h>
#include "tripoder_a64.h"
/* Exact first-instruction stack effects for the original trampoline FDE.
 * reg uses AArch64 DWARF numbering. offset is an unsigned CFA subtraction. */
struct tp_native_saved {uint8_t reg,offset;bool present;};
struct tp_native_prefix {
    struct tp_a64_entry_plan entry;
    uint32_t original[4];
    unsigned frame;
    struct tp_native_saved saved[2];
    bool signed_ra,entry_signed;
};
/* Single replacement strategy: preserve entry BTI, displace one proven
 * instruction, seal code/FDE before publishing a near direct B. No installation
 * or ownership is performed here. Classic PACIASP signs with SP only; no B-key,
 * PC-modified PAC, authenticated return or unknown stack instruction accepted. */
enum tp_a64_status tp_native_prefix_plan(const uint32_t *input,size_t available,
    uintptr_t entry,uintptr_t replacement,uintptr_t trampoline,struct tp_native_prefix *out);
/* Read-only opcode/stack classification. Final near-range, page and ownership
 * checks are still required by prepare/publication. */
bool tp_native_prefix_supported(const uint32_t *input,size_t available,uintptr_t entry);
enum tp_a64_status tp_native_address_range(const uint32_t *input,size_t available,uintptr_t entry,
    uintptr_t relay_offset,uintptr_t original_offset,size_t bytes,uintptr_t *low,uintptr_t *high);
#endif
