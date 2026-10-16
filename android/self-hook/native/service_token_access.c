#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>

static jclass service_class,manager_class;
static jfieldID manager_field,token_field;
static jmethodID stop_method;
static _Atomic bool attempted,ready;
static void fail(JNIEnv *env,const char *reason){
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");if(type)(*env)->ThrowNew(env,type,reason);
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_ServiceTokenAccess_prepareNative(JNIEnv *env,jclass type,
    jclass service,jclass manager,jobject manager_member,jobject token_member,jobject stop_member){
    (void)type;bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true)||!service||!manager||!manager_member||!token_member||!stop_member)return JNI_FALSE;
    jclass s=(*env)->NewGlobalRef(env,service),m=NULL;
    if(!s)goto rejected;m=(*env)->NewGlobalRef(env,manager);if(!m)goto rejected;
    jfieldID mf=(*env)->FromReflectedField(env,manager_member);if(!mf||(*env)->ExceptionCheck(env))goto rejected;
    jfieldID tf=(*env)->FromReflectedField(env,token_member);if(!tf||(*env)->ExceptionCheck(env))goto rejected;
    jmethodID method=(*env)->FromReflectedMethod(env,stop_member);if(!method||(*env)->ExceptionCheck(env))goto rejected;
    service_class=s;manager_class=m;manager_field=mf;token_field=tf;stop_method=method;
    atomic_store_explicit(&ready,true,memory_order_release);return JNI_TRUE;
rejected:
    if(s)(*env)->DeleteGlobalRef(env,s);if(m)(*env)->DeleteGlobalRef(env,m);return JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_ServiceTokenAccess_stopNative(JNIEnv *env,jclass type,
    jobject service,jobject component,jint start_id){
    (void)type;
    if(!atomic_load_explicit(&ready,memory_order_acquire)||!service||!component||!(*env)->IsInstanceOf(env,service,service_class)){
        fail(env,"SERVICE_TOKEN_NATIVE_STATE");return JNI_FALSE;}
    if((*env)->PushLocalFrame(env,4)<0)return JNI_FALSE;
    jboolean result=JNI_FALSE;
    jobject manager=(*env)->GetObjectField(env,service,manager_field);
    if((*env)->ExceptionCheck(env))goto done;
    jobject token=(*env)->GetObjectField(env,service,token_field);
    if((*env)->ExceptionCheck(env))goto done;
    if(!manager||!token||!(*env)->IsInstanceOf(env,manager,manager_class)){fail(env,"SERVICE_TOKEN_PHYSICAL_STATE_MISSING");goto done;}
    result=(*env)->CallBooleanMethod(env,manager,stop_method,component,token,start_id);
done:
    (*env)->PopLocalFrame(env,NULL);return result;
}
