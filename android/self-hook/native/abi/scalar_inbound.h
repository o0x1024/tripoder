#ifndef TP_SCALAR_INBOUND_H
#define TP_SCALAR_INBOUND_H
#include "scalar_abi.h"
#include "../../include/tripoder_hook_limits.h"
#include "scalar_admission.h"
/* Callback/context and their reachable objects must survive until process
 * exit. Original invocation uses the same scalar_call ABI. The callback must
 * return a value of the declared type and an explicit errno; exceptions may
 * not unwind through this C boundary. Failure is process-fatal, never an ACK. */
typedef bool (*tp_scalar_callback)(void *context,const struct tp_scalar_value *arguments,
    unsigned count,int incoming_errno,struct tp_scalar_result *result);
struct tp_scalar_inbound {unsigned slot;void (*entry)(void);};
enum tp_scalar_error tp_scalar_inbound_prepare(const struct tp_scalar_signature *,tp_scalar_callback,void *context,struct tp_scalar_inbound *);
/* JNI's private adapter validates its own shorty and stack extent. Capture
 * shares the same immutable slots and PAC/FDE frame as public scalar hooks. */
typedef bool (*tp_register_callback)(void *context,const struct tp_scalar_frame *registers,const uint64_t *incoming_stack,
    int incoming_errno,struct tp_scalar_return *result,int *returned_errno);
enum tp_scalar_error tp_register_inbound_prepare(tp_register_callback,void *context,struct tp_scalar_inbound *);
unsigned tp_scalar_inbound_remaining(void);
bool tp_scalar_inbound_admission(unsigned slot,_Atomic uint64_t *state,uint32_t generation,_Atomic unsigned *active);
/* Called only in the parked publication domain. Conservative for all slots. */
bool tp_scalar_inbound_pending_pc(uintptr_t pc);
#ifdef TP_SELF_HOOK_TESTING
bool tp_scalar_inbound_test_gate(unsigned slot,unsigned value);
unsigned tp_scalar_inbound_test_state(unsigned slot);
#endif
#endif
