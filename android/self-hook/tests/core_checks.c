#include "core_checks.h"
#include "tripoder_a64.h"
#include <string.h>

#define CHECK(label, expression) do { result.checks++; if (!(expression)) { \
    result.failures++; if (result.first_failure == NULL) result.first_failure = label; \
} } while (0)

struct tp_check_result tp_core_checks(void) {
    struct tp_check_result result = {0, 0, NULL};
    uint32_t word = 0, output[TP_A64_MAX_WORDS], input[TP_A64_MAX_WORDS];
    CHECK("B forward", tp_a64_branch(0x1000, 0x1080, &word) == TP_A64_OK && word == 0x14000020);
    CHECK("B backward", tp_a64_branch(0x1080, 0x1000, &word) == TP_A64_OK && word == 0x17ffffe0);
    CHECK("B self", tp_a64_branch(0x1000, 0x1000, &word) == TP_A64_OK && word == 0x14000000);
    CHECK("B positive limit", tp_a64_branch(0x10000000, 0x17fffffc, &word) == TP_A64_OK && word == 0x15ffffff);
    CHECK("B negative limit", tp_a64_branch(0x10000000, 0x08000000, &word) == TP_A64_OK && word == 0x16000000);
    word = 0xdeadbeef;
    CHECK("B positive overflow", tp_a64_branch(0x10000000, 0x18000000, &word) == TP_A64_RANGE && word == 0xdeadbeef);
    CHECK("B negative overflow", tp_a64_branch(0x10000000, 0x07fffffc, &word) == TP_A64_RANGE && word == 0xdeadbeef);
    CHECK("B high address", tp_a64_branch(0, UINT64_MAX - 3, &word) == TP_A64_RANGE);
    CHECK("B source alignment", tp_a64_branch(1, 4, &word) == TP_A64_ALIGNMENT);
    CHECK("B target alignment", tp_a64_branch(0, 5, &word) == TP_A64_ALIGNMENT);
    CHECK("B null", tp_a64_branch(0, 4, NULL) == TP_A64_ARGUMENT);

    /* Expected values are also checked against the NDK assembler by host_check.py. */
    const struct { uint32_t source, moved; } vectors[] = {
        {TP_A64_NOP, TP_A64_NOP}, {TP_A64_BTI_C, TP_A64_BTI_C},
        {TP_A64_BTI_J, TP_A64_BTI_J}, {TP_A64_BTI_JC, TP_A64_BTI_JC},
        {0xd65f03c0,0xd65f03c0},
        {0x91000400, 0x91000400}, {0xd10083ff, 0xd10083ff},
        {0x14000020, 0x17fffc20}, {0x54000400, 0x54ff8400},
        {0xb5000407, 0xb5ff8407}, {0xb7500408, 0xb7578408},
        {0x10000403, 0x10ff8403}, {0xb0000004, 0x90000004},
        {0x58000405, 0x58ff8405}, {0x18000405, 0x18ff8405},
        {0x98000405, 0x98ff8405}
    };
    for (size_t i = 0; i < sizeof(vectors)/sizeof(vectors[0]); i++) {
        CHECK("relocation vector", tp_a64_relocate(&vectors[i].source, 1, 0x1000, 0x2000, output) == TP_A64_OK && output[0] == vectors[i].moved);
    }
    const uint32_t unsupported[] = {
        0, 0xffffffff, 0x94000020, /* BL */
        0xd61f0200, /* BR x16 */ 0xd503233f, /* PACIASP */ 0xd50323bf, /* AUTIASP */
        0xd4000001, /* SVC */ 0x1c000405, /* SIMD literal */ 0xd8000405, /* PRFM */
        0x5400040e, 0x5400040f, 0x54000410, /* excluded cond/reserved */
        0x91800400 /* reserved ADD immediate bit */
    };
    for (size_t i = 0; i < sizeof(unsupported)/sizeof(unsupported[0]); i++) {
        output[0] = 0xfeedcafe;
        CHECK("unsupported unchanged", tp_a64_relocate(&unsupported[i], 1, 0x1000, 0x2000, output) == TP_A64_UNSUPPORTED && output[0] == 0xfeedcafe);
    }
    uint32_t branch_block[] = {0x14000002, TP_A64_NOP, 0x17fffffe};
    CHECK("internal branches mapped", tp_a64_relocate(branch_block, 3, 0x1000, 0x2000, output) == TP_A64_OK && memcmp(branch_block, output, sizeof(branch_block)) == 0);
    uint32_t end_branch = 0x14000001;
    CHECK("end branch not mapped", tp_a64_relocate(&end_branch, 1, 0x1000, 0x2000, output) == TP_A64_OK && output[0] == 0x17fffc01);
    uint32_t literal_self = 0x58000000;
    CHECK("literal self rejected", tp_a64_relocate(&literal_self, 1, 0x1000, 0x2000, output) == TP_A64_SELF_REFERENCE);
    uint32_t literal_overlap = 0x58ffffe0; /* ldr x0, PC-4; reads 8 bytes. */
    CHECK("literal partial overlap", tp_a64_relocate(&literal_overlap, 1, 0x1000, 0x2000, output) == TP_A64_SELF_REFERENCE);
    uint32_t adr_self = 0x10000000;
    CHECK("ADR self rejected", tp_a64_relocate(&adr_self, 1, 0x1000, 0x2000, output) == TP_A64_SELF_REFERENCE);
    uint32_t backward = 0x17ffffff;
    CHECK("negative target overflow", tp_a64_relocate(&backward, 1, 0, 0x2000, output) == TP_A64_OVERFLOW);
    uint32_t forward = 0x14000002;
    CHECK("positive target overflow", tp_a64_relocate(&forward, 1, UINT64_MAX - 7, 0x2000, output) == TP_A64_OVERFLOW);
    uint32_t cond = 0x54000400;
    CHECK("conditional range", tp_a64_relocate(&cond, 1, 0x1000, 0x200000, output) == TP_A64_RANGE);
    CHECK("empty", tp_a64_relocate(input, 0, 0x1000, 0x2000, output) == TP_A64_ARGUMENT);
    CHECK("capacity", tp_a64_relocate(input, TP_A64_MAX_WORDS + 1, 0x1000, 0x2000, output) == TP_A64_ARGUMENT);
    CHECK("source null", tp_a64_relocate(NULL, 1, 0x1000, 0x2000, output) == TP_A64_ARGUMENT);
    CHECK("destination null", tp_a64_relocate(input, 1, 0x1000, 0x2000, NULL) == TP_A64_ARGUMENT);
    CHECK("source alignment", tp_a64_relocate(input, 1, 0x1001, 0x2000, output) == TP_A64_ALIGNMENT);
    CHECK("destination alignment", tp_a64_relocate(input, 1, 0x1000, 0x2001, output) == TP_A64_ALIGNMENT);
    CHECK("window overflow", tp_a64_relocate(input, 1, UINT64_MAX - 3, 0x2000, output) == TP_A64_OVERFLOW);
    CHECK("output window overflow", tp_a64_relocate(input, 1, 0x1000, UINT64_MAX - 3, output) == TP_A64_OVERFLOW);
    input[0] = TP_A64_NOP; input[1] = 0; output[0] = 123; output[1] = 456;
    CHECK("transactional rejection", tp_a64_relocate(input, 2, 0x1000, 0x2000, output) == TP_A64_UNSUPPORTED && output[0] == 123 && output[1] == 456);
    input[1] = TP_A64_NOP;
    CHECK("virtual overlap", tp_a64_relocate(input, 2, 0x1000, 0x1004, output) == TP_A64_OVERLAP);
    CHECK("input output alias", tp_a64_relocate(input, 2, 0x1000, 0x2000, input) == TP_A64_OK && input[0] == TP_A64_NOP && input[1] == TP_A64_NOP);

    struct tp_a64_entry_plan plan, sentinel;
    memset(&sentinel, 0x5a, sizeof(sentinel)); plan = sentinel;
    input[0] = TP_A64_BTI_C; input[1] = 0x91000400;
    CHECK("BTI entry", tp_a64_plan_entry(input, 2, 0x1000, 0x1800, 0x2000, &plan) == TP_A64_OK &&
          plan.patch_address == 0x1004 && plan.preserved_prefix_bytes == 4 &&
          plan.expected_word == 0x91000400 && plan.branch_word == 0x140001ff &&
          plan.trampoline[0] == TP_A64_BTI_C && plan.trampoline[1] == 0x91000400 && plan.trampoline[2] == 0x17fffc00);
    plan = sentinel;
    input[1] = 0xd503233f;
    CHECK("PAC rejected atomically", tp_a64_plan_entry(input, 2, 0x1000, 0x1800, 0x2000, &plan) == TP_A64_UNSUPPORTED && memcmp(&plan, &sentinel, sizeof(plan)) == 0);
    CHECK("truncated BTI", tp_a64_plan_entry(input, 1, 0x1000, 0x1800, 0x2000, &plan) == TP_A64_ARGUMENT);
    input[0] = TP_A64_BTI_J;
    CHECK("BTI j not callable", tp_a64_plan_entry(input, 2, 0x1000, 0x1800, 0x2000, &plan) == TP_A64_UNSUPPORTED);
    input[0] = TP_A64_NOP;
    CHECK("plain entry", tp_a64_plan_entry(input, 1, 0x1000, 0x1800, 0x2000, &plan) == TP_A64_OK && plan.preserved_prefix_bytes == 0);
    CHECK("replacement self", tp_a64_plan_entry(input, 1, 0x1000, 0x1000, 0x2000, &plan) == TP_A64_OVERLAP);
    CHECK("trampoline overlap", tp_a64_plan_entry(input, 1, 0x1000, 0x1800, 0x0ffc, &plan) == TP_A64_OVERLAP);
    CHECK("replacement trampoline", tp_a64_plan_entry(input, 1, 0x1000, 0x2004, 0x2000, &plan) == TP_A64_OVERLAP);
    CHECK("entry displacement limit", tp_a64_plan_entry(input, 1, 0x1000, 0x1800, 0x10000000, &plan) == TP_A64_RANGE);
    CHECK("entry argument", tp_a64_plan_entry(NULL, 1, 0x1000, 0x1800, 0x2000, &plan) == TP_A64_ARGUMENT);
    CHECK("entry alignment", tp_a64_plan_entry(input, 1, 0x1001, 0x1800, 0x2000, &plan) == TP_A64_ALIGNMENT);
    CHECK("entry address overflow", tp_a64_plan_entry(input, 1, UINT64_MAX - 3, 0x1800, 0x2000, &plan) == TP_A64_OVERFLOW);
    CHECK("entry trampoline overflow", tp_a64_plan_entry(input, 1, 0x1000, 0x1800, UINT64_MAX - 3, &plan) == TP_A64_OVERFLOW);
    uint32_t back_to_prefix[] = {0x17ffffff, 0x54ffffe0, 0xb5ffffe7, 0xb757ffe8};
    for (size_t i = 0; i < sizeof(back_to_prefix)/sizeof(back_to_prefix[0]); i++) {
        input[0] = TP_A64_BTI_C; input[1] = back_to_prefix[i]; plan = sentinel;
        CHECK("branch into preserved prefix", tp_a64_plan_entry(input, 2, 0x1000, 0x1800, 0x2000, &plan) == TP_A64_SELF_REFERENCE && memcmp(&plan, &sentinel, sizeof(plan)) == 0);
    }
    uint32_t into_output[] = {0x14000400, 0x54008000, 0xb5008007, 0xb7508008, 0x18008000, 0x58008000, 0x10008000};
    for (size_t i = 0; i < sizeof(into_output)/sizeof(into_output[0]); i++) {
        output[0] = 0xdeadbeef; plan = sentinel;
        CHECK("relocation output reference", tp_a64_relocate(&into_output[i], 1, 0x1000, 0x2000, output) == TP_A64_SELF_REFERENCE && output[0] == 0xdeadbeef);
        CHECK("full trampoline reference", tp_a64_plan_entry(&into_output[i], 1, 0x1000, 0x3000, 0x2000, &plan) == TP_A64_SELF_REFERENCE && memcmp(&plan, &sentinel, sizeof(plan)) == 0);
    }
    uint32_t output_last_word = 0x18008040;
    CHECK("trampoline suffix literal", tp_a64_plan_entry(&output_last_word, 1, 0x1000, 0x3000, 0x2000, &plan) == TP_A64_SELF_REFERENCE);
    uint32_t output_overlap = 0x58007fe0;
    CHECK("trampoline partial literal", tp_a64_plan_entry(&output_overlap, 1, 0x1000, 0x3000, 0x2000, &plan) == TP_A64_SELF_REFERENCE);
    return result;
}
