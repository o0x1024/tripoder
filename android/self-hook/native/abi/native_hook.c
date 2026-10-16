#define _GNU_SOURCE
#include "native_hook_internal.h"
#include "../modules/linker_notifier.h"
#include "../park_syscall.h"
#include <link.h>
#include <string.h>
#include <errno.h>
pthread_mutex_t tp_native_mutation=PTHREAD_MUTEX_INITIALIZER;
struct tp_native_binding tp_native_bindings[TP_NATIVE_ENTRY_LIMIT];
unsigned tp_native_used;
static _Atomic uint64_t next_invocation;
struct call {struct call *previous;struct tp_native_binding *binding;uint64_t id,fpcr,fpsr;bool used;};
static _Thread_local struct call *current_call;
static void fp_environment(uint64_t *control,uint64_t *status){__asm__ volatile("mrs %0,fpcr\n\tmrs %1,fpsr":"=r"(*control),"=r"(*status));}
static void fp_restore(uint64_t control,uint64_t status){__asm__ volatile("msr fpcr,%0\n\tmsr fpsr,%1\n\tisb"::"r"(control),"r"(status):"memory");}
void tp_native_stop(int reason){(void)tp_park_syscall(SYS_exit_group,reason,0,0,0,0,0);__builtin_trap();}
static bool callback(void *opaque,const struct tp_scalar_value *args,unsigned count,int incoming_errno,struct tp_scalar_result *out){
    struct tp_native_binding *binding=opaque;
    /* The audited assembly ingress already owns the lease before any TLS/JNI. */
    unsigned phase=atomic_load_explicit(&binding->phase,memory_order_acquire);
    if(phase!=TP_NATIVE_ACTIVE&&phase!=TP_NATIVE_RETIRED)tp_native_stop(207);
    uint64_t number=atomic_fetch_add_explicit(&next_invocation,1,memory_order_relaxed)+1;
    if(!number)tp_native_stop(208);
    struct call call={.previous=current_call,.binding=binding,.id=number};current_call=&call;
    fp_environment(&call.fpcr,&call.fpsr);
    atomic_fetch_add_explicit(&binding->calls,1,memory_order_relaxed);
    bool ok=binding->callback(binding->context,number,args,count,incoming_errno,out);
    current_call=call.previous;
    fp_restore(call.fpcr,call.fpsr);
    /* Entire callback (including JNI/result encoding) finishes before release.
     * No target memory is accessed after this point. Code/contexts are retained. */
    /* The assembly epilogue releases after scalar result/errno encoding. */
    return ok;
}
bool tp_native_original(uint64_t invocation,const struct tp_scalar_value *arguments,unsigned count,int incoming_errno,struct tp_scalar_result *out){
    struct call *call=current_call;
    if(!call||!invocation||call->id!=invocation||call->used)return false;
    call->used=true; /* Failed value/count validation also consumes the token. */
    if(count!=call->binding->signature.count||!out)return false;
    atomic_fetch_add_explicit(&call->binding->original_calls,1,memory_order_relaxed);
    uint64_t control,status;fp_environment(&control,&status);fp_restore(call->fpcr,call->fpsr);
    bool ok=tp_scalar_call(&call->binding->signature,(void (*)(void))call->binding->bridge.original,arguments,incoming_errno,out)==TP_SCALAR_OK;
    fp_environment(&call->fpcr,&call->fpsr);fp_restore(control,status);return ok;
}
struct executable_check {uintptr_t base,entry;const char *path;bool valid;unsigned available;};
static int executable(struct dl_phdr_info *info,size_t size,void *opaque){
    (void)size;struct executable_check *check=opaque;if(info->dlpi_addr!=check->base||strcmp(info->dlpi_name,check->path))return 0;
    for(unsigned i=0;i<info->dlpi_phnum;i++){
        const ElfW(Phdr) *p=&info->dlpi_phdr[i];
        if(p->p_type==PT_LOAD&&(p->p_flags&(PF_R|PF_W|PF_X))==(PF_R|PF_X)&&p->p_memsz>=4
            &&check->entry>=info->dlpi_addr+p->p_vaddr&&check->entry-(info->dlpi_addr+p->p_vaddr)<=p->p_memsz-4){
            check->valid=true;check->available=p->p_memsz-(check->entry-(info->dlpi_addr+p->p_vaddr))>=8&&(check->entry&4095u)<=4088u?2u:1u;
        }
    }
    return 1;
}
bool tp_native_prepare(unsigned module,uint32_t generation,uintptr_t rva,const char *path,const struct tp_scalar_signature *signature,tp_native_callback user_callback,void *context,unsigned *out){
    tp_native_error_reset();
    if(!path||path[0]!='/'||!signature||!user_callback||!out||(rva&3u))return tp_native_reject(TP_NATIVE_ARGUMENT,"INPUT",0);
    struct tp_scalar_layout layout;if(tp_scalar_plan(signature,&layout)!=TP_SCALAR_OK)return tp_native_reject(TP_NATIVE_ABI,"SIGNATURE",0);
    if(!tp_linker_acquire(module,generation))return tp_native_reject(TP_NATIVE_STALE,"MODULE_ACQUIRE",0);
    bool ok=false;if(pthread_mutex_lock(&tp_native_mutation))tp_native_stop(210);
    struct tp_module_snapshot module_state={0};
    tp_native_reject(TP_NATIVE_STALE,"MODULE_SNAPSHOT",0);
    if(!tp_linker_snapshot(module,&module_state)||!module_state.live||module_state.ambiguous||module_state.generation!=generation
        ||rva>UINTPTR_MAX-module_state.base)goto done;
    tp_native_reject(TP_NATIVE_CAPACITY,"NATIVE_SLOTS",0);
    if(tp_native_used==TP_NATIVE_ENTRY_LIMIT||!tp_scalar_inbound_remaining())goto done;
    uintptr_t entry=module_state.base+rva;struct executable_check check={module_state.base,entry,path,false,0};
    tp_native_reject(TP_NATIVE_IMAGE,"EXECUTABLE_SEGMENT",0);
    dl_iterate_phdr(executable,&check);if(!check.valid)goto done;
    tp_native_reject(TP_NATIVE_IMAGE,"PAGE_CLAIM",0);
    struct tp_native_page *page=tp_native_claim_page(module,generation,entry,path);if(!page)goto done;
    unsigned word=(unsigned)((entry&4095u)/4u);const uint32_t *input=&page->page.original_image[word];
    if(!tp_native_prefix_supported(input,check.available,entry)){tp_native_reject(TP_NATIVE_PREFIX,"PREFIX_CLASSIFICATION",0);goto done;}
    struct tp_native_binding *binding=&tp_native_bindings[tp_native_used++];
    binding->handle=(unsigned)(binding-tp_native_bindings);binding->module=module;binding->generation=generation;
    binding->entry=entry;binding->page=page;binding->signature=*signature;binding->callback=user_callback;binding->context=context;
    tp_native_error_location(binding->handle,0,0);tp_native_reject(TP_NATIVE_RESOURCE,"INBOUND_PREPARATION",0);
    if(tp_scalar_inbound_prepare(signature,callback,binding,&binding->inbound)!=TP_SCALAR_OK
        ||!tp_scalar_inbound_admission(binding->inbound.slot,tp_linker_admission_state(module,generation),generation,&binding->active_calls)
        )goto done;
    if(!tp_native_bridge_prepare(input,check.available,entry,binding->inbound.entry,&binding->bridge)){
        tp_native_reject(TP_NATIVE_ADDRESS,binding->bridge.stage,binding->bridge.error);goto done;
    }
    atomic_store_explicit(&binding->phase,TP_NATIVE_PREPARED,memory_order_release);*out=binding->handle;ok=true;
    tp_native_error_reset();
done:
    if(pthread_mutex_unlock(&tp_native_mutation))tp_native_stop(211);
    // Successful preparation owns the publication lease through restoration.
    if(!ok&&!tp_linker_release(module,generation))tp_native_stop(212);
    return ok;
}
bool tp_native_publish(const unsigned *handles,unsigned count){return tp_native_change(handles,count,true);}
bool tp_native_retire(const unsigned *handles,unsigned count){return tp_native_change(handles,count,false);}
bool tp_native_report(unsigned handle,struct tp_native_report *out){
    if(!out||handle>=TP_NATIVE_ENTRY_LIMIT)return false;struct tp_native_binding *binding=&tp_native_bindings[handle];
    unsigned phase=atomic_load_explicit(&binding->phase,memory_order_acquire);if(!phase)return false;
    *out=(struct tp_native_report){.handle=handle,.module=binding->module,.generation=binding->generation,.phase=phase,
        .prefix_bytes=binding->bridge.plan.entry.preserved_prefix_bytes,.frame=binding->bridge.plan.frame,
        .seals=binding->bridge.seals,.fde_pcs=binding->bridge.fde_pcs,.entry=binding->entry,
        .relay=binding->bridge.relay,.original=binding->bridge.original,.signed_ra=binding->bridge.plan.signed_ra,
        .calls=atomic_load_explicit(&binding->calls,memory_order_relaxed),.original_calls=atomic_load_explicit(&binding->original_calls,memory_order_relaxed),
        .active_calls=atomic_load_explicit(&binding->active_calls,memory_order_acquire)};
    return true;
}
#ifdef TP_SELF_HOOK_TESTING
bool tp_native_test_gate(unsigned handle,unsigned value){return handle<tp_native_used&&tp_scalar_inbound_test_gate(tp_native_bindings[handle].inbound.slot,value);}
unsigned tp_native_test_gate_state(unsigned handle){return handle<tp_native_used?tp_scalar_inbound_test_state(tp_native_bindings[handle].inbound.slot):0;}
#endif
