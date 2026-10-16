#include "owned_entry_registry.h"
#include <stddef.h>

_Static_assert(ATOMIC_POINTER_LOCK_FREE == 2, "ART writer routing requires lock-free policy pointers");
_Static_assert(offsetof(struct tp_entry_owner_policy, method)==0 && offsetof(struct tp_entry_owner_policy, desired_entry)==24,
    "fixed AArch64 leaf policy layout");
_Static_assert(offsetof(struct tp_entry_owner_registry, slots)==0,"fixed AArch64 leaf slot layout");
struct tp_entry_owner_registry tp_art_entry_owners=TP_ENTRY_OWNER_REGISTRY_INIT;

enum tp_entry_registration tp_entry_owner_register(struct tp_entry_owner_registry *registry,
    const struct tp_entry_owner_policy *policy) {
    if (!registry || !policy || !policy->method || (policy->method & 7) || !policy->owner
        || !policy->operation_generation || !policy->desired_entry
        || ((uintptr_t)policy->desired_entry & 7)) return TP_ENTRY_POLICY_INVALID;
    if (atomic_flag_test_and_set_explicit(&registry->registering, memory_order_acquire))
        return TP_ENTRY_REGISTRATION_BUSY;
    unsigned empty = TP_ENTRY_OWNER_LIMIT;
    enum tp_entry_registration result = TP_ENTRY_REGISTRY_FULL;
    for (unsigned i = 0; i < TP_ENTRY_OWNER_LIMIT; i++) {
        const struct tp_entry_owner_policy *existing = atomic_load_explicit(&registry->slots[i], memory_order_acquire);
        if (!existing) { if (empty == TP_ENTRY_OWNER_LIMIT) empty = i; continue; }
        if (existing->method == policy->method) {
            result = existing == policy ? TP_ENTRY_ALREADY_REGISTERED : TP_ENTRY_CONFLICT;
            goto done;
        }
    }
    if (empty < TP_ENTRY_OWNER_LIMIT) {
        atomic_store_explicit(&registry->slots[empty], policy, memory_order_release);
        result = TP_ENTRY_REGISTERED;
    }
done:
    atomic_flag_clear_explicit(&registry->registering, memory_order_release);
    return result;
}

enum tp_entry_route tp_entry_owner_select(const struct tp_entry_owner_registry *registry,
    uintptr_t method, uintptr_t requested_entry, uintptr_t *selected_entry) {
    if (!registry || !method || !selected_entry) return TP_ENTRY_SELECTION_REJECTED;
    for (unsigned i = 0; i < TP_ENTRY_OWNER_LIMIT; i++) {
        const struct tp_entry_owner_policy *policy = atomic_load_explicit(&registry->slots[i], memory_order_acquire);
        if (!policy || policy->method != method) continue;
        uintptr_t selected = atomic_load_explicit(policy->desired_entry, memory_order_acquire);
        if (!selected || (selected & 3)) return TP_ENTRY_SELECTION_REJECTED;
        *selected_entry = selected;
        return TP_ENTRY_SELECTED;
    }
    *selected_entry = requested_entry;
    return TP_ENTRY_NOT_OWNED;
}
