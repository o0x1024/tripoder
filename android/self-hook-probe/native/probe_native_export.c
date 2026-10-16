#define _GNU_SOURCE
#include "tripoder_native_hook.h"
#include "modules/linker_notifier.h"
#include "code_page.h"
#include "park_syscall.h"
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <libunwind.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
static unsigned checks,failures;
static const char *first;
static void fatal(int reason){(void)tp_park_syscall(SYS_exit_group,reason,0,0,0,0,0);__builtin_trap();}
static void check(bool ok,const char *name){checks++;if(!ok){failures++;if(!first)first=name;}}
struct context {unsigned kind;_Atomic unsigned calls,errors;_Atomic uint64_t last_token;};
static struct context contexts[4];
static _Atomic unsigned unwind_calls;
static bool dispatch(void *opaque,uint64_t token,const struct tp_scalar_value *args,unsigned count,int incoming_errno,struct tp_scalar_result *out){
    struct context *context=opaque;atomic_fetch_add(&context->calls,1);
    struct tp_scalar_result rejected={0};uint64_t previous=atomic_exchange(&context->last_token,token);
    if(tp_native_original(token+1,args,count,incoming_errno,&rejected)
        ||(previous&&tp_native_original(previous,args,count,incoming_errno,&rejected)))atomic_fetch_add(&context->errors,1);
    if(!tp_native_original(token,args,count,incoming_errno,out))return false;
    if(tp_native_original(token,args,count,incoming_errno,&rejected))atomic_fetch_add(&context->errors,1);
    if(context->kind==2){double value;memcpy(&value,&out->value.bits,8);value+=1.25;memcpy(&out->value.bits,&value,8);}
    else if(context->kind==1)out->value.bits+=5;
    else {int32_t value=(int32_t)(uint32_t)out->value.bits;value+=context->kind==3?200:100;out->value.bits=(uint32_t)value;}
    if(context->kind==3)out->error_number=EDOM;
    // Real unwind across the replacement relay/inbound and ordinary caller.
    unw_context_t unwind;unw_cursor_t cursor;unsigned frames=0;
    if(!unw_getcontext(&unwind)&&!unw_init_local(&cursor,&unwind))for(unsigned i=0;i<24;i++){if(unw_step(&cursor)<=0)break;frames++;}
    if(frames>=4)atomic_fetch_add(&unwind_calls,1);else atomic_fetch_add(&context->errors,1);
    errno=EIO;return true;
}
struct symbols {
    void (*set_path)(const char *),(*reset)(void);
    int32_t (*count)(int32_t),(*add)(int32_t,int32_t),(*error)(int32_t);
    uint64_t (*exact)(uint64_t);
    double (*fraction)(double);
};
static struct symbols functions;
static _Atomic unsigned concurrent_calls,concurrent_errors;
static void *worker(void *unused){
    (void)unused;
    for(unsigned i=0;i<500;i++){
        errno=17;int32_t value=functions.add(3,4);int returned_errno=errno;
        if(value!=107||returned_errno!=17)atomic_fetch_add(&concurrent_errors,1);
        atomic_fetch_add(&concurrent_calls,1);
    }
    return NULL;
}
static const char *run(const char *path,const char *audit,bool pac){
    static char json[3072];unsigned handles[4]={0};struct tp_native_report reports[4]={0};
    void *module=NULL;bool published=false;unsigned prepared=0;unsigned restored_inode=0;
    struct tp_module_snapshot state={0},removed={0};
    if(checks)return "{\"passed\":false,\"error\":\"REPEATED_RUN\"}";
    const char *paths[]={path};check(tp_linker_install(paths,1),"LIFETIME_BEFORE_OWNED_LIBRARY");if(failures)goto done;
    module=dlopen(path,RTLD_NOW|RTLD_LOCAL);check(module!=NULL,"LOAD_REAL_PRODUCT_OWNED_LIBRARY");if(!module)goto done;
    functions.set_path=dlsym(module,"tripoder_hook_audit_path");functions.reset=dlsym(module,"tripoder_hook_reset");functions.count=dlsym(module,"tripoder_hook_count");
    functions.add=dlsym(module,"tripoder_hook_add");functions.exact=dlsym(module,"tripoder_hook_exact");functions.fraction=dlsym(module,"tripoder_hook_fraction");functions.error=dlsym(module,"tripoder_hook_error");
    check(functions.set_path&&functions.reset&&functions.count&&functions.add&&functions.exact&&functions.fraction&&functions.error,"ORIGINAL_PRODUCT_EXPORTS");if(failures)goto cleanup;
    functions.set_path(audit);functions.reset();
    check(tp_linker_snapshot(0,&state)&&state.live&&state.generation==1,"CAPTURE_LOADER_GENERATION");if(failures)goto cleanup;
    check(functions.add(3,4)==7&&functions.exact(UINT64_C(9007199254740993))==UINT64_C(9007199254740993)
        &&functions.fraction(2.0)==2.5&&functions.error(9)==9,"FOUR_ORIGINAL_NATIVE_VALUES");functions.reset();
    const struct tp_scalar_signature signatures[4]={
        {.count=2,.result=TP_SCALAR_I32,.arguments={TP_SCALAR_I32,TP_SCALAR_I32}},
        {.count=1,.result=TP_SCALAR_U64,.arguments={TP_SCALAR_U64}},
        {.count=1,.result=TP_SCALAR_F64,.arguments={TP_SCALAR_F64}},
        {.count=1,.result=TP_SCALAR_I32,.arguments={TP_SCALAR_I32}}};
    uintptr_t entries[4]={(uintptr_t)functions.add,(uintptr_t)functions.exact,(uintptr_t)functions.fraction,(uintptr_t)functions.error};
    for(unsigned i=0;i<4;i++){
        contexts[i].kind=i;
        check(tp_native_prepare(0,state.generation,entries[i]-state.base,path,&signatures[i],dispatch,&contexts[i],&handles[i]),"PREPARE_REAL_EXPORT");
        if(failures)goto cleanup;prepared++;
        check(tp_native_report(handles[i],&reports[i])&&reports[i].frame==32&&reports[i].seals==15&&reports[i].fde_pcs==8
            &&reports[i].signed_ra==pac&&reports[i].prefix_bytes==(pac?4u:0u),"PREFIX_AND_REAL_SEALED_FDE");
        if(failures)goto cleanup;
    }
    check((entries[0]>>12)==(entries[1]>>12)&&(entries[0]>>12)==(entries[2]>>12)&&(entries[0]>>12)==(entries[3]>>12),"FOUR_EXPORTS_SHARE_ONE_PAGE");
    check(tp_native_publish(handles,4),"BATCH_PUBLISH_FOUR_SHARED_WORDS");if(failures)goto cleanup;published=true;
    errno=17;int32_t sum=functions.add(3,4);int after_sum=errno;
    check(sum==107&&after_sum==17,"REAL_ADD_ORIGINAL_REPLACEMENT_ERRNO");
    errno=17;uint64_t exact=functions.exact(UINT64_C(9007199254740993));int after_exact=errno;
    check(exact==UINT64_C(9007199254740998)&&after_exact==17,"EXACT_64BIT_ORIGINAL");
    errno=17;double fraction=functions.fraction(2.0);int after_fraction=errno;
    check(fraction==3.75&&after_fraction==17,"DOUBLE_ORIGINAL_RETURN");
    errno=17;int32_t error=functions.error(9);int after_error=errno;check(error==209&&after_error==EDOM,"SIDE_EFFECT_ONCE_EXPLICIT_ERRNO");
    bool one_each=true;for(unsigned i=0;i<4;i++)if(functions.count((int32_t)i)!=1||atomic_load(&contexts[i].calls)!=1)one_each=false;
    check(one_each,"FOUR_ORIGINALS_EXACTLY_ONCE");
    check(!tp_native_publish(handles,4),"DUPLICATE_PUBLISH_REJECTED");
    check(tp_native_retire(&handles[1],1),"RETIRE_ONE_SHARED_PAGE_ENTRY");
    check(functions.exact(UINT64_C(9007199254740993))==UINT64_C(9007199254740993)&&functions.add(3,4)==107&&functions.fraction(2.0)==3.75,"OTHER_SHARED_HOOKS_SURVIVE");
    pthread_t threads[4];unsigned made=0;for(;made<4;made++)if(pthread_create(&threads[made],NULL,worker,NULL))break;
    for(unsigned i=0;i<made;i++)if(pthread_join(threads[i],NULL))fatal(218);
    check(made==4&&atomic_load(&concurrent_calls)==2000&&atomic_load(&concurrent_errors)==0,"DIRECT_CONCURRENT_NATIVE_CALLERS");
    unsigned rest[]={handles[0],handles[2],handles[3]};
    check(tp_native_retire(rest,3),"RESTORE_REMAINING_SHARED_PAGE_WORDS");if(failures)goto cleanup;published=false;prepared=0;
    struct tp_page_identity identity={0};
    check(tp_code_page_identity((void *)(entries[0]&~(uintptr_t)4095),"r-xp",&identity)&&identity.inode&&identity.bti==pac,"LAST_ENTRY_RESTORES_FILE_MAPPING");restored_inode=identity.inode!=0;
    check(functions.add(3,4)==7&&functions.fraction(2.0)==2.5&&functions.error(9)==9,"ORIGINALS_AFTER_RETIRE");
    struct tp_scalar_result rejected={0};check(!tp_native_original(atomic_load(&contexts[0].last_token),NULL,0,17,&rejected),"TOKEN_EXPIRED_AFTER_CALLBACK");
    bool callbacks_ok=true;for(unsigned i=0;i<4;i++)if(atomic_load(&contexts[i].errors))callbacks_ok=false;check(callbacks_ok,"TOKEN_IDENTITY_ONCE_AND_UNWIND");
    check(atomic_load(&unwind_calls)>=2004,"ACTUAL_NATIVE_DISPATCH_UNWIND");
cleanup:
    if(published){unsigned active[4],count=0;for(unsigned i=0;i<prepared;i++){struct tp_native_report report;if(tp_native_report(handles[i],&report)&&report.phase==2)active[count++]=handles[i];}
        if(count&&!tp_native_retire(active,count))fatal(219);}
    else if(prepared){for(unsigned i=0;i<prepared;i++){struct tp_native_report report;if(tp_native_report(handles[i],&report)&&report.phase==1&&!tp_native_retire(&handles[i],1))fatal(220);}}
    if(module){check(dlclose(module)==0,"OWNED_LIBRARY_CAN_UNLOAD_AFTER_RETIRE");check(tp_linker_snapshot(0,&removed)&&!removed.live,"REMOVAL_OBSERVED_WITHOUT_PINNING");}
done:
    snprintf(json,sizeof(json),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"exports\":4,\"concurrentCalls\":%u,\"concurrentErrors\":%u,\"unwindCalls\":%u,\"fileMappingRestored\":%s,\"moduleRemoved\":%s,\"generation\":%u,\"pac\":%s,\"hookInstalled\":true,\"productAccepted\":false}",
        failures?"false":"true",checks,failures,first?first:"",atomic_load(&concurrent_calls),atomic_load(&concurrent_errors),atomic_load(&unwind_calls),restored_inode?"true":"false",!removed.live&&removed.generation?"true":"false",state.generation,pac?"true":"false");
    return json;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeExportProbe_run(JNIEnv *env,jclass type,jstring library,jstring audit,jboolean pac){
    (void)type;const char *path=(*env)->GetStringUTFChars(env,library,NULL);if(!path)return NULL;
    const char *file=(*env)->GetStringUTFChars(env,audit,NULL);if(!file){(*env)->ReleaseStringUTFChars(env,library,path);return NULL;}
    const char *json=run(path,file,pac);(*env)->ReleaseStringUTFChars(env,audit,file);(*env)->ReleaseStringUTFChars(env,library,path);
    return (*env)->NewStringUTF(env,json);
}
