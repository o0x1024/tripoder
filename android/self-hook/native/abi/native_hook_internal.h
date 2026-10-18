#ifndef TP_NATIVE_HOOK_INTERNAL_H
#define TP_NATIVE_HOOK_INTERNAL_H
#include "tripoder_native_hook.h"
#include "native_bridge.h"
#include "scalar_inbound.h"
#include "../code_page.h"
#include <pthread.h>
#include <stdatomic.h>
enum tp_native_phase {TP_NATIVE_EMPTY,TP_NATIVE_PREPARED,TP_NATIVE_ACTIVE,TP_NATIVE_RETIRED};
struct tp_native_page {
    struct tp_code_page page;
    uint32_t current[TP_CODE_PAGE_WORDS];
    char path[1024];
    unsigned module;
    uint32_t generation;
    bool used;
};
struct tp_native_binding {
    struct tp_scalar_signature signature;
    tp_native_callback callback;
    void *context;
    unsigned handle,module;
    uint32_t generation;
    uintptr_t entry;
    struct tp_native_page *page;
    struct tp_native_bridge bridge;
    struct tp_scalar_inbound inbound;
    _Atomic unsigned phase;
    _Atomic uint64_t calls,original_calls;
    _Atomic unsigned active_calls;
};
extern pthread_mutex_t tp_native_mutation;
extern struct tp_native_binding tp_native_bindings[TP_NATIVE_ENTRY_LIMIT];
extern unsigned tp_native_used;
/* Called only with mutation lock held, with same-generation module leases. */
struct tp_native_page *tp_native_claim_page(unsigned module,uint32_t generation,uintptr_t entry,const char *path);
bool tp_native_change(const unsigned *,unsigned count,bool enable);
void tp_native_stop(int reason) __attribute__((noreturn));
void tp_native_error_reset(void);
bool tp_native_reject(enum tp_native_status status,const char *stage,int system_error);
void tp_native_error_location(unsigned handle,int tid,uintptr_t pc);
void tp_native_error_plan(int status);
/* Authenticate the current one-shot invocation and its callback implementation.
 * The JNI adapter obtains its immutable signature/context without re-decoding
 * a Java types array. This lookup does not consume the original token. */
void *tp_native_original_context(uint64_t invocation,tp_native_callback expected);
#endif
