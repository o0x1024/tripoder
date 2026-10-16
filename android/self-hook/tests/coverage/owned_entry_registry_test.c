#include "owned_entry_registry.h"
#include "tripoder_a64.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>

static unsigned checks;
static void check(bool good, const char *name) {
    checks++;
    if (!good) { fprintf(stderr, "FAILED: %s\n", name); exit(1); }
}
struct existing_owner { _Atomic uintptr_t entry; };
static struct tp_entry_owner_registry registry = TP_ENTRY_OWNER_REGISTRY_INIT;
static struct existing_owner owner = { .entry = 0x4000 };
static struct tp_entry_owner_policy policy = { .method = 0x1000, .owner = &owner,
    .operation_generation = 7, .desired_entry = &owner.entry };
static _Atomic unsigned errors;
static _Atomic bool start;
static void *reader(void *unused) {
    (void)unused;
    while (!atomic_load_explicit(&start, memory_order_acquire)) { }
    for (unsigned i = 0; i < 100000; i++) {
        uintptr_t selected = 0;
        enum tp_entry_route result = tp_entry_owner_select(&registry, 0x1000, 0x9000, &selected);
        if (!((result == TP_ENTRY_NOT_OWNED && selected == 0x9000)
            || (result == TP_ENTRY_SELECTED && (selected == 0x4000 || selected == 0x8000))))
            atomic_fetch_add_explicit(&errors, 1, memory_order_relaxed);
        if (tp_entry_owner_select(&registry, 0x2000, 0xa000, &selected) != TP_ENTRY_NOT_OWNED
            || selected != 0xa000) atomic_fetch_add_explicit(&errors, 1, memory_order_relaxed);
    }
    return NULL;
}
int main(void) {
    pthread_t readers[4];
    for (unsigned i = 0; i < 4; i++) check(pthread_create(&readers[i], NULL, reader, NULL) == 0, "reader creation");
    atomic_store_explicit(&start, true, memory_order_release);
    check(tp_entry_owner_register(&registry, &policy) == TP_ENTRY_REGISTERED, "single ownership publication");
    for (unsigned i = 0; i < 100000; i++) atomic_store_explicit(&owner.entry,
        (i & 1) ? 0x4000 : 0x8000, memory_order_release);
    for (unsigned i = 0; i < 4; i++) check(pthread_join(readers[i], NULL) == 0, "reader join");
    check(atomic_load(&errors) == 0, "publication and selection do not cross methods");
    check(tp_entry_owner_register(&registry, &policy) == TP_ENTRY_ALREADY_REGISTERED, "same immutable policy is idempotent");
    struct tp_entry_owner_policy conflict = policy;
    conflict.operation_generation++;
    check(tp_entry_owner_register(&registry, &conflict) == TP_ENTRY_CONFLICT, "generation cannot replace process lease");
    uintptr_t unchanged = 0x12345678;
    atomic_store(&owner.entry, 0);
    check(tp_entry_owner_select(&registry, 0x1000, 0x9000, &unchanged) == TP_ENTRY_SELECTION_REJECTED
        && unchanged == 0x12345678, "owned invalid selection cannot pass through");
    atomic_store(&owner.entry, 0x4001);
    check(tp_entry_owner_select(&registry, 0x1000, 0x9000, &unchanged) == TP_ENTRY_SELECTION_REJECTED,
        "A64 entry alignment");
    struct tp_entry_owner_policy invalid = policy;
    invalid.operation_generation = 0;
    check(tp_entry_owner_register(&registry, &invalid) == TP_ENTRY_POLICY_INVALID, "generation required");
    struct tp_entry_owner_registry bounded = TP_ENTRY_OWNER_REGISTRY_INIT;
    struct tp_entry_owner_policy policies[TP_ENTRY_OWNER_LIMIT + 1];
    for (unsigned i = 0; i <= TP_ENTRY_OWNER_LIMIT; i++) {
        policies[i] = policy; policies[i].method = 0x1000 + i * 32;
        check(tp_entry_owner_register(&bounded, &policies[i]) == (i == TP_ENTRY_OWNER_LIMIT
            ? TP_ENTRY_REGISTRY_FULL : TP_ENTRY_REGISTERED), "bounded registry admission");
    }
    // Exercise the actual existing planner, not a duplicated PAC whitelist.
    uint32_t pac[] = {0xd503233f, 0xa9be7bfd};
    struct tp_a64_entry_plan plan = { .patch_address = 0xdeadbeef };
    check(tp_a64_plan_entry(pac, 2, 0x514d8c, 0x600000, 0x601000, &plan) == TP_A64_UNSUPPORTED,
        "actual UpdateEntryPoints PAC prologue rejected by existing planner");
    check(plan.patch_address == 0xdeadbeef, "PAC rejection leaves output untouched");
    printf("{\"checks\":%u,\"concurrentSelections\":800000,\"errors\":%u,"
        "\"installedInterposition\":false,\"writerClosureComplete\":false,\"pacEntry\":\"rejected\"}\n",
        checks, atomic_load(&errors));
    return 0;
}
