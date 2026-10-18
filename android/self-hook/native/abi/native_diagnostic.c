#include "native_hook_internal.h"
#include <string.h>
static _Thread_local struct tp_native_error last;
void tp_native_error_reset(void){
    memset(&last,0,sizeof(last));last.stage="NONE";last.handle=UINT32_MAX;
}
bool tp_native_reject(enum tp_native_status status,const char *stage,int system_error){
    last.status=status;last.stage=stage;last.system_error=system_error;return false;
}
void tp_native_error_location(unsigned handle,int tid,uintptr_t pc){last.handle=handle;last.tid=tid;last.pc=pc;}
void tp_native_error_plan(int status){last.planner_status=status;}
void tp_native_last_error(struct tp_native_error *out){if(out)*out=last;}
const char *tp_native_status_name(enum tp_native_status status){
    static const char *const names[]={"OK","ARGUMENT","STALE_MODULE","CAPACITY","ABI","IMAGE","PREFIX",
        "ADDRESS","RESOURCE","STATE","CONFLICT","PREIMAGE","PARK","BUSY"};
    return (unsigned)status<sizeof(names)/sizeof(*names)?names[status]:"INVALID_STATUS";
}
bool tp_native_resources(struct tp_native_resources *out){
    if(!out)return false;if(pthread_mutex_lock(&tp_native_mutation))tp_native_stop(228);
    struct tp_native_resources result={.issued=tp_native_used,.inbound_used=TP_NATIVE_ENTRY_LIMIT-tp_scalar_inbound_remaining()};
    for(unsigned i=0;i<tp_native_used;i++){
        struct tp_native_binding *binding=&tp_native_bindings[i];unsigned phase=atomic_load_explicit(&binding->phase,memory_order_acquire);
        if(phase==TP_NATIVE_PREPARED)result.prepared++;else if(phase==TP_NATIVE_ACTIVE)result.active++;
        else if(phase==TP_NATIVE_RETIRED)result.retired++;else result.failed++;
        result.in_flight+=atomic_load_explicit(&binding->active_calls,memory_order_acquire);
        if(binding->bridge.handle)result.retained_images++;
    }
    *out=result;if(pthread_mutex_unlock(&tp_native_mutation))tp_native_stop(229);return true;
}
