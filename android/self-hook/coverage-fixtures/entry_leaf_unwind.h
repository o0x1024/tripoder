#ifndef TP_ENTRY_LEAF_UNWIND_H
#define TP_ENTRY_LEAF_UNWIND_H
#include <stdbool.h>
#include <stdint.h>
struct tp_leaf_unwind_report { unsigned attempts,samples,leaf_mask,state_mask,signal_frames,failures; bool restored,joined; uintptr_t leaf,caller; };
struct tp_leaf_sample_range {
    uintptr_t leaf,caller;
    unsigned post_offset;
    uintptr_t (*call)(void *,uintptr_t);
};
bool tp_entry_leaf_unwind(void *owned,void *foreign,int diagnostic_fd,const struct tp_leaf_sample_range *,struct tp_leaf_unwind_report *report);
#endif
