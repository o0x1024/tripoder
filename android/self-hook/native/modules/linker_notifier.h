#ifndef TP_LINKER_NOTIFIER_H
#define TP_LINKER_NOTIFIER_H
#include "module_lifetime.h"
#define TP_MODULE_EVENT_LIMIT 64u
enum tp_module_event_kind {TP_MODULE_ADDED=1,TP_MODULE_REMOVED=2,TP_MODULE_AMBIGUOUS=3,TP_MODULE_UNIQUE=4};
struct tp_module_event {uint64_t sequence;unsigned index;uint32_t generation;uintptr_t base;unsigned kind;};
struct tp_linker_report {
    uintptr_t base,debug,notifier,relay;
    unsigned count,seals,fde_pcs,parked;
    uint64_t notifications,consistent,added,removed;
    bool installed;
    int error;
    const char *stage;
    char loader_name[256];
    unsigned identity_flags;
};
/* Call before guest code. Paths are the immutable, canonical artifact-library
 * allowlist. No module is dlopen/pinned by this observer. It cannot be replaced
 * or removed during this process. Fixed platform identity must be checked by
 * the launcher; Native independently checks loaded Build ID and code page. */
bool tp_linker_install(const char *const *paths,unsigned count);
bool tp_linker_snapshot(unsigned index,struct tp_module_snapshot *);
bool tp_linker_acquire(unsigned index,uint32_t generation);
bool tp_linker_release(unsigned index,uint32_t generation);
/* Stable state cell; used only to configure the immutable native ingress. */
_Atomic uint64_t *tp_linker_admission_state(unsigned index,uint32_t generation);
/* Publication only: caller already owns a parked execution domain. Reject a
 * loader mutation/notifier snapshot that was paused before reconciliation. */
bool tp_linker_publication_ready(unsigned index,uint32_t generation);
void tp_linker_report(struct tp_linker_report *);
bool tp_linker_events(struct tp_module_event output[static TP_MODULE_EVENT_LIMIT],unsigned *count);
#endif
