#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "tripoder_thread_park.h"
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
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_installNative(JNIEnv *env,jclass unused,jobjectArray input,jboolean thin,jboolean diagnostics){
    (void)unused;bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true)){fail(env,"NATIVE_STORAGE_ALREADY_ATTEMPTED");return;}
    jsize count=input?(*env)->GetArrayLength(env,input):0;
    if(count!=5&&count!=6&&count!=9){fail(env,"NATIVE_STORAGE_ARGUMENTS");return;}
    char paths[9][PATH_MAX];
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
    if(tp_configure_process(count>=6?paths[5]:paths[4])!=0){fail(env,"NATIVE_STORAGE_PROCESS_CONFIGURATION_FAILED");return;}
    if(count==9&&tp_configure_host_storage(paths[6],paths[7],paths[8])!=0){fail(env,"NATIVE_HOST_STORAGE_CONFIGURATION_FAILED");return;}
    tp_configure_thin(thin);
    tp_syscall_namespace_trace_debugger(diagnostics);
    if(tp_park_prepare_process()!=TP_PARK_OK){fail(env,"NATIVE_STORAGE_THREAD_RESERVATION_FAILED");return;}
    /* All libc and direct-SVC filesystem calls share the syscall namespace. */
    if(!tp_signal_chain_prepare()){fail(env,"ART_SIGNAL_CHAIN_UNAVAILABLE");return;}
    if(tp_syscall_namespace_install_with_signal_chain(tp_signal_chain_claim,reserve_signal_delivery,tp_signal_chain_user_action)!=0){
        bool ready;unsigned status;uint64_t roles,other;tp_signal_user_delivery_state(&ready,&status,&roles,&other);
        char reason[128];snprintf(reason,sizeof(reason),"NATIVE_SYSCALL_NAMESPACE_UNAVAILABLE:userDeliveryStatus=%u",status);fail(env,reason);
    }
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_syscallNamespaceNative(JNIEnv *env,jclass type){
    (void)type;uint64_t counts[4];tp_syscall_namespace_counts(counts);char value[256];
    snprintf(value,sizeof(value),"{\"openat\":%llu,\"fstatat\":%llu,\"readlinkat\":%llu,\"faccessat\":%llu}",(unsigned long long)counts[0],(unsigned long long)counts[1],(unsigned long long)counts[2],(unsigned long long)counts[3]);return (*env)->NewStringUTF(env,value);
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
