#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include "physical_application.h"

/* Ordinary JNI only. No ArtMethod pointer, managed getter, field mutation or local-ref retention. */
enum phase { UNPREPARED, PREPARING, READY, FAILED };
enum root { THREAD_CLASS, HOST, THREAD, APK, LOGICAL, ROOT_COUNT };
enum field { INITIAL_APPLICATION, LOADED_APK, APK_APPLICATION, CURRENT_THREAD, FIELD_COUNT };
static pthread_mutex_t physical_lock=PTHREAD_MUTEX_INITIALIZER;
static struct {
    enum phase phase;
    jobject roots[ROOT_COUNT];
    jfieldID fields[FIELD_COUNT];
    unsigned retained;
    unsigned long long checks;
    const char *reason;
} physical={.phase=UNPREPARED,.reason="NOT_PREPARED"};

static bool pending(JNIEnv *env){return (*env)->ExceptionCheck(env)==JNI_TRUE;}
static bool same(JNIEnv *env,jobject a,jobject b){return a&&b&&(*env)->IsSameObject(env,a,b)==JNI_TRUE;}
static void reject(JNIEnv *env,const char *reason){
    physical.reason=reason;
    if(pending(env))return;
    jclass error=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(error){(*env)->ThrowNew(env,error,reason);(*env)->DeleteLocalRef(env,error);}
}
static void release_preparation(JNIEnv *env){
    for(unsigned i=0;i<ROOT_COUNT;i++)if(physical.roots[i]){
        (*env)->DeleteGlobalRef(env,physical.roots[i]);physical.roots[i]=NULL;
    }
    physical.retained=0;
}
static bool retain(JNIEnv *env,unsigned slot,jobject value){
    if(!value||slot>=ROOT_COUNT||physical.roots[slot])return false;
    physical.roots[slot]=(*env)->NewGlobalRef(env,value);
    if(!physical.roots[slot]||pending(env))return false;
    physical.retained++;return true;
}
static bool observe(JNIEnv *env,jobject expected_host){
    physical.reason="PHYSICAL_APPLICATION_OBSERVATION_EXCEPTION";
    if(!same(env,expected_host,physical.roots[HOST])){reject(env,"PHYSICAL_APPLICATION_HOST_MISMATCH");return false;}
    if((*env)->PushLocalFrame(env,8)<0)return false;
    bool valid=false;
    jobject thread=(*env)->GetStaticObjectField(env,(jclass)physical.roots[THREAD_CLASS],physical.fields[CURRENT_THREAD]);
    if(pending(env))goto done;
    if(!same(env,thread,physical.roots[THREAD])){reject(env,"PHYSICAL_APPLICATION_THREAD_CHANGED");goto done;}
    jobject initial=(*env)->GetObjectField(env,thread,physical.fields[INITIAL_APPLICATION]);
    if(pending(env))goto done;
    if(!same(env,initial,physical.roots[HOST])){
        // A hot-fix Application may become ActivityThread's initial application.
        // It must be attached to the exact logical LoadedApk registered by this
        // process's sole ApplicationAdapter; the physical host APK stays fixed.
        jobject logical=initial&&physical.roots[LOGICAL]?(*env)->GetObjectField(env,initial,physical.fields[LOADED_APK]):NULL;
        if(pending(env)||!same(env,logical,physical.roots[LOGICAL])){reject(env,"PHYSICAL_APPLICATION_INITIAL_CHANGED");goto done;}
    }
    jobject apk=(*env)->GetObjectField(env,physical.roots[HOST],physical.fields[LOADED_APK]);
    if(pending(env))goto done;
    if(!same(env,apk,physical.roots[APK])){reject(env,"PHYSICAL_APPLICATION_APK_CHANGED");goto done;}
    jobject owner=(*env)->GetObjectField(env,apk,physical.fields[APK_APPLICATION]);
    if(pending(env))goto done;
    if(!same(env,owner,physical.roots[HOST])){reject(env,"PHYSICAL_APPLICATION_APK_OWNER_CHANGED");goto done;}
    valid=true;physical.checks++;physical.reason="READY";
done:
    (*env)->PopLocalFrame(env,NULL);return valid;
}
bool tp_physical_bind_logical(JNIEnv *env,jobject host,jobject logical){
    bool valid=false;pthread_mutex_lock(&physical_lock);
    if(physical.phase!=READY||!same(env,host,physical.roots[HOST])||!logical||same(env,logical,physical.roots[APK]))reject(env,"PHYSICAL_LOGICAL_BINDING_INVALID");
    else if(physical.roots[LOGICAL]){valid=same(env,logical,physical.roots[LOGICAL]);if(!valid)reject(env,"PHYSICAL_LOGICAL_BINDING_CHANGED");}
    else valid=retain(env,LOGICAL,logical);
    pthread_mutex_unlock(&physical_lock);return valid;
}

JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_PhysicalApplication_prepareNative(JNIEnv *env,jclass ignored,
    jobject host,jclass application_class,jclass thread_class,jclass loaded_apk_class,jobjectArray fields){
    (void)ignored;
    if(pending(env))return;
    pthread_mutex_lock(&physical_lock);
    if(physical.phase!=UNPREPARED){reject(env,"PHYSICAL_APPLICATION_ALREADY_ATTEMPTED");goto unlock;}
    physical.phase=PREPARING;physical.reason="PHYSICAL_APPLICATION_PREPARATION_EXCEPTION";
    if(!host||!application_class||!thread_class||!loaded_apk_class||!fields||
        (*env)->GetArrayLength(env,fields)!=FIELD_COUNT||pending(env)||
        !(*env)->IsInstanceOf(env,host,application_class)||pending(env)){
        reject(env,"PHYSICAL_APPLICATION_ARGUMENT_SHAPE");goto fail;
    }
    if((*env)->PushLocalFrame(env,12)<0)goto fail;
    for(unsigned i=0;i<FIELD_COUNT;i++){
        jobject value=(*env)->GetObjectArrayElement(env,fields,(jsize)i);
        if(!value||pending(env)){reject(env,"PHYSICAL_APPLICATION_FIELD_REQUIRED");goto fail_frame;}
        physical.fields[i]=(*env)->FromReflectedField(env,value);
        (*env)->DeleteLocalRef(env,value);
        if(!physical.fields[i]||pending(env)){reject(env,"PHYSICAL_APPLICATION_FIELD_ID_REQUIRED");goto fail_frame;}
    }
    jobject thread=(*env)->GetStaticObjectField(env,thread_class,physical.fields[CURRENT_THREAD]);
    if(pending(env))goto fail_frame;
    if(!thread||!(*env)->IsInstanceOf(env,thread,thread_class)){
        reject(env,"PHYSICAL_APPLICATION_THREAD_REQUIRED");goto fail_frame;
    }
    jobject initial=(*env)->GetObjectField(env,thread,physical.fields[INITIAL_APPLICATION]);
    if(pending(env))goto fail_frame;
    if(!same(env,initial,host)){reject(env,"PHYSICAL_APPLICATION_INITIAL_MISMATCH");goto fail_frame;}
    jobject apk=(*env)->GetObjectField(env,host,physical.fields[LOADED_APK]);
    if(pending(env))goto fail_frame;
    if(!apk||!(*env)->IsInstanceOf(env,apk,loaded_apk_class)){
        reject(env,"PHYSICAL_APPLICATION_APK_REQUIRED");goto fail_frame;
    }
    jobject owner=(*env)->GetObjectField(env,apk,physical.fields[APK_APPLICATION]);
    if(pending(env))goto fail_frame;
    if(!same(env,owner,host)){reject(env,"PHYSICAL_APPLICATION_APK_OWNER_MISMATCH");goto fail_frame;}
    if(!retain(env,THREAD_CLASS,thread_class)||!retain(env,HOST,host)||!retain(env,THREAD,thread)||!retain(env,APK,apk)){
        reject(env,"PHYSICAL_APPLICATION_ROOT_ALLOCATION");goto fail_frame;
    }
    /* Recheck after allocation safepoints, with all identities retained through GC. */
    if(!observe(env,host))goto fail_frame;
    physical.phase=READY;physical.reason="READY";
    (*env)->PopLocalFrame(env,NULL);goto unlock;
fail_frame:
    (*env)->PopLocalFrame(env,NULL);
fail:
    physical.phase=FAILED;release_preparation(env);
    reject(env,physical.reason);
unlock:
    pthread_mutex_unlock(&physical_lock);
}

JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_PhysicalApplication_checkNative(JNIEnv *env,jclass ignored,jobject expected_host){
    (void)ignored;
    if(pending(env))return;
    pthread_mutex_lock(&physical_lock);
    if(physical.phase!=READY){reject(env,"PHYSICAL_APPLICATION_NOT_READY");goto done;}
    if(!observe(env,expected_host)){
        physical.phase=FAILED;
        /* Published roots intentionally remain alive until process exit. No repair-and-continue. */
        reject(env,physical.reason);
    }
done:
    pthread_mutex_unlock(&physical_lock);
}

JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_PhysicalApplication_reportNative(JNIEnv *env,jclass ignored){
    (void)ignored;
    if(pending(env))return NULL;
    char report[256];
    pthread_mutex_lock(&physical_lock);
    snprintf(report,sizeof(report),"{\"ready\":%s,\"retainedGlobalRefs\":%u,\"checks\":%llu,\"stage\":\"%s\"}",
        physical.phase==READY?"true":"false",physical.retained,physical.checks,physical.reason);
    pthread_mutex_unlock(&physical_lock);
    return (*env)->NewStringUTF(env,report);
}

JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_PhysicalApplication_threadNative(JNIEnv *env,jclass ignored,jobject expected_host){
    (void)ignored;if(pending(env))return NULL;jobject result=NULL;
    pthread_mutex_lock(&physical_lock);
    if(physical.phase!=READY)reject(env,"PHYSICAL_APPLICATION_NOT_READY");
    else if(observe(env,expected_host))result=(*env)->NewLocalRef(env,physical.roots[THREAD]);
    else{physical.phase=FAILED;reject(env,physical.reason);}
    pthread_mutex_unlock(&physical_lock);return result;
}
