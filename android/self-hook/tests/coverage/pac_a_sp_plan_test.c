#include "pac_a_sp_plan.h"
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks;
static void check(bool okay, const char *name) {
    checks++;
    if (!okay) { fprintf(stderr, "FAIL: %s\n", name); exit(1); }
}
static enum tp_a64_status plan(const uint32_t words[2], struct tp_pac_a_sp_plan *out) {
    return tp_pac_a_sp_plan_entry(TP_PAC_A_SP_WITHOUT_PAUTH_LR, words, 2, 0xd503233f,
        0x100000, 0x9000000000, 0x200000, 0x201000, out);
}
static void rejected(uint32_t first, uint32_t second, enum tp_a64_status wanted) {
    uint32_t words[] = {first, second};
    struct tp_pac_a_sp_plan out, before;
    memset(&out, 0xa5, sizeof(out)); before = out;
    check(plan(words, &out) == wanted && !memcmp(&out, &before, sizeof(out)), "reject without partial output");
}
static uint64_t branch_target(uint32_t word, uint64_t at) {
    int64_t offset = (int64_t)((word & 0x3ffffff) ^ 0x2000000) - 0x2000000;
    return (uint64_t)((int64_t)at + offset * 4);
}
static void dump(const struct tp_pac_a_sp_plan *p) {
    printf("{\"stackBytes\":%u,\"patchAddress\":%llu,\"patchWord\":%u,\"relay\":[",
        p->stack_bytes, (unsigned long long)p->patch_address, p->branch_word);
    for (unsigned i = 0; i < 6; i++) printf("%s%u", i ? "," : "", p->relay[i]);
    printf("],\"trampoline\":[");
    for (unsigned i = 0; i < 4; i++) printf("%s%u", i ? "," : "", p->trampoline[i]);
    printf("]}");
}
int main(int argc, char **argv) {
    if (argc == 3) {
        uint32_t prefix[2];
        for (unsigned i = 0; i < 2; i++) {
            char *end = NULL;
            unsigned long long value = strtoull(argv[i + 1], &end, 16);
            if (!end || *end || value > UINT32_MAX) return 2;
            prefix[i] = (uint32_t)value;
        }
        struct tp_pac_a_sp_plan prefix_plan;
        enum tp_a64_status status = plan(prefix, &prefix_plan);
        printf("{\"status\":%u}", (unsigned)status);
        return status == TP_A64_OK ? 0 : 1;
    }
    if (argc != 1) return 2;
    struct tp_pac_a_sp_plan out, sub, stp;
    for (unsigned amount = 16; amount <= 4080; amount += 16) {
        uint32_t words[] = {0xd503233f, 0xd10003ff | amount << 10};
        check(plan(words, &out) == TP_A64_OK && out.stack_bytes == amount && out.frame_kind == TP_PAC_SUB_SP,
            "aligned SUB SP admitted");
        check(out.trampoline[2] == words[1] && out.trampoline_unwind[2].cfa_sp_offset == amount
            && out.trampoline_unwind[2].saved_lr_cfa_offset == 0, "SUB relocation and CFA");
    }
    for (unsigned amount = 16; amount <= 512; amount += 16) {
        uint32_t encoded = (128u - amount / 8u) & 127u;
        uint32_t words[] = {0xd503233f, 0xa9807bfd | encoded << 15};
        check(plan(words, &out) == TP_A64_OK && out.stack_bytes == amount && out.frame_kind == TP_PAC_STP_FP_LR_PREINDEX,
            "aligned negative STP FP LR preindex admitted");
        check(out.trampoline[2] == words[1] && out.trampoline_unwind[2].saved_lr_cfa_offset == 8 - (int)amount
            && out.trampoline_unwind[2].saved_fp_cfa_offset == -(int)amount, "STP signed LR/FP unwind slots");
    }
    uint32_t words[] = {0xd503233f, 0xd10083ff};
    check(plan(words, &sub) == TP_A64_OK, "representative SUB");
    words[1] = 0xa9be7bfd;
    check(plan(words, &stp) == TP_A64_OK, "representative STP");
    check(stp.preserved_paciasp == 0xd503233f && stp.patch_address == 0x100004, "original PAC landing retained");
    check(branch_target(stp.branch_word, stp.patch_address) == 0x200000, "target patch reaches relay");
    check(branch_target(stp.trampoline[3], 0x20100c) == 0x100008, "original trampoline returns after frame instruction");
    check(stp.relay[1] == 0xd50323bf && stp.trampoline[1] == 0xd503233f, "exact A-key sign and authenticate");
    check(stp.relay[4] == 0 && stp.relay[5] == 0x90, "far replacement literal remains 64 bit");
    check(stp.relay_unwind[0].lr_signed_with_entry_sp == 1
        && stp.relay_unwind[1].pc_offset == 8 && stp.relay_unwind[1].lr_signed_with_entry_sp == 0,
        "relay signature state changes only after AUTIASP");
    check(stp.trampoline_unwind[0].lr_signed_with_entry_sp == 0
        && stp.trampoline_unwind[1].pc_offset == 8 && stp.trampoline_unwind[1].lr_signed_with_entry_sp == 1,
        "trampoline signature state changes only after PACIASP");
    rejected(0xd503237f, 0xa9be7bfd, TP_A64_UNSUPPORTED); // B key.
    rejected(0xdac1a3fe, 0xa9be7bfd, TP_A64_UNSUPPORTED); // PACIASPPC, LLVM oracle checked.
    rejected(0xd503245f, 0xa9be7bfd, TP_A64_UNSUPPORTED); // Do not replace PAC with a broader landing.
    rejected(0xd503233f, 0xd10003ff, TP_A64_UNSUPPORTED); // Zero frame.
    rejected(0xd503233f, 0xd10023ff, TP_A64_UNSUPPORTED); // Misaligned 8-byte frame.
    rejected(0xd503233f, 0xd14007ff, TP_A64_UNSUPPORTED); // Shifted immediate outside admitted shape.
    rejected(0xd503233f, 0xa9817bfd, TP_A64_UNSUPPORTED); // Positive writeback.
    rejected(0xd503233f, 0xa9bf7bfc, TP_A64_UNSUPPORTED); // Unexpected FP register.
    rejected(0xd503233f, 0xd10083de, TP_A64_UNSUPPORTED); // Writes LR instead of SP.
    rejected(0xd503233f, 0x94000001, TP_A64_UNSUPPORTED); // BL must not be copied.
    rejected(0xd503233f, 0x14000001, TP_A64_UNSUPPORTED); // Branches outside admitted frame shape.
    rejected(0xd503233f, 0xd50323bf, TP_A64_UNSUPPORTED); // Authentication is not a frame instruction.
    rejected(0xd503233f, 0xd65f03c0, TP_A64_UNSUPPORTED);
    struct bad_case { enum tp_pac_profile profile; uint32_t replacement_word;
        uint64_t entry, replacement, relay, trampoline; enum tp_a64_status wanted; };
    struct bad_case bad[] = {
        {TP_PAC_PROFILE_UNKNOWN,0xd503233f,0x100000,0x9000000000,0x200000,0x201000,TP_A64_UNSUPPORTED},
        {TP_PAC_PAUTH_LR_PRESENT,0xd503233f,0x100000,0x9000000000,0x200000,0x201000,TP_A64_UNSUPPORTED},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503237f,0x100000,0x9000000000,0x200000,0x201000,TP_A64_UNSUPPORTED},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503245f,0x100000,0x9000000000,0x200000,0x201000,TP_A64_UNSUPPORTED},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100002,0x9000000000,0x200000,0x201000,TP_A64_ALIGNMENT},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100000,0x9000000000,0x200004,0x201000,TP_A64_ALIGNMENT},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,UINT64_MAX-3,0x9000000000,0x200000,0x201000,TP_A64_OVERFLOW},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100000,0x9000000000,UINT64_MAX-7,0x201000,TP_A64_OVERFLOW},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100ffc,0x9000000000,0x200000,0x201000,TP_A64_RANGE},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100000,0x100004,0x200000,0x201000,TP_A64_OVERLAP},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100000,0x9000000000,0x200000,0x200010,TP_A64_OVERLAP},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100000,0x9000000000,0x8100008,0x201000,TP_A64_RANGE},
        {TP_PAC_A_SP_WITHOUT_PAUTH_LR,0xd503233f,0x100000,0x9000000000,0x200000,0x9000000,TP_A64_RANGE},
    };
    for (unsigned i = 0; i < sizeof(bad)/sizeof(bad[0]); i++) {
        struct tp_pac_a_sp_plan before;
        memset(&out, 0x55, sizeof(out)); before = out;
        check(tp_pac_a_sp_plan_entry(bad[i].profile, words, 2, bad[i].replacement_word,
            bad[i].entry, bad[i].replacement, bad[i].relay, bad[i].trampoline, &out) == bad[i].wanted
            && !memcmp(&out, &before, sizeof(out)), "invalid profile/address is transactional rejection");
    }
    check(tp_pac_a_sp_plan_entry(TP_PAC_A_SP_WITHOUT_PAUTH_LR, words, 2, 0xd503233f,
        0x100000, 0x9000000000, 0x8100000, 0x201000, &out) == TP_A64_OK,
        "largest positive patch displacement");
    check(tp_pac_a_sp_plan_entry(TP_PAC_A_SP_WITHOUT_PAUTH_LR, words, 2, 0xd503233f,
        0x10000004, 0x9000000000, 0x8000008, 0x10001000, &out) == TP_A64_OK,
        "negative patch displacement boundary");
    check(tp_pac_a_sp_plan_entry(TP_PAC_A_SP_WITHOUT_PAUTH_LR, NULL, 2, 0xd503233f,
        0x100000, 0x9000000000, 0x200000, 0x201000, &out) == TP_A64_ARGUMENT, "null input");
    check(tp_pac_a_sp_plan_entry(TP_PAC_A_SP_WITHOUT_PAUTH_LR, words, 1, 0xd503233f,
        0x100000, 0x9000000000, 0x200000, 0x201000, &out) == TP_A64_ARGUMENT, "short source");
    printf("{\"checks\":%u,\"deviceExecuted\":false,\"runtimeOwnershipProven\":false,\"samples\":[", checks);
    dump(&sub); printf(","); dump(&stp); printf("]}\n");
    return 0;
}
