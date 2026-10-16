#ifndef TP_NEAR_ENTRY_LEAF_H
#define TP_NEAR_ENTRY_LEAF_H
#include <stdbool.h>
#include <stdint.h>
#include "owned_entry_registry.h"

/* A sealed, real ELF mapping with loader-visible FDEs. A successful lease is
 * retained until process exit; code is never edited after it becomes executable.
 * Only this compiled-in template is accepted. No caller-supplied code/path. */
struct tp_near_entry_leaf {
    uintptr_t base,leaf,resume;
    unsigned post_offset,bytes,seals,fde_ranges,fde_pcs;
    int fd,error;
    void *handle;
    const char *stage;
    char detail[256];
};
bool tp_near_entry_leaf_prepare(uintptr_t resume,struct tp_entry_owner_registry *,struct tp_near_entry_leaf *);
#endif
