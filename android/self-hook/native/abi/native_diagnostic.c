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
void tp_native_last_error(struct tp_native_error *out){if(out)*out=last;}
const char *tp_native_status_name(enum tp_native_status status){
    static const char *const names[]={"OK","ARGUMENT","STALE_MODULE","CAPACITY","ABI","IMAGE","PREFIX",
        "ADDRESS","RESOURCE","STATE","CONFLICT","PREIMAGE","PARK","BUSY"};
    return (unsigned)status<sizeof(names)/sizeof(*names)?names[status]:"INVALID_STATUS";
}
