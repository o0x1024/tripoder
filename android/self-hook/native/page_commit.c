#define _GNU_SOURCE
#include "code_page.h"
#include "tripoder_a64.h"
#include "park_syscall.h"
#include <stddef.h>
#include <sys/mman.h>

__attribute__((noinline)) static void cache_sync(uintptr_t start) {
    uint64_t ctr;
    __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
    uintptr_t data_line = (uintptr_t)4 << ((ctr >> 16) & 15);
    uintptr_t instruction_line = (uintptr_t)4 << (ctr & 15);
    for (uintptr_t at = start & ~(data_line - 1); at < start + 4096; at += data_line)
        __asm__ volatile("dc cvau, %0" : : "r"(at) : "memory");
    __asm__ volatile("dsb ish" : : : "memory");
    for (uintptr_t at = start & ~(instruction_line - 1); at < start + 4096; at += instruction_line)
        __asm__ volatile("ic ivau, %0" : : "r"(at) : "memory");
    __asm__ volatile("dsb ish\n\tisb" : : : "memory");
}

/* No libc, allocator, JNI, linker, logs or externally supplied callbacks. */
int tp_code_page_validate(const struct tp_code_page_update *update) {
    if (update == NULL || update->owner == NULL || update->prepared == NULL || update->committed ||
        update->owner->address != update->destination || update->owner->anonymous != update->was_anonymous) return 0;
    const volatile uint32_t *current = update->destination;
    const volatile uint32_t *prepared = update->prepared;
    for (unsigned i = 0; i < 1024; i++) {
        if (current[i] != update->expected[i] || prepared[i] != update->replacement[i]) return 0;
    }
    return 1;
}
int tp_code_page_commit(struct tp_code_page_update *update) {
    if (!tp_code_page_validate(update)) return 0;
    long moved = tp_park_syscall(SYS_mremap, (long)update->prepared, 4096, 4096,
                                MREMAP_MAYMOVE | MREMAP_FIXED, (long)update->destination, 0);
    if ((void *)moved != update->destination) {
        /* FIXED failure can have destroyed the destination: never resume it. */
        (void)tp_park_syscall(SYS_exit_group, 193, 0, 0, 0, 0, 0);
        __builtin_trap();
    }
    cache_sync((uintptr_t)update->destination);
    update->committed = true;
    return 1;
}
