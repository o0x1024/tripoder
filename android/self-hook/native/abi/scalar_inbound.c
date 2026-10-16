#include "scalar_inbound.h"
#include "../park_syscall.h"
#include <errno.h>
#include <stdatomic.h>
#include <string.h>
struct binding {
    struct tp_scalar_signature signature;
    struct tp_scalar_layout layout;
    tp_scalar_callback callback;
    tp_register_callback register_callback;
    void *context;
    _Atomic bool ready;
};
static struct binding bindings[TP_NATIVE_ENTRY_LIMIT];
__attribute__((visibility("hidden"))) struct tp_scalar_admission tp_scalar_admissions[TP_NATIVE_ENTRY_LIMIT];
static _Atomic unsigned used;
extern const unsigned char tp_scalar_inbound_entries[];
_Static_assert(ATOMIC_INT_LOCK_FREE==2&&ATOMIC_BOOL_LOCK_FREE==2,"inline publication atomics required");
_Static_assert(sizeof(struct tp_scalar_frame)==200&&offsetof(struct tp_scalar_frame,stack)==128
    &&offsetof(struct tp_scalar_frame,stack_size)==192&&sizeof(struct tp_scalar_return)==16,"inbound assembly ABI");
static struct binding *reserve(unsigned *index){
    unsigned slot=atomic_load_explicit(&used,memory_order_relaxed);
    do{if(slot==TP_NATIVE_ENTRY_LIMIT)return NULL;}
    while(!atomic_compare_exchange_weak_explicit(&used,&slot,slot+1,memory_order_relaxed,memory_order_relaxed));
    *index=slot;return &bindings[slot];
}
enum tp_scalar_error tp_scalar_inbound_prepare(const struct tp_scalar_signature *signature,tp_scalar_callback callback,void *context,struct tp_scalar_inbound *out){
    if(!signature||!callback||!out)return TP_SCALAR_NULL;
    struct tp_scalar_signature snapshot=*signature;struct tp_scalar_layout layout;
    enum tp_scalar_error error=tp_scalar_plan(&snapshot,&layout);if(error)return error;
    unsigned slot;struct binding *binding=reserve(&slot);if(!binding)return TP_SCALAR_BUDGET;
    binding->signature=snapshot;binding->layout=layout;
    binding->context=context;binding->callback=callback;atomic_store_explicit(&binding->ready,true,memory_order_release);
    *out=(struct tp_scalar_inbound){slot,(void (*)(void))(tp_scalar_inbound_entries+(size_t)slot*16)};return TP_SCALAR_OK;
}
enum tp_scalar_error tp_register_inbound_prepare(tp_register_callback callback,void *context,struct tp_scalar_inbound *out){
    if(!callback||!out)return TP_SCALAR_NULL;
    unsigned slot;struct binding *binding=reserve(&slot);if(!binding)return TP_SCALAR_BUDGET;
    binding->register_callback=callback;binding->context=context;atomic_store_explicit(&binding->ready,true,memory_order_release);
    *out=(struct tp_scalar_inbound){slot,(void (*)(void))(tp_scalar_inbound_entries+(size_t)slot*16)};return TP_SCALAR_OK;
}
unsigned tp_scalar_inbound_remaining(void){return TP_NATIVE_ENTRY_LIMIT-atomic_load_explicit(&used,memory_order_relaxed);}
bool tp_scalar_inbound_admission(unsigned slot,_Atomic uint64_t *state,uint32_t generation,_Atomic unsigned *active){
    if(slot>=TP_NATIVE_ENTRY_LIMIT||!state||!generation||!active||tp_scalar_admissions[slot].state
       ||!atomic_load_explicit(&bindings[slot].ready,memory_order_acquire))return false;
    tp_scalar_admissions[slot].identity=((uint64_t)generation<<32)|TP_MODULE_LIVE_BIT;
    tp_scalar_admissions[slot].active=active;tp_scalar_admissions[slot].state=state;return true;
}
extern const unsigned char tp_scalar_inbound_entries_end[],tp_scalar_inbound_common[],tp_scalar_inbound_admitted[];
extern const unsigned char tp_scalar_inbound_admit[],tp_scalar_inbound_admit_end[];
__attribute__((noinline)) bool tp_scalar_inbound_pending_pc(uintptr_t pc){
    return (pc>=(uintptr_t)tp_scalar_inbound_entries&&pc<(uintptr_t)tp_scalar_inbound_entries_end)
        ||(pc>=(uintptr_t)tp_scalar_inbound_common&&pc<(uintptr_t)tp_scalar_inbound_admitted)
        ||(pc>=(uintptr_t)tp_scalar_inbound_admit&&pc<(uintptr_t)tp_scalar_inbound_admit_end);
}
#ifdef TP_SELF_HOOK_TESTING
bool tp_scalar_inbound_test_gate(unsigned slot,unsigned value){
    if(slot>=TP_NATIVE_ENTRY_LIMIT||!tp_scalar_admissions[slot].state||value>1)return false;
    atomic_store_explicit(&tp_scalar_admissions[slot].test_gate,value,memory_order_release);return true;
}
unsigned tp_scalar_inbound_test_state(unsigned slot){return slot<TP_NATIVE_ENTRY_LIMIT?atomic_load(&tp_scalar_admissions[slot].test_gate):0;}
#endif
static __attribute__((noreturn)) void invalid_callback(void){
    (void)tp_park_syscall(SYS_exit_group,204,0,0,0,0,0);__builtin_trap();
}
/* Called only by the immutable assembly entry. Capture TLS errno before any
 * callback/JNI/allocation, and restore the explicit result after all work.
 * Neither entry dispatch nor original invocation shares per-call globals. */
__attribute__((visibility("hidden"))) void tp_scalar_inbound_dispatch(unsigned slot,struct tp_scalar_frame *frame,const uint64_t *incoming_stack,struct tp_scalar_return *raw){
    int *cell=&errno;int incoming_errno=*cell;
    if(slot>=TP_NATIVE_ENTRY_LIMIT)invalid_callback();
    const struct binding *binding=&bindings[slot];if(!atomic_load_explicit(&binding->ready,memory_order_acquire))invalid_callback();
    if(binding->register_callback){
        struct tp_scalar_return result={0};int returned_errno=incoming_errno;
        if(!binding->register_callback(binding->context,frame,incoming_stack,incoming_errno,&result,&returned_errno))invalid_callback();
        *raw=result;*cell=returned_errno;return;
    }
    unsigned stack_size=binding->layout.stack_size;frame->stack_size=stack_size;frame->reserved=0;
    /* Read only stack space allocated by this exact scalar signature. */
    for(unsigned i=0;i<stack_size/8;i++)frame->stack[i]=incoming_stack[i];
    struct tp_scalar_value values[TP_SCALAR_MAX_ARGS];
    if(tp_scalar_capture(&binding->signature,frame,values)!=TP_SCALAR_OK)invalid_callback();
    struct tp_scalar_result result={0};
    if(!binding->callback(binding->context,values,binding->signature.count,incoming_errno,&result)
        ||result.value.type!=binding->signature.result)invalid_callback();
    if(binding->layout.result.width==4&&(result.value.bits>>32))invalid_callback();
    if(result.value.type==TP_SCALAR_VOID&&result.value.bits)invalid_callback();
    *raw=(struct tp_scalar_return){0};
    if(result.value.type!=TP_SCALAR_VOID){
        if(binding->layout.result.bank==TP_SCALAR_FPR)raw->fpr=result.value.bits;else raw->gpr=result.value.bits;
    }
    *cell=result.error_number;
}
