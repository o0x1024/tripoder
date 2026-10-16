#ifndef TRIPODER_A64_H
#define TRIPODER_A64_H

#include <stddef.h>
#include <stdint.h>

/* Offline plans only. No memory reads, executable writes or thread-safety claims. */
enum tp_a64_status {
    TP_A64_OK = 0,
    TP_A64_ARGUMENT,
    TP_A64_ALIGNMENT,
    TP_A64_OVERFLOW,
    TP_A64_RANGE,
    TP_A64_UNSUPPORTED,
    TP_A64_SELF_REFERENCE,
    TP_A64_OVERLAP
};

#define TP_A64_MAX_WORDS 16
#define TP_A64_BTI_C UINT32_C(0xd503245f)
#define TP_A64_BTI_J UINT32_C(0xd503249f)
#define TP_A64_BTI_JC UINT32_C(0xd50324df)
#define TP_A64_NOP UINT32_C(0xd503201f)

/* All output parameters remain unchanged on failure. Addresses are virtual,
 * untagged, caller-attested addresses; this API does not validate mappings.
 * ADRP retains the original page base; downstream address uses, code/data
 * self-observation and other incoming branches require separate caller proof. */
enum tp_a64_status tp_a64_branch(uint64_t from, uint64_t to, uint32_t *word);
enum tp_a64_status tp_a64_relocate(const uint32_t *input, size_t count,
                                  uint64_t source, uint64_t destination,
                                  uint32_t *output);

struct tp_a64_entry_plan {
    uint64_t patch_address;
    uint32_t expected_word;
    uint32_t branch_word;
    uint32_t trampoline[3]; /* BTI c; relocated instruction; direct B back. */
    uint32_t preserved_prefix_bytes;
};

/* Exactly one displaced instruction, optionally after entry BTI c/jc.
 * This is not install/activate: it cannot establish ownership, quiescence,
 * W^X permission, instruction visibility, ABI or source-code identity. */
enum tp_a64_status tp_a64_plan_entry(const uint32_t *input, size_t available,
                                    uint64_t entry, uint64_t replacement,
                                    uint64_t trampoline,
                                    struct tp_a64_entry_plan *output);
const char *tp_a64_status_name(enum tp_a64_status status);

#endif
