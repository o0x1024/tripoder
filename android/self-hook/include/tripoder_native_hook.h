#ifndef TRIPODER_NATIVE_HOOK_H
#define TRIPODER_NATIVE_HOOK_H
#include "../native/abi/scalar_abi.h"
enum tp_native_status {TP_NATIVE_OK,TP_NATIVE_ARGUMENT,TP_NATIVE_STALE,TP_NATIVE_CAPACITY,TP_NATIVE_ABI,
    TP_NATIVE_IMAGE,TP_NATIVE_PREFIX,TP_NATIVE_ADDRESS,TP_NATIVE_RESOURCE,TP_NATIVE_STATE,
    TP_NATIVE_CONFLICT,TP_NATIVE_PREIMAGE,TP_NATIVE_PARK,TP_NATIVE_BUSY};
/* Same-thread operation result. A failed operation did not publish; failures
 * after the first committed page terminate instead of returning this record. */
struct tp_native_error {enum tp_native_status status;const char *stage;int system_error,planner_status,tid;unsigned handle;uintptr_t pc;};
void tp_native_last_error(struct tp_native_error *);
const char *tp_native_status_name(enum tp_native_status);
struct tp_native_resources {unsigned issued,prepared,active,retired,failed,in_flight,inbound_used,retained_images;};
bool tp_native_resources(struct tp_native_resources *);
typedef bool (*tp_native_callback)(void *context,uint64_t invocation,const struct tp_scalar_value *arguments,
    unsigned count,int incoming_errno,struct tp_scalar_result *result);
/* One immutable activation. Callback/context survive until process exit.
 * Caller attests the canonical artifact file, export RVA and scalar signature;
 * the core independently checks module generation, executable segment, mapped
 * file/page identity, prefix, near code/FDE and all publication preimages. */
bool tp_native_prepare(unsigned module,uint32_t generation,uintptr_t rva,const char *path,
    const struct tp_scalar_signature *,tp_native_callback,void *context,unsigned *handle);
bool tp_native_publish(const unsigned *handles,unsigned count);
/* PREPARED cancels without publication; ACTIVE restores the original entry.
 * Every activation is one-shot; a new activation captures a new callback. */
bool tp_native_retire(const unsigned *handles,unsigned count);
/* Opaque call ID, never an address. Same live call/thread and at most once;
 * rejected/failed original attempts consume their right before invocation. */
bool tp_native_original(uint64_t invocation,const struct tp_scalar_value *arguments,unsigned count,
    int incoming_errno,struct tp_scalar_result *result);
struct tp_native_report {
    unsigned handle,module,phase,prefix_bytes,frame,seals,fde_pcs;
    uint32_t generation;
    uintptr_t entry,relay,original;
    uint64_t calls,original_calls;
    unsigned active_calls;
    bool signed_ra;
};
bool tp_native_report(unsigned handle,struct tp_native_report *);
#ifdef TP_SELF_HOOK_TESTING
bool tp_native_test_gate(unsigned handle,unsigned value);
unsigned tp_native_test_gate_state(unsigned handle);
void tp_native_test_fail_prepare(unsigned stage);
bool tp_native_test_commit_witness(int fd,const char *text,unsigned length);
#endif
#endif
