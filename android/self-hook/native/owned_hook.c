#define _GNU_SOURCE
#include "tripoder_owned_hook.h"
#include "tripoder_a64.h"
#include "code_page.h"
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

enum phase { CLOSED, PREPARED, ACTIVE };
static struct {
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    enum phase phase;
    bool writing;
    unsigned calls, mutations;
    uint64_t generation, epoch;
    void *module, *entry, *trampoline;
    uint64_t *callback_slot;
    tp_owned_callback callback;
    struct tp_code_page entry_page, trampoline_page;
    uint32_t patched[TP_CODE_PAGE_WORDS], displaced[TP_CODE_PAGE_WORDS];
} slot = {.mutex = PTHREAD_MUTEX_INITIALIZER};
static pthread_once_t once = PTHREAD_ONCE_INIT;
static _Thread_local struct { bool inside, callback, original; uint64_t generation; } caller;
static const uint32_t original[TP_CODE_PAGE_WORDS] = {TP_A64_BTI_C, UINT32_C(0x91000400), UINT32_C(0xd65f03c0), [3 ... TP_CODE_PAGE_WORDS-1] = TP_A64_NOP};
static const uint32_t empty[TP_CODE_PAGE_WORDS] = {[0 ... TP_CODE_PAGE_WORDS-1] = TP_A64_NOP};

static void init(void) {
    pthread_condattr_t attr;
    if (pthread_condattr_init(&attr) || pthread_condattr_setclock(&attr, CLOCK_MONOTONIC) ||
        pthread_cond_init(&slot.condition, &attr) || pthread_condattr_destroy(&attr)) tp_code_fatal();
}
static void lock(void) {
    if (pthread_once(&once, init) || pthread_mutex_lock(&slot.mutex)) tp_code_fatal();
}
static enum tp_owned_status unlock(enum tp_owned_status status) {
    if (pthread_mutex_unlock(&slot.mutex)) tp_code_fatal();
    return status;
}
static void reopen(void) {
    slot.writing = false;
    if (pthread_cond_broadcast(&slot.condition)) tp_code_fatal();
}
static bool valid(uint64_t generation) { return generation == slot.generation && slot.phase != CLOSED; }
static enum tp_owned_status drain(void) {
    if (slot.writing) return TP_OWNED_BUSY;
    slot.writing = true;
    struct timespec deadline;
    if (clock_gettime(CLOCK_MONOTONIC, &deadline) != 0) tp_code_fatal();
    deadline.tv_nsec += 100000000;
    if (deadline.tv_nsec >= 1000000000) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000; }
    while (slot.calls != 0) {
        int status = pthread_cond_timedwait(&slot.condition, &slot.mutex, &deadline);
        if (status == ETIMEDOUT) { reopen(); return TP_OWNED_BUSY; }
        if (status != 0) tp_code_fatal();
    }
    return TP_OWNED_OK;
}
static uint64_t replacement(uint64_t argument) {
    if (!caller.inside || caller.callback || caller.generation != slot.generation) tp_code_fatal();
    caller.callback = true;
    uint64_t result = slot.callback(caller.generation, argument);
    caller.callback = false;
    return result;
}

enum tp_owned_status tp_owned_open(const char *path, tp_owned_callback callback, uint64_t *generation) {
    if (caller.inside) return TP_OWNED_REENTRANT;
    if (path == NULL || path[0] != '/' || callback == NULL || generation == NULL) return TP_OWNED_ARGUMENT;
    if (sysconf(_SC_PAGESIZE) != 4096) return TP_OWNED_PLATFORM;
    lock();
    if (slot.phase != CLOSED || slot.writing) return unlock(TP_OWNED_BUSY);
    if (slot.generation == UINT64_MAX) return unlock(TP_OWNED_STATE);
    void *module = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (module == NULL) return unlock(TP_OWNED_IDENTITY);
    void *entry = dlsym(module, "tp_fixture_target");
    void *trampoline = dlsym(module, "tp_fixture_trampoline");
    void *relay = dlsym(module, "tp_fixture_replacement");
    uint64_t *callback_slot = dlsym(module, "tp_fixture_callback");
    Dl_info entry_info, trampoline_info;
    struct tp_code_page entry_page = {0}, trampoline_page = {0};
    enum tp_owned_status failure = TP_OWNED_IDENTITY;
    if (entry == NULL || trampoline == NULL || !dladdr(entry, &entry_info) || !dladdr(trampoline, &trampoline_info)
        || strcmp(entry_info.dli_fname, path) || strcmp(trampoline_info.dli_fname, path)
        || entry_info.dli_fbase != trampoline_info.dli_fbase
        || (uintptr_t)trampoline != (uintptr_t)entry + 4096) goto reject;
    Dl_info relay_info, callback_info;
    if (relay == NULL || callback_slot == NULL || !dladdr(relay, &relay_info) || !dladdr(callback_slot, &callback_info)
        || relay_info.dli_fbase != entry_info.dli_fbase || callback_info.dli_fbase != entry_info.dli_fbase
        || (uintptr_t)relay != (uintptr_t)trampoline + 4096) goto reject;
    struct tp_page_identity relay_identity, callback_identity;
    if (!tp_code_page_identity(relay, "r-xp", &relay_identity)
        || !tp_code_page_identity(callback_slot, "rw-p", &callback_identity)
        || relay_identity.inode == 0 || callback_identity.inode == 0) goto reject;
    for (unsigned i = 0; i < 512; i++) if (callback_slot[i] != 0) goto reject;
    struct tp_a64_entry_plan plan;
    uint32_t displaced[TP_CODE_PAGE_WORDS];
    failure = TP_OWNED_PLAN;
    if (tp_a64_plan_entry(original, 3, (uintptr_t)entry, (uintptr_t)relay,
                          (uintptr_t)trampoline, &plan) != TP_A64_OK) goto reject;
    failure = TP_OWNED_IDENTITY;
    if (!tp_code_page_claim(&entry_page, entry, path, original)
        || !tp_code_page_claim(&trampoline_page, trampoline, path, empty)) goto reject;
    failure = TP_OWNED_PERMISSION;
    memcpy(displaced, empty, sizeof(displaced)); memcpy(displaced, plan.trampoline, sizeof(plan.trampoline));
    if (tp_code_page_publish(&trampoline_page, empty, displaced) != 1) goto reject;
    /* Fixed u64(u64) ABI relay: x16 is caller-saved IP0; x0 and LR are preserved.
     * This is the sole H2a path, not an out-of-range fallback for arbitrary ABI. */
    *callback_slot = (uintptr_t)replacement;
    slot.module = module; slot.entry = entry; slot.trampoline = trampoline;
    slot.callback_slot = callback_slot;
    slot.entry_page = entry_page; slot.trampoline_page = trampoline_page;
    memcpy(slot.patched, original, sizeof(original)); slot.patched[1] = plan.branch_word;
    memcpy(slot.displaced, displaced, sizeof(slot.displaced));
    slot.callback = callback; slot.calls = 0; slot.mutations = 1; slot.epoch = 0;
    slot.generation++; slot.phase = PREPARED;
    *generation = slot.generation;
    return unlock(TP_OWNED_OK);
reject:
    tp_code_page_release(&trampoline_page, empty, empty);
    tp_code_page_release(&entry_page, original, original);
    if (dlclose(module) != 0) tp_code_fatal();
    return unlock(failure);
}

void tp_owned_pages(struct tp_owned_page_report *result) {
    if (result == NULL) return;
    lock(); tp_code_pages_report(result); unlock(TP_OWNED_OK);
}
#ifdef TP_SELF_HOOK_TESTING
void tp_owned_test_reject_next_prepare(void) {
    lock(); tp_code_pages_reject_next_prepare(); unlock(TP_OWNED_OK);
}
#endif

enum tp_owned_status tp_owned_enable(uint64_t generation, bool enabled) {
    if (caller.inside) return TP_OWNED_REENTRANT;
    lock();
    if (!valid(generation)) return unlock(TP_OWNED_STALE);
    if ((slot.phase == ACTIVE) == enabled) return unlock(TP_OWNED_STATE);
    if (slot.mutations >= 1024 || slot.epoch == UINT64_MAX) return unlock(TP_OWNED_STATE);
    enum tp_owned_status drained = drain();
    if (drained != TP_OWNED_OK) return unlock(drained);
    /* Other mutators cannot pass writing; readers cannot be in either code page. */
    if (!tp_code_page_current(&slot.trampoline_page, slot.displaced)) tp_code_fatal();
    int changed = tp_code_page_publish(&slot.entry_page,
                                     enabled ? original : slot.patched, enabled ? slot.patched : original);
    if (changed == 0) tp_code_fatal(); /* Our explicit ownership was violated. */
    if (changed == 1) {
        slot.phase = enabled ? ACTIVE : PREPARED;
        slot.epoch++; slot.mutations++;
    }
    reopen();
    return unlock(changed == 1 ? TP_OWNED_OK : TP_OWNED_PERMISSION);
}

enum tp_owned_status tp_owned_call(uint64_t generation, uint64_t argument, struct tp_owned_result *result) {
    if (caller.inside) return TP_OWNED_REENTRANT;
    if (result == NULL) return TP_OWNED_ARGUMENT;
    int incoming_errno = errno;
    lock();
    while (valid(generation) && slot.writing)
        if (pthread_cond_wait(&slot.condition, &slot.mutex)) tp_code_fatal();
    if (!valid(generation)) return unlock(TP_OWNED_STALE);
    if (slot.calls == UINT_MAX) return unlock(TP_OWNED_BUSY);
    struct tp_owned_result value = {.epoch = slot.epoch, .hooked = slot.phase == ACTIVE};
    uint64_t (*target)(uint64_t) = (uint64_t (*)(uint64_t))slot.entry;
    slot.calls++; /* Covers the interval BEFORE entering code, not just callback time. */
    unlock(TP_OWNED_OK);
    caller.inside = true; caller.callback = false; caller.original = false; caller.generation = generation;
    errno = incoming_errno;
    /* Mutex admission orders data; every executing CPU also synchronizes its instruction stream. */
    __asm__ volatile("isb" ::: "memory");
    value.value = target(argument);
    int result_errno = errno;
    value.original_calls = caller.original ? 1 : 0;
    caller.inside = false;
    lock();
    slot.calls--;
    if (slot.calls == 0 && pthread_cond_broadcast(&slot.condition)) tp_code_fatal();
    unlock(TP_OWNED_OK);
    *result = value;
    errno = result_errno;
    return TP_OWNED_OK;
}

enum tp_owned_status tp_owned_original(uint64_t generation, uint64_t argument, uint64_t *result) {
    if (result == NULL) return TP_OWNED_ARGUMENT;
    if (!caller.inside || !caller.callback || caller.generation != generation) return TP_OWNED_STATE;
    if (caller.original) return TP_OWNED_ORIGINAL_TWICE;
    caller.original = true; /* Consume before call: exceptions/signals are unsupported. */
    __asm__ volatile("isb" ::: "memory");
    *result = ((uint64_t (*)(uint64_t))slot.trampoline)(argument);
    return TP_OWNED_OK;
}

enum tp_owned_status tp_owned_info(uint64_t generation, struct tp_owned_info *result) {
    if (result == NULL) return TP_OWNED_ARGUMENT;
    lock();
    if (!valid(generation)) return unlock(TP_OWNED_STALE);
    *result = (struct tp_owned_info){slot.generation, slot.epoch, (uintptr_t)slot.entry,
        (uintptr_t)slot.trampoline, slot.mutations, slot.calls, slot.phase == ACTIVE};
    return unlock(TP_OWNED_OK);
}

enum tp_owned_status tp_owned_close(uint64_t generation) {
    if (caller.inside) return TP_OWNED_REENTRANT;
    lock();
    if (!valid(generation)) return unlock(TP_OWNED_STALE);
    if (slot.phase == ACTIVE) return unlock(TP_OWNED_STATE);
    enum tp_owned_status drained = drain();
    if (drained != TP_OWNED_OK) return unlock(drained);
    /* Restore the saved file mappings before handing the address range back
     * to the linker. All readers are drained; no anonymous executable view leaks. */
    tp_code_page_release(&slot.entry_page, original, original);
    tp_code_page_release(&slot.trampoline_page, slot.displaced, empty);
    *slot.callback_slot = 0;
    if (dlclose(slot.module) != 0) tp_code_fatal();
    slot.phase = CLOSED; slot.module = NULL; slot.entry = NULL; slot.trampoline = NULL; slot.callback = NULL;
    slot.callback_slot = NULL;
    reopen();
    return unlock(TP_OWNED_OK);
}
