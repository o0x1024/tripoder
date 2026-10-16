#include "tripoder_fixed_libc.h"
#include "tripoder_thread_park.h"
#include "path_policy.h"
#include "io_adapter.h"
#include <jni.h>
#include <stdatomic.h>
#include <string.h>
#include <stdio.h>

static _Atomic bool attempted;
static void fail(JNIEnv *env,const char *reason){
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type)(*env)->ThrowNew(env,type,reason);
}
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_installNative(JNIEnv *env,jclass unused,jobjectArray input){
    (void)unused;bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true)){fail(env,"NATIVE_STORAGE_ALREADY_ATTEMPTED");return;}
    if(!input||(*env)->GetArrayLength(env,input)!=5){fail(env,"NATIVE_STORAGE_ARGUMENTS");return;}
    char paths[5][PATH_MAX];
    for(unsigned i=0;i<5;i++){
        jbyteArray value=(*env)->GetObjectArrayElement(env,input,(jsize)i);
        if(!value){fail(env,"NATIVE_STORAGE_PATH_REQUIRED");return;}
        jsize size=(*env)->GetArrayLength(env,value);
        if(size<=0||size>=PATH_MAX){(*env)->DeleteLocalRef(env,value);fail(env,"NATIVE_STORAGE_PATH_BUDGET");return;}
        (*env)->GetByteArrayRegion(env,value,0,size,(jbyte *)paths[i]);(*env)->DeleteLocalRef(env,value);
        if((*env)->ExceptionCheck(env))return;
        if(memchr(paths[i],0,(size_t)size)){fail(env,"NATIVE_STORAGE_PATH_NUL");return;}
        paths[i][size]=0;
    }
    if(tp_configure(paths[0],paths[1],paths[2],paths[3],paths[4])!=0){fail(env,"NATIVE_STORAGE_CONFIGURATION_FAILED");return;}
    if(tp_park_prepare_process()!=TP_PARK_OK){fail(env,"NATIVE_STORAGE_THREAD_RESERVATION_FAILED");return;}
    const struct tp_fixed_libc_replacements replacements={tp_openat,tp_fstatat,tp_unlinkat,tp_mkdirat,tp_renameat2,
        tp_faccessat,tp_readlinkat,tp_chdir,tp_symlinkat,tp_linkat};
    enum tp_fixed_libc_status prepared=tp_fixed_libc_prepare(&replacements);
    enum tp_fixed_libc_status activated=prepared==TP_FIXED_LIBC_OK?tp_fixed_libc_activate():TP_FIXED_LIBC_STATE;
    if(prepared!=TP_FIXED_LIBC_OK||activated!=TP_FIXED_LIBC_OK){
        struct tp_fixed_libc_report report;tp_fixed_libc_report(&report);char reason[600];
        snprintf(reason,sizeof(reason),"NATIVE_STORAGE_PUBLICATION_FAILED:prepare=%d:activate=%d:pause=%d:observed=%u:parked=%u:pendingTid=%d:maskValid=%d:mask=%llx:errno=%d:absolute=%u:changed=%u:admission=%u:attempts=%u:elapsedNs=%llu:contextStatus=%d:contextIndex=%d:unsafeTid=%d:unsafePc=%llx:quiescenceRejections=%u",
            prepared,activated,report.pause_status,report.pause.observed,report.pause.parked,report.pause.pending_tid,
            report.pause.pending_mask_valid,(unsigned long long)report.pause.pending_signal_mask,report.pause.system_error,
            report.absolute_entries,report.changed_words,report.admission_outcome,report.admission_attempts,
            (unsigned long long)report.admission_elapsed_ns,report.context_status,report.context_index,report.unsafe_tid,
            (unsigned long long)report.unsafe_pc,report.quiescence_rejections);
        fail(env,reason);return;
    }
    struct tp_fixed_libc_report report;tp_fixed_libc_report(&report);
    if(!report.ready||!report.enabled||report.validated_entries!=10||report.changed_words!=40||report.absolute_entries!=10||report.publications!=2||
       report.restorations||!report.source_bti||!report.lease_held)
        fail(env,"NATIVE_STORAGE_PUBLICATION_UNCONFIRMED");
}
