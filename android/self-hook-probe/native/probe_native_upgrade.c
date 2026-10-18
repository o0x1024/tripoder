#define _GNU_SOURCE
#include "tripoder_native_hook.h"
#include "modules/linker_notifier.h"
#include "code_page.h"
#include "park_syscall.h"
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
static unsigned checks,failures;static const char *first="";
static void check(bool ok,const char *name){checks++;if(!ok){failures++;if(!*first)first=name;}}
static int (*target)(int,int);static int value,error_number;
static _Atomic bool pause_call,entered,resume_call;
static bool callback(void *context,uint64_t token,const struct tp_scalar_value *args,unsigned count,int incoming,struct tp_scalar_result *out){
    (void)context;
    if(atomic_load(&pause_call)){atomic_store(&entered,true);while(!atomic_load(&resume_call))tp_park_sleep(1000000);}
    return tp_native_original(token,args,count,incoming,out);
}
static void *worker(void *unused){(void)unused;errno=17;value=target(3,4);error_number=errno;return NULL;}
static bool await_gate(unsigned handle){
    uint64_t end=tp_park_now()+UINT64_C(3000000000);
    while(tp_native_test_gate_state(handle)!=2){if(tp_park_now()>=end)return false;tp_park_sleep(1000000);}return true;
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhookprobe_NativeUpgradeProbe_commitDeath(JNIEnv *env,jclass type,jstring library,jstring directory){
    (void)type;const char *path=(*env)->GetStringUTFChars(env,library,NULL);if(!path)return;
    const char *dir=(*env)->GetStringUTFChars(env,directory,NULL);if(!dir){(*env)->ReleaseStringUTFChars(env,library,path);return;}
    const char *paths[]={path};struct tp_module_snapshot module;unsigned handle;
    const struct tp_scalar_signature signature={.count=2,.result=TP_SCALAR_I32,.arguments={TP_SCALAR_I32,TP_SCALAR_I32}};
    if(!tp_linker_install(paths,1))goto done;
    void *loaded=dlopen(path,RTLD_NOW|RTLD_LOCAL);if(!loaded)goto done;
    void *entry=dlsym(loaded,"tripoder_hook_add");if(!entry||!tp_linker_snapshot(0,&module)||!module.live)goto done;
    if(!tp_native_prepare(0,module.generation,(uintptr_t)entry-module.base,path,&signature,callback,NULL,&handle))goto done;
    char file[2048],text[200];int length=snprintf(file,sizeof(file),"%s/active-unload-ready.json",dir);if(length<=0||(size_t)length>=sizeof(file))goto done;
    int fd=open(file,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0644);if(fd<0)goto done;
    length=snprintf(text,sizeof(text),"{\"pid\":%d,\"committedPages\":1,\"expectedExitStatus\":230}\n",getpid());
    if(length>0&&(size_t)length<sizeof(text)&&tp_native_test_commit_witness(fd,text,(unsigned)length))tp_native_publish(&handle,1);
    close(fd);
done:
    (*env)->ReleaseStringUTFChars(env,directory,dir);(*env)->ReleaseStringUTFChars(env,library,path);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeUpgradeProbe_run(JNIEnv *env,jclass type,jstring library){
    (void)type;const char *path=(*env)->GetStringUTFChars(env,library,NULL);if(!path)return NULL;
    void *loaded=NULL;pthread_t thread;bool running=false;unsigned handle=UINT32_MAX;uint64_t held=0;
    const char *paths[]={path};struct tp_module_snapshot module={0},lease={0};struct tp_native_report report;
    struct tp_native_error diagnostic;
    const struct tp_scalar_signature signature={.count=2,.result=TP_SCALAR_I32,.arguments={TP_SCALAR_I32,TP_SCALAR_I32}};
    check(tp_linker_install(paths,1),"MODULE_OBSERVER");if(failures)goto done;
    loaded=dlopen(path,RTLD_NOW|RTLD_LOCAL);check(loaded!=NULL,"MODULE_LOAD");if(!loaded)goto done;
    target=dlsym(loaded,"tripoder_hook_add");check(target&&tp_linker_snapshot(0,&module)&&module.live,"LIVE_EXPORT");if(failures)goto done;
    uintptr_t rva=(uintptr_t)target-module.base;
    struct tp_scalar_signature invalid=signature;invalid.count=17;
    check(!tp_native_prepare(0,module.generation,rva,path,&invalid,callback,NULL,&handle)&&handle==UINT32_MAX,"INVALID_SIGNATURE_NO_HANDLE");
    tp_native_last_error(&diagnostic);check(diagnostic.status==TP_NATIVE_ABI,"SIGNATURE_DIAGNOSTIC");
    check(tp_linker_snapshot(0,&lease)&&lease.references==0,"INVALID_SIGNATURE_NO_LEASE");
    struct tp_native_resources before_failures,after_failures;
    check(tp_native_resources(&before_failures),"INITIAL_RESOURCE_ACCOUNT");
    for(unsigned stage=1;stage<=6;stage++){
        unsigned untouched=UINT32_MAX;tp_native_test_fail_prepare(stage);
        check(!tp_native_prepare(0,module.generation,rva,path,&signature,callback,NULL,&untouched)&&untouched==UINT32_MAX,"INJECTED_PREPARE_NO_HANDLE");
        tp_native_last_error(&diagnostic);check(diagnostic.status==TP_NATIVE_RESOURCE&&diagnostic.system_error==EIO&&diagnostic.planner_status==0,"INJECTED_FAILURE_DISTINCT_FROM_PLANNER");
        check(tp_linker_snapshot(0,&lease)&&lease.references==0&&target(3,4)==7,"INJECTED_FAILURE_NO_PUBLICATION_OR_LEASE");
    }
    check(tp_native_resources(&after_failures)&&after_failures.failed==before_failures.failed+6
        &&after_failures.retained_images==before_failures.retained_images+1,"FAILED_DRAFTS_AND_QUARANTINED_IMAGE_ACCOUNTED");
    check(tp_native_prepare(0,module.generation,rva,path,&signature,callback,NULL,&handle),"PREPARED_FOR_REFUSAL");if(failures)goto done;
    tp_code_pages_reject_next_prepare();check(!tp_native_publish(&handle,1),"PREPARE_FAULT_REFUSED");
    tp_native_last_error(&diagnostic);check(diagnostic.status==TP_NATIVE_RESOURCE&&target(3,4)==7,"FAULT_LEFT_ORIGINAL_ENTRY");
    check(tp_native_retire(&handle,1)&&tp_linker_snapshot(0,&lease)&&lease.references==0,"CANCEL_RELEASES_PUBLICATION_LEASE");
    check(tp_native_prepare(0,module.generation,rva,path,&signature,callback,NULL,&handle)&&tp_native_publish(&handle,1),"NEW_ACTIVATION_AFTER_FAULT");if(failures)goto done;
    check(tp_native_test_gate(handle,1),"ARM_EARLY_ENTRY_GATE");
    check(pthread_create(&thread,NULL,worker,NULL)==0,"EARLY_WORKER");if(failures)goto done;running=true;
    check(await_gate(handle),"EARLY_PC_REACHED");if(failures)goto done;
    check(tp_native_report(handle,&report)&&report.active_calls==0,"PRE_ADMISSION_NOT_COUNTED");
    check(!tp_native_retire(&handle,1),"EARLY_ENTRY_RETIRE_BUSY");tp_native_last_error(&diagnostic);
    check(diagnostic.status==TP_NATIVE_BUSY&&diagnostic.tid>0&&diagnostic.pc!=0,"BUSY_IDENTIFIES_PARKED_PC");
    check(tp_native_report(handle,&report)&&report.phase==2&&tp_linker_snapshot(0,&lease)&&lease.references==1,"BUSY_RETAINS_ACTIVE_ENTRY_AND_LEASE");
    tp_native_test_gate(handle,0);if(pthread_join(thread,NULL))__builtin_trap();running=false;
    check(value==7&&error_number==17&&tp_native_report(handle,&report)&&report.active_calls==0,"EARLY_WORKER_COMPLETES_ONCE");
    check(tp_native_retire(&handle,1)&&tp_linker_snapshot(0,&lease)&&lease.references==0,"RETRY_RETIRE_AFTER_WINDOW_CLOSED");if(failures)goto done;
    check(tp_native_prepare(0,module.generation,rva,path,&signature,callback,NULL,&handle)&&tp_native_publish(&handle,1),"LONG_CALL_ACTIVATION");if(failures)goto done;
    atomic_store(&pause_call,true);check(pthread_create(&thread,NULL,worker,NULL)==0,"LONG_WORKER");if(failures)goto done;running=true;
    uint64_t deadline=tp_park_now()+UINT64_C(3000000000);
    while(!atomic_load(&entered)&&tp_park_now()<deadline)tp_park_sleep(1000000);
    check(atomic_load(&entered)&&tp_native_report(handle,&report)&&report.active_calls==1,"ADMITTED_CALL_VISIBLE");if(failures)goto done;
    check(tp_native_retire(&handle,1)&&tp_linker_snapshot(0,&lease)&&lease.references==1,"RESTORED_ENTRY_RETAINS_INVOCATION");if(failures)goto done;
    uint64_t start=tp_park_now();while(tp_park_now()-start<UINT64_C(31000000000))tp_park_sleep(10000000);held=tp_park_now()-start;
    atomic_store(&resume_call,true);if(pthread_join(thread,NULL))__builtin_trap();running=false;
    check(value==7&&error_number==17&&tp_native_report(handle,&report)&&report.calls==1&&report.original_calls==1&&report.active_calls==0,"LATE_ORIGINAL_AND_ENCODING_SURVIVE");
    check(tp_linker_snapshot(0,&lease)&&lease.references==0,"FULL_NATIVE_EPILOGUE_RELEASED");
    check(dlclose(loaded)==0,"UNLOAD_AFTER_DRAIN");loaded=NULL;
    check(tp_linker_snapshot(0,&lease)&&!lease.live,"MODULE_REMOVED");
done:
    if(running){tp_native_test_gate(handle,0);atomic_store(&resume_call,true);if(pthread_join(thread,NULL))__builtin_trap();}
    char json[1200];snprintf(json,sizeof(json),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"heldNs\":%llu,\"earlyAdmissionVerified\":%s,\"resourceReclamation\":false}",failures?"false":"true",checks,failures,first,(unsigned long long)held,failures?"false":"true");
    (*env)->ReleaseStringUTFChars(env,library,path);return (*env)->NewStringUTF(env,json);
}
