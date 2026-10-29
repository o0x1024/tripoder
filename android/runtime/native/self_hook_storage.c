#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "tripoder_thread_park.h"
#include "tripoder_fixed_libc.h"
#include "path_policy.h"
#include "io_adapter.h"
#include "syscall_namespace.h"
#include "signal_chain.h"
#include "signal_user_delivery.h"
#include <fcntl.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <unistd.h>
#include <link.h>
#include <dlfcn.h>
#include <signal.h>
#include <ucontext.h>
#include <unwind.h>
#define syscall tp_kernel_syscall

static _Atomic bool attempted;
static _Atomic bool syscall_namespace_active;
static bool trace_debugger_diagnostics;
static int reserve_signal_delivery(void){
    uintptr_t base,libc,real_mask;
    if(!tp_signal_chain_platform(&base,&libc,&real_mask))return -1;
    return tp_signal_user_delivery_reserve(base,libc,real_mask)==TP_SIGNAL_DELIVERY_OK?0:-1;
}
static void fail(JNIEnv *env,const char *reason){
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type)(*env)->ThrowNew(env,type,reason);
}
static void fail_io(JNIEnv *env,const char *reason){
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,"java/io/IOException");
    if(type)(*env)->ThrowNew(env,type,reason);
}
/* Resolve through the existing path policy, but obtain identity from the real
 * opened FD. Guest-visible readlink rewrites are not linker identities. */
JNIEXPORT jstring JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_physicalArtifactPath(JNIEnv *env,jclass unused,jstring path){
    (void)unused;
    if(!path){fail_io(env,"ARTIFACT_PHYSICAL_PATH_REQUIRED");return NULL;}
    const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return NULL;
    int fd=tp_openat(AT_FDCWD,name,O_PATH|O_NOFOLLOW|O_CLOEXEC,0);
    (*env)->ReleaseStringUTFChars(env,path,name);
    if(fd<0){fail_io(env,"ARTIFACT_PHYSICAL_OPEN_FAILED");return NULL;}
    struct stat st;char link[64],actual[PATH_MAX];
    bool regular=syscall(SYS_fstat,fd,&st)==0&&S_ISREG(st.st_mode);
    snprintf(link,sizeof(link),"/proc/self/fd/%d",fd);
    ssize_t size=regular?(ssize_t)syscall(SYS_readlinkat,AT_FDCWD,link,actual,sizeof(actual)-1):-1;
    syscall(SYS_close,fd);
    if(size<=0||size>=(ssize_t)sizeof(actual)-1){fail_io(env,"ARTIFACT_PHYSICAL_IDENTITY_FAILED");return NULL;}
    actual[size]=0;return (*env)->NewStringUTF(env,actual);
}
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_installNative(JNIEnv *env,jclass unused,jobjectArray input,jboolean diagnostics){
    (void)unused;bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true)){fail(env,"NATIVE_STORAGE_ALREADY_ATTEMPTED");return;}
    jsize count=input?(*env)->GetArrayLength(env,input):0;
    if(count!=8){fail(env,"NATIVE_STORAGE_ARGUMENTS");return;}
    char paths[8][PATH_MAX];
    for(jsize i=0;i<count;i++){
        jbyteArray value=(*env)->GetObjectArrayElement(env,input,i);
        if(!value){fail(env,"NATIVE_STORAGE_PATH_REQUIRED");return;}
        jsize size=(*env)->GetArrayLength(env,value);
        if(size<=0||size>=PATH_MAX){(*env)->DeleteLocalRef(env,value);fail(env,"NATIVE_STORAGE_PATH_BUDGET");return;}
        (*env)->GetByteArrayRegion(env,value,0,size,(jbyte *)paths[i]);(*env)->DeleteLocalRef(env,value);
        if((*env)->ExceptionCheck(env))return;
        if(memchr(paths[i],0,(size_t)size)){fail(env,"NATIVE_STORAGE_PATH_NUL");return;}
        paths[i][size]=0;
    }
    if(tp_configure(paths[0],paths[1],paths[2],paths[3],paths[4])!=0){fail(env,"NATIVE_STORAGE_CONFIGURATION_FAILED");return;}
    if(tp_configure_host_storage(paths[5],paths[6],paths[7])!=0){fail(env,"NATIVE_HOST_STORAGE_CONFIGURATION_FAILED");return;}
    trace_debugger_diagnostics=(bool)diagnostics;
    if(tp_park_prepare_process()!=TP_PARK_OK){fail(env,"NATIVE_STORAGE_THREAD_RESERVATION_FAILED");return;}
    if(!tp_signal_chain_prepare()){fail(env,"ART_SIGNAL_CHAIN_UNAVAILABLE");return;}
    /* Path adaptation belongs at the existing fixed libc filesystem entries.
     * No process-wide seccomp filter by default: exec/debuggerd and guest signal masks keep
     * platform semantics. Direct guest SVC is only intercepted when opt-in syscall policy is enabled. */
    const struct tp_fixed_libc_replacements paths_only={tp_openat,tp_fstatat,tp_unlinkat,tp_mkdirat,tp_renameat2,tp_faccessat,tp_readlinkat,tp_chdir,tp_symlinkat,tp_linkat,tp_fchmodat};
    enum tp_fixed_libc_status status=tp_fixed_libc_prepare(&paths_only);
    if(status==TP_FIXED_LIBC_OK)status=tp_fixed_libc_activate();
    if(status!=TP_FIXED_LIBC_OK){char reason[96];snprintf(reason,sizeof(reason),"NATIVE_PATH_PUBLICATION_FAILED:%u",status);fail(env,reason);}

}
JNIEXPORT jstring JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_syscallNamespaceNative(JNIEnv *env,jclass type){
    (void)type;
    struct tp_fixed_libc_report report;tp_fixed_libc_report(&report);
    uint64_t counts[4]={0},pcounts[6]={0};bool bexec=false,bkill=false,bsink=false;
    bool seccomp_active=atomic_load_explicit(&syscall_namespace_active,memory_order_acquire);
    if(seccomp_active){tp_syscall_namespace_counts(counts);tp_syscall_namespace_get_policy(&bexec,&bkill,&bsink,pcounts);}
    char value[768];
    snprintf(value,sizeof(value),"{\"transport\":\"%s\",\"addedSeccompFilters\":%u,\"nativePathEntries\":%u,\"enabled\":%s,\"publicationPages\":%u,\"parkAttempts\":%u,\"parkStatus\":%d,\"admissionElapsedNs\":%llu,\"directGuestSvcRewritten\":%s,\"nativeDumpSuppressed\":%s,\"openat\":%llu,\"fstatat\":%llu,\"readlinkat\":%llu,\"faccessat\":%llu,\"blockExecve\":%s,\"blockKill\":%s,\"networkSinkhole\":%s,\"execveAttempts\":%llu,\"execveBlocked\":%llu,\"killAttempts\":%llu,\"killBlocked\":%llu,\"connectAttempts\":%llu,\"connectSinkholed\":%llu}",
        seccomp_active?"fixed-libc-paths+seccomp-opt-in":"fixed-libc-paths",seccomp_active?1u:0u,
        report.validated_entries,report.enabled?"true":"false",report.publications,report.admission_attempts,report.pause_status,(unsigned long long)report.admission_elapsed_ns,
        seccomp_active?"true":"false",seccomp_active?"true":"false",
        (unsigned long long)counts[0],(unsigned long long)counts[1],(unsigned long long)counts[2],(unsigned long long)counts[3],
        bexec?"true":"false",bkill?"true":"false",bsink?"true":"false",
        (unsigned long long)pcounts[0],(unsigned long long)pcounts[1],(unsigned long long)pcounts[2],(unsigned long long)pcounts[3],(unsigned long long)pcounts[4],(unsigned long long)pcounts[5]);
    return (*env)->NewStringUTF(env,value);
}
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_setSyscallPolicyNative(JNIEnv *env,jclass type,jboolean block_execve,jboolean block_kill,jboolean network_sinkhole){
    (void)type;
    if(block_execve||block_kill||network_sinkhole){
        if(!atomic_load_explicit(&syscall_namespace_active,memory_order_acquire)){
            tp_syscall_namespace_trace_debugger(trace_debugger_diagnostics);
            if(tp_syscall_namespace_install_with_signal_chain(tp_signal_chain_claim,reserve_signal_delivery,tp_signal_chain_user_action)!=0){
                bool ready;unsigned status;uint64_t roles,other;tp_signal_user_delivery_state(&ready,&status,&roles,&other);
                char reason[128];snprintf(reason,sizeof(reason),"SYSCALL_ANALYSIS_POLICY_NOT_INSTALLED:userDeliveryStatus=%u",status);
                fail(env,reason);return;
            }
            atomic_store_explicit(&syscall_namespace_active,true,memory_order_release);
        }
    }
    if(atomic_load_explicit(&syscall_namespace_active,memory_order_acquire)){
        tp_syscall_namespace_set_policy((bool)block_execve,(bool)block_kill,(bool)network_sinkhole);
    }
}

JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_readPhysicalProc(JNIEnv *env,jclass unused,jstring path,jint limit){
    (void)unused;
    if(!path||limit<=0||limit>4*1024*1024){fail_io(env,"RUNTIME_EVIDENCE_INVALID_ARGUMENTS");return NULL;}
    const char *raw_path=(*env)->GetStringUTFChars(env,path,NULL);
    if(!raw_path)return NULL;
    if(strncmp(raw_path,"/proc/self/",11)!=0){(*env)->ReleaseStringUTFChars(env,path,raw_path);fail_io(env,"RUNTIME_EVIDENCE_PATH_DENIED");return NULL;}
    int fd=(int)syscall(SYS_openat,AT_FDCWD,raw_path,O_RDONLY|O_CLOEXEC,0);
    (*env)->ReleaseStringUTFChars(env,path,raw_path);
    if(fd<0){fail_io(env,"RUNTIME_EVIDENCE_OPEN_FAILED");return NULL;}
    size_t cap=(size_t)limit+1,used=0;
    char *buf=malloc(cap);
    if(!buf){syscall(SYS_close,fd);fail_io(env,"RUNTIME_EVIDENCE_ALLOC_FAILED");return NULL;}
    bool ok=true;
    while(used<cap){
        ssize_t n=(ssize_t)syscall(SYS_read,fd,buf+used,cap-used);
        if(n==0)break;
        if(n<0){ok=false;break;}
        used+=(size_t)n;
    }
    syscall(SYS_close,fd);
    if(!ok){free(buf);fail_io(env,"RUNTIME_EVIDENCE_READ_FAILED");return NULL;}
    if(used>(size_t)limit){free(buf);fail_io(env,"RUNTIME_EVIDENCE_READ_BUDGET");return NULL;}
    jbyteArray out=(*env)->NewByteArray(env,(jsize)used);
    if(out&&used>0)(*env)->SetByteArrayRegion(env,out,0,(jsize)used,(const jbyte *)buf);
    free(buf);
    return out;
}

JNIEXPORT jstring JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_signalChainNative(JNIEnv *env,jclass type){
    (void)type;bool available,delivery;unsigned status;uint64_t changes,modules,roles,other;
    tp_signal_chain_state(&available,&changes,&modules);tp_signal_user_delivery_state(&delivery,&status,&roles,&other);
    char result[360];snprintf(result,sizeof(result),"{\"ready\":%s,\"patched\":%llu,\"modules\":%llu,\"claims\":%llu,\"userDeliveryReady\":%s,\"userDeliveryStatus\":%u,\"userDeliveryCalls\":%llu,\"otherMaskCalls\":%llu}",available?"true":"false",(unsigned long long)changes,(unsigned long long)modules,(unsigned long long)tp_signal_chain_claims(),delivery?"true":"false",status,(unsigned long long)roles,(unsigned long long)other);return (*env)->NewStringUTF(env,result);
}
