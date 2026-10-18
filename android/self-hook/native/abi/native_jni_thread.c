#include "native_jni_thread.h"
#include "native_hook_internal.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>

/* Only native-born threads attached by this bridge acquire a lease. The key
 * and destructor code are process-lifetime, like the published Hook image.
 * No JNIEnv is cached across calls: an external explicit detach is observed
 * by GetEnv before any JNI operation on the next invocation. */
struct lease {JavaVM *vm;};
static pthread_once_t once=PTHREAD_ONCE_INIT;
static pthread_key_t key;
static bool initialized;
static char exiting;
static _Atomic uint64_t leases,attachments,detachments;
static void release(void *opaque){
    if(!opaque)return;if(opaque==&exiting)tp_native_stop(225);
    struct lease *lease=opaque;
    if(pthread_setspecific(key,&exiting))tp_native_stop(225);
    JNIEnv *env=NULL;jint state=(*lease->vm)->GetEnv(lease->vm,(void **)&env,JNI_VERSION_1_6);
    if(state==JNI_OK){
        if((*lease->vm)->DetachCurrentThread(lease->vm)!=JNI_OK)tp_native_stop(224);
        atomic_fetch_add_explicit(&detachments,1,memory_order_relaxed);
    }else if(state!=JNI_EDETACHED)tp_native_stop(224);
    if(pthread_setspecific(key,NULL))tp_native_stop(225);
    atomic_fetch_sub_explicit(&leases,1,memory_order_relaxed);free(lease);
}
static void initialize(void){initialized=pthread_key_create(&key,release)==0;}
bool tp_jni_thread_initialize(void){return pthread_once(&once,initialize)==0&&initialized;}
bool tp_jni_thread_env(JavaVM *vm,JNIEnv **env){
    if(!vm||!env||!initialized)return false;
    struct lease *lease=pthread_getspecific(key);
    if((void *)lease==&exiting|| (lease&&lease->vm!=vm))return false;
    jint state=(*vm)->GetEnv(vm,(void **)env,JNI_VERSION_1_6);
    if(state==JNI_OK)return true; // A pre-attached thread is borrowed.
    if(state!=JNI_EDETACHED)return false;
    bool created=lease==NULL;
    if(created){lease=malloc(sizeof(*lease));if(!lease)return false;lease->vm=vm;}
    // A native Hook must not keep the VM alive after managed shutdown.
    if((*vm)->AttachCurrentThreadAsDaemon(vm,env,NULL)!=JNI_OK){if(created)free(lease);return false;}
    if(created&&pthread_setspecific(key,lease)){
        if((*vm)->DetachCurrentThread(vm)!=JNI_OK)tp_native_stop(224);free(lease);return false;
    }
    if(created)atomic_fetch_add_explicit(&leases,1,memory_order_relaxed);
    atomic_fetch_add_explicit(&attachments,1,memory_order_relaxed);return true;
}
void tp_jni_thread_resources(struct tp_jni_thread_stats *out){
    *out=(struct tp_jni_thread_stats){atomic_load_explicit(&leases,memory_order_relaxed),
        atomic_load_explicit(&attachments,memory_order_relaxed),atomic_load_explicit(&detachments,memory_order_relaxed)};
}
