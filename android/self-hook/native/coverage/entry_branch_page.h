#ifndef TP_ENTRY_BRANCH_PAGE_H
#define TP_ENTRY_BRANCH_PAGE_H
#include "../code_page.h"
struct tp_entry_branch_page {
    struct tp_code_page page;
    uint32_t patched[TP_CODE_PAGE_WORDS];
    uintptr_t entry,leaf;
    unsigned commits,parked_threads;
    int park_status;
    bool prepared,active,restored;
};
/* Exact PACIASP; STP FP,LR,[SP,-32]!; STP x20,x19,[SP,16] entry only.
 * The first two instructions stay intact; +8 is the only patched instruction.
 * Caller proves the complete fixed image/function and no independent entry at +8. No PAC authentication
 * or resigning is introduced. All code/FDE leases precede branch publication. */
bool tp_entry_branch_prepare(struct tp_entry_branch_page *,uintptr_t entry,uintptr_t leaf,
    const char *file,const uint32_t image[static TP_CODE_PAGE_WORDS]);
bool tp_entry_branch_publish(struct tp_entry_branch_page *,bool restore);
#endif
