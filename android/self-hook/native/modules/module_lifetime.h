#ifndef TP_MODULE_LIFETIME_H
#define TP_MODULE_LIFETIME_H
#include <stdbool.h>
#include <stdint.h>
#include <stdatomic.h>
#define TP_MODULE_LIMIT 4096u
#define TP_MODULE_PATH_LIMIT 1024u
/* A generation is never reused, including when the linker recycles a link_map
 * or mapping address. References cover pending publication AND active hooks.
 * They do not pin a loader handle or postpone dlclose. */
struct tp_module_slot {
    _Atomic uint64_t state;
    _Atomic uintptr_t map,base;
};
struct tp_module_seen { uintptr_t map,base; };
struct tp_module_snapshot { uint32_t generation,references;uintptr_t base,map;bool live,ambiguous; };
enum tp_module_change { TP_MODULE_CHANGE_OK,TP_MODULE_CHANGE_INVALID,TP_MODULE_CHANGE_IN_USE,TP_MODULE_CHANGE_EXHAUSTED };
enum tp_module_change tp_module_reconcile(struct tp_module_slot *,struct tp_module_seen);
bool tp_module_snapshot(const struct tp_module_slot *,struct tp_module_snapshot *);
bool tp_module_acquire(struct tp_module_slot *,uint32_t generation);
bool tp_module_release(struct tp_module_slot *,uint32_t generation);
#endif
