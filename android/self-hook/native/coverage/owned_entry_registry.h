#ifndef TP_OWNED_ENTRY_REGISTRY_H
#define TP_OWNED_ENTRY_REGISTRY_H
#include <stdbool.h>
#include <stdatomic.h>
#include <stdint.h>
#include "../../include/tripoder_hook_limits.h"

#define TP_ENTRY_OWNER_LIMIT TP_MANAGED_ENTRY_LIMIT

/* Pure registration/routing only: does not install any interposition, modify
 * ArtMethods, authorize activation, or prove writer closure.
 * The existing owner retains Method/Class/loader roots and supplies policy.
 * Policy/context storage is immutable and retained until process exit.
 * desired_entry points directly into that owner; this is a read view, not a
 * second lifecycle authority. Readers use bounded acquire loads, never call
 * back into C/Java while running inside an ART writer. */
struct tp_entry_owner_policy {
    uintptr_t method;
    const void *owner;
    uint64_t operation_generation; /* Supplied by the existing Runtime owner. */
    const _Atomic uintptr_t *desired_entry;
};
struct tp_entry_owner_registry {
    _Atomic(const struct tp_entry_owner_policy *) slots[TP_ENTRY_OWNER_LIMIT];
    atomic_flag registering;
};
#define TP_ENTRY_OWNER_REGISTRY_INIT { .slots = {0}, .registering = ATOMIC_FLAG_INIT }
extern struct tp_entry_owner_registry tp_art_entry_owners;

enum tp_entry_registration {
    TP_ENTRY_REGISTERED,
    TP_ENTRY_ALREADY_REGISTERED,
    TP_ENTRY_CONFLICT,
    TP_ENTRY_REGISTRATION_BUSY,
    TP_ENTRY_REGISTRY_FULL,
    TP_ENTRY_POLICY_INVALID
};
enum tp_entry_route {
    TP_ENTRY_NOT_OWNED,
    TP_ENTRY_SELECTED,
    TP_ENTRY_SELECTION_REJECTED
};

enum tp_entry_registration tp_entry_owner_register(struct tp_entry_owner_registry *registry,
    const struct tp_entry_owner_policy *policy);
/* NOT_OWNED returns requested_entry unchanged. SELECTED supplies the existing
 * owner's entry to the original writer; the writer must retain its CAS/zombie
 * bookkeeping. REJECTED does not provide an entry and must never silently pass
 * through a write for an owned method. No action is taken by this function. */
enum tp_entry_route tp_entry_owner_select(const struct tp_entry_owner_registry *registry,
    uintptr_t method, uintptr_t requested_entry, uintptr_t *selected_entry);
#endif
