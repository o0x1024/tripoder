#define _GNU_SOURCE
#include "tripoder_native_hook.h"
#include "modules/linker_notifier.h"
#include "probe_maps.h"
#include "park_syscall.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
static unsigned checks,failures;
static const char *first;
static void check(bool ok,const char *name){checks++;if(!ok){failures++;if(!first)first=name;}}
struct context {uint64_t delta;bool pause,consume;_Atomic bool enabled,entered,resume;_Atomic unsigned calls,errors;_Atomic uint64_t token;};
static struct context contexts[5];
static const struct tp_scalar_signature add_signature={.count=2,.result=TP_SCALAR_I32,.arguments={TP_SCALAR_I32,TP_SCALAR_I32}};
static const struct tp_scalar_signature exact_signature={.count=1,.result=TP_SCALAR_U64,.arguments={TP_SCALAR_U64}};
static bool dispatch(void *opaque,uint64_t token,const struct tp_scalar_value *args,unsigned count,int incoming,struct tp_scalar_result *out){
    struct context *ctx=opaque;atomic_fetch_add(&ctx->calls,1);atomic_store(&ctx->token,token);
    atomic_store_explicit(&ctx->entered,true,memory_order_release);
    while(ctx->pause&&!atomic_load_explicit(&ctx->resume,memory_order_acquire))tp_park_sleep(100000);
    if(ctx->consume){
        if(tp_native_original(token,args,count+1,incoming,out)||tp_native_original(token,args,count,incoming,out))atomic_fetch_add(&ctx->errors,1);
        *out=(struct tp_scalar_result){.value={TP_SCALAR_I32,123},.error_number=EDOM};return true;
    }
    if(!tp_native_original(token,args,count,incoming,out))return false;
    if(atomic_load_explicit(&ctx->enabled,memory_order_acquire))out->value.bits+=ctx->delta;
    errno=EIO;return true;
}
static int32_t (*add)(int32_t,int32_t),(*count_target)(int32_t);
static int32_t old_result;static int old_errno;
static void *old_call(void *unused){(void)unused;errno=17;old_result=add(3,4);old_errno=errno;return NULL;}
static bool reached(struct context *ctx){
    uint64_t end=tp_park_now()+UINT64_C(3000000000);
    while(!atomic_load_explicit(&ctx->entered,memory_order_acquire)){if(tp_park_now()>=end)return false;tp_park_sleep(100000);}
    return true;
}
static bool witness(const char *directory,unsigned checks_done){
    struct tp_module_snapshot state={0};if(!tp_linker_snapshot(0,&state)||!state.live||state.references!=1)return false;
    if(!tp_probe_maps_save(directory,"active-unload"))return false;
    char path[2048],json[1024];int n=snprintf(path,sizeof(path),"%s/active-unload-ready.json",directory);if(n<=0||(size_t)n>=sizeof(path))return false;
    int fd=open(path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0644);if(fd<0)return false;
    n=snprintf(json,sizeof(json),"{\"pid\":%d,\"installed\":true,\"generation\":%u,\"references\":%u,\"checks\":%u,\"failures\":%u,\"expectedExitStatus\":198,\"publicationRetired\":true,\"invocationLease\":true}\n",
        getpid(),state.generation,state.references,checks_done,failures);
    bool ok=n>0&&(size_t)n<sizeof(json)&&write(fd,json,(size_t)n)==n&&fsync(fd)==0;return close(fd)==0&&ok;
}
static const char *run(const char *path,const char *audit,const char *directory,bool death){
    static char json[1600];const char *paths[]={path};void *library=NULL;pthread_t thread;bool started=false;
    unsigned a=0,b=0,old=0,next=0,replacement=0;struct tp_module_snapshot module={0},leases={0};
    if(checks)return "{\"passed\":false,\"error\":\"REPEATED_RUN\"}";
    check(tp_linker_install(paths,1),"INSTALL_LIFETIME");if(failures)goto done;
    library=dlopen(path,RTLD_NOW|RTLD_LOCAL);check(library!=NULL,"LOAD_OWNED_LIBRARY");if(!library)goto done;
    void (*set_path)(const char *)=dlsym(library,"tripoder_hook_audit_path"),(*reset)(void)=dlsym(library,"tripoder_hook_reset");
    add=dlsym(library,"tripoder_hook_add");uint64_t (*exact)(uint64_t)=dlsym(library,"tripoder_hook_exact");count_target=dlsym(library,"tripoder_hook_count");
    check(set_path&&reset&&add&&exact&&count_target,"OWNED_EXPORTS");if(failures)goto done;set_path(audit);reset();
    check(tp_linker_snapshot(0,&module)&&module.live,"CAPTURE_MODULE");if(failures)goto done;
    for(unsigned i=0;i<5;i++)atomic_store(&contexts[i].enabled,true);
    contexts[0].delta=100;contexts[1].delta=5;contexts[2].delta=100;contexts[2].pause=true;contexts[3].delta=200;contexts[4].consume=true;
    uintptr_t add_rva=(uintptr_t)add-module.base,exact_rva=(uintptr_t)exact-module.base;
    check(tp_native_prepare(0,module.generation,add_rva,path,&add_signature,dispatch,&contexts[0],&a)
        &&tp_native_prepare(0,module.generation,exact_rva,path,&exact_signature,dispatch,&contexts[1],&b),"PREPARE_A_AND_B_ON_SAME_PAGE");if(failures)goto done;
    check(tp_native_publish(&a,1)&&add(3,4)==107,"PUBLISH_A_ONLY");if(failures)goto done;
    check(tp_native_retire(&a,1)&&add(3,4)==7,"RETIRE_A_WITH_B_PREPARED");if(failures)goto done;
    check(tp_native_publish(&b,1)&&exact(100)==105,"PUBLISH_B_AFTER_LAST_ACTIVE_FILE_RESTORE");if(failures)goto done;
    check(tp_native_retire(&b,1)&&exact(100)==100,"RETIRE_B");if(failures)goto done;
    check(tp_native_prepare(0,module.generation,add_rva,path,&add_signature,dispatch,&contexts[2],&old)&&tp_native_publish(&old,1),"REACTIVATE_NEW_IMMUTABLE_OLD_CONTEXT");if(failures)goto done;
    check(pthread_create(&thread,NULL,old_call,NULL)==0,"START_INBOUND_PRE_DISPATCH_CALL");if(failures)goto done;started=true;
    check(reached(&contexts[2]),"NATIVE_CALL_LEASE_BEFORE_JAVA_ACCOUNTING");if(failures)goto done;
    atomic_store_explicit(&contexts[2].enabled,false,memory_order_release);
    if(!death){check(tp_native_prepare(0,module.generation,add_rva,path,&add_signature,dispatch,&contexts[3],&next),"PREPARE_NEXT_WHILE_OLD_CALL_PAUSED");if(failures)goto done;}
    check(tp_native_retire(&old,1),"RETIRE_OLD_WHILE_NATIVE_CALLBACK_PAUSED");if(failures)goto done;
    check(tp_linker_snapshot(0,&leases)&&leases.references==(death?1u:2u),"INVOCATION_LEASE_SURVIVES_PUBLICATION_RETIRE");if(failures)goto done;
    if(death){
        if(!witness(directory,checks))goto done;
        dlclose(library);return "{\"passed\":false,\"error\":\"INFLIGHT_UNLOAD_RETURNED\"}";
    }
    check(tp_native_publish(&next,1)&&add(3,4)==207,"NEXT_VERSION_EXECUTES_ITS_OWN_CONTEXT");if(failures)goto done;
    struct tp_scalar_result rejected={0};
    check(!tp_native_original(atomic_load(&contexts[2].token),NULL,0,17,&rejected),"FOREIGN_THREAD_CANNOT_USE_PAUSED_TOKEN");
    atomic_store_explicit(&contexts[2].resume,true,memory_order_release);
    if(pthread_join(thread,NULL)){(void)tp_park_syscall(SYS_exit_group,222,0,0,0,0,0);__builtin_trap();}started=false;
    check(old_result==7&&old_errno==17&&atomic_load(&contexts[2].calls)==1&&atomic_load(&contexts[3].calls)==1,"LATE_OLD_CALL_ONLY_OLD_DISABLED_ORIGINAL");
    check(tp_linker_snapshot(0,&leases)&&leases.references==1,"CALL_LEASE_RELEASED_AFTER_ENCODING");
    check(tp_native_retire(&next,1),"RETIRE_NEXT_VERSION");if(failures)goto done;
    int before=count_target(0);
    check(tp_native_prepare(0,module.generation,add_rva,path,&add_signature,dispatch,&contexts[4],&replacement)&&tp_native_publish(&replacement,1),"REPLACEMENT_WITHOUT_ORIGINAL");if(failures)goto done;
    errno=17;int value=add(3,4);int returned_errno=errno;
    check(value==123&&returned_errno==EDOM&&count_target(0)==before&&!atomic_load(&contexts[4].errors),"INVALID_ORIGINAL_ATTEMPT_CONSUMES_TOKEN");
    check(tp_native_retire(&replacement,1),"RETIRE_REPLACEMENT");if(failures)goto done;
    check(dlclose(library)==0,"UNLOAD_AFTER_ALL_INVOCATIONS");library=NULL;
    check(tp_linker_snapshot(0,&leases)&&!leases.live&&!leases.references,"UNLOADED_WITHOUT_RETAINED_MODULE_REFERENCE");
done:
    if(started){atomic_store(&contexts[2].resume,true);if(pthread_join(thread,NULL)){(void)tp_park_syscall(SYS_exit_group,223,0,0,0,0,0);__builtin_trap();}}
    /* Failure evidence is retained in this process; do not guess which partial
     * activation should be removed or call dlclose with a held publication. */
    snprintf(json,sizeof(json),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"oldResult\":%d,\"oldErrno\":%d,\"oldCallbacks\":%u,\"newCallbacks\":%u,\"moduleRemoved\":%s,\"immutableActivations\":true,\"productJavaConnected\":false,\"productAccepted\":false}",
        failures?"false":"true",checks,failures,first?first:"",old_result,old_errno,atomic_load(&contexts[2].calls),atomic_load(&contexts[3].calls),!leases.live&&leases.generation?"true":"false");
    return json;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeRaceProbe_run(JNIEnv *env,jclass type,jstring library,jstring audit,jstring directory,jboolean death){
    (void)type;jstring strings[3]={library,audit,directory};const char *values[3]={0};unsigned count=0;
    for(;count<3;count++){values[count]=(*env)->GetStringUTFChars(env,strings[count],NULL);if(!values[count])break;}
    const char *result=count==3?run(values[0],values[1],values[2],death):NULL;
    for(unsigned i=0;i<count;i++)(*env)->ReleaseStringUTFChars(env,strings[i],values[i]);
    return result?(*env)->NewStringUTF(env,result):NULL;
}
