#include "art_reflection_view.h"
#include <stdatomic.h>
#include <stdio.h>
#include <unistd.h>

enum { HOST,GUEST,INFO,LOADER,FACTORY,RESOURCES,THREAD,PHYSICAL,LOGICAL,CONTEXT,DECLARED,ROOTS };
static struct {
    jclass classes[7];jfieldID fields[12];jmethodID methods[9];jobject roots[ROOTS];
    unsigned retained,checks,constructs,attaches;
    pid_t tid;
    const char *stage;
} state={.stage="NOT_STARTED"};
static _Atomic bool attempted,ownership_ready,view_attempted,begun,creation_attempted;
static const char *class_names[]={"android/app/Application","android/app/ActivityThread","android/app/LoadedApk",
    "android/app/ContextImpl","android/content/pm/ApplicationInfo","android/app/QueuedWork","android/content/ContextWrapper"};

static void fail(JNIEnv *env,const char *reason){
    state.stage=reason;
    if((*env)->ExceptionCheck(env))return;
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type)(*env)->ThrowNew(env,type,reason);
}
static bool same(JNIEnv *env,jobject a,jobject b){return a&&b&&(*env)->IsSameObject(env,a,b);}
static bool keep(JNIEnv *env,unsigned slot,jobject value){
    if(!value||slot>=ROOTS||state.roots[slot])return false;
    state.roots[slot]=(*env)->NewGlobalRef(env,value);
    if(state.roots[slot]){state.retained++;return true;}return false;
}
static bool physical(JNIEnv *env,bool attached){
    if(state.tid&&state.tid!=gettid())return false;
    if((*env)->PushLocalFrame(env,16)<0)return false;
    jobject host=state.roots[HOST],guest=state.roots[GUEST],thread=state.roots[THREAD];
    jobject current=(*env)->GetStaticObjectField(env,state.classes[1],state.fields[11]);
    jobject initial=(*env)->GetObjectField(env,thread,state.fields[0]);
    jobject apk=(*env)->GetObjectField(env,host,state.fields[1]);
    jobject host_owner=(*env)->GetObjectField(env,state.roots[PHYSICAL],state.fields[2]);
    bool valid=!(*env)->ExceptionCheck(env)&&same(env,current,thread)&&same(env,initial,host)&&
        same(env,apk,state.roots[PHYSICAL])&&same(env,host_owner,host);
    if(valid&&state.roots[LOGICAL]){
        jobject owner=(*env)->GetObjectField(env,state.roots[LOGICAL],state.fields[2]);
        valid=same(env,owner,guest)&&!same(env,state.roots[PHYSICAL],state.roots[LOGICAL]);
        const unsigned expected[]={LOADER,LOADER,RESOURCES,FACTORY,DECLARED};
        for(unsigned i=0;valid&&i<5;i++){
            jobject value=(*env)->GetObjectField(env,state.roots[LOGICAL],state.fields[4+i]);
            valid=same(env,value,state.roots[expected[i]]);
        }
    }
    if(valid&&attached){jobject guest_apk=(*env)->GetObjectField(env,guest,state.fields[1]);
        valid=same(env,guest_apk,state.roots[LOGICAL]);}
    (*env)->PopLocalFrame(env,NULL);
    if(valid)state.checks++;
    return valid;
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_prepareOwnershipNative(JNIEnv *env,jclass unused,
    jobject host,jobjectArray fields,jobjectArray methods){
    (void)unused;bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true)){fail(env,"APPLICATION_ALREADY_CONFIGURED");return;}
    state.stage="MEMBER_IDS";
    if(!fields||!methods||(*env)->GetArrayLength(env,fields)!=12||(*env)->GetArrayLength(env,methods)!=9)goto error;
    for(unsigned i=0;i<7;i++){
        jclass local=(*env)->FindClass(env,class_names[i]);if(!local)goto error;
        state.classes[i]=(*env)->NewGlobalRef(env,local);(*env)->DeleteLocalRef(env,local);
        if(!state.classes[i])goto error;state.retained++;
    }
    for(unsigned i=0;i<12;i++){
        jobject value=(*env)->GetObjectArrayElement(env,fields,(jsize)i);
        if(!value)goto error;state.fields[i]=(*env)->FromReflectedField(env,value);(*env)->DeleteLocalRef(env,value);
        if(!state.fields[i]||(*env)->ExceptionCheck(env))goto error;
    }
    for(unsigned i=0;i<9;i++){
        jobject value=(*env)->GetObjectArrayElement(env,methods,(jsize)i);
        if(!value)goto error;state.methods[i]=(*env)->FromReflectedMethod(env,value);(*env)->DeleteLocalRef(env,value);
        if(!state.methods[i]||(*env)->ExceptionCheck(env))goto error;
    }
    if(!keep(env,HOST,host))goto error;
    state.stage="PHYSICAL_IDENTITY";
    jobject thread=(*env)->GetStaticObjectField(env,state.classes[1],state.fields[11]);
    jobject apk=(*env)->GetObjectField(env,host,state.fields[1]);
    if((*env)->ExceptionCheck(env)||!keep(env,THREAD,thread)||!keep(env,PHYSICAL,apk)||!physical(env,false))goto error;
    state.stage="OWNERSHIP_CAPTURED";atomic_store_explicit(&ownership_ready,true,memory_order_release);return;
error:
    fail(env,state.stage);
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_beginViewNative(JNIEnv *env,jclass unused){
    (void)unused;bool expected=false;
    if(!atomic_load_explicit(&ownership_ready,memory_order_acquire)||
       !atomic_compare_exchange_strong(&view_attempted,&expected,true)){fail(env,"APPLICATION_VIEW_ORDER");return;}
    state.stage="PRECONSTRUCTION_VIEW";
    jobject initial=(*env)->ToReflectedField(env,state.classes[1],state.fields[0],JNI_FALSE);
    bool valid=initial&&physical(env,false)&&tp_art_reflection_prepare_pending(env,initial,state.roots[THREAD],state.roots[HOST],state.classes[0])&&tp_art_reflection_toggle(env,true);
    if(initial)(*env)->DeleteLocalRef(env,initial);
    if(!valid){fail(env,state.stage);return;}
    state.stage="AWAITING_GUEST";atomic_store_explicit(&begun,true,memory_order_release);
}
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_current(JNIEnv *env,jclass unused){
    (void)unused;if(!atomic_load_explicit(&begun,memory_order_acquire)){fail(env,"APPLICATION_NOT_BEGUN");return NULL;}
    return tp_art_reflection_value(env);
}
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_createNative(JNIEnv *env,jclass unused,
    jobject host,jobject guest,jobject info,jobject loader,jobject factory,jobject resources){
    (void)unused;bool expected=false;
    if(!atomic_load_explicit(&begun,memory_order_acquire)||!atomic_compare_exchange_strong(&creation_attempted,&expected,true)
        ||!same(env,host,state.roots[HOST])){fail(env,"APPLICATION_CREATE_STATE");return NULL;}
    state.tid=gettid();
    if(!keep(env,GUEST,guest)||!keep(env,INFO,info)||!keep(env,LOADER,loader)||!keep(env,FACTORY,factory)||!keep(env,RESOURCES,resources))goto error;
    if(!physical(env,false)||!tp_art_reflection_promote(env,guest))goto error;
    jobject thread=state.roots[THREAD],apk=state.roots[PHYSICAL];
    state.stage="LOGICAL_LOADED_APK";
    jmethodID copy=(*env)->GetMethodID(env,state.classes[4],"<init>","(Landroid/content/pm/ApplicationInfo;)V");
    jfieldID data_dir=(*env)->GetFieldID(env,state.classes[4],"dataDir","Ljava/lang/String;");
    jfieldID component_factory=(*env)->GetFieldID(env,state.classes[4],"appComponentFactory","Ljava/lang/String;");
    if(!copy||!data_dir||!component_factory)goto error;
    jobject declared=(*env)->NewObject(env,state.classes[4],copy,info);
    jobject construction=(*env)->NewObject(env,state.classes[4],copy,info);
    jobject data=(*env)->GetObjectField(env,info,data_dir);
    if(!declared||!construction||!data||(*env)->ExceptionCheck(env))goto error;
    (*env)->SetObjectField(env,declared,state.fields[9],data);
    (*env)->SetObjectField(env,construction,state.fields[9],data);
    (*env)->SetObjectField(env,construction,component_factory,NULL);
    jobject compatibility=(*env)->CallObjectMethod(env,apk,state.methods[3]);
    if(!compatibility||(*env)->ExceptionCheck(env))goto error;
    jobject logical=(*env)->NewObject(env,state.classes[2],state.methods[2],thread,construction,compatibility,NULL,
        JNI_FALSE,JNI_TRUE,JNI_FALSE);
    if(!logical||(*env)->ExceptionCheck(env))goto error;
    state.constructs++;
    (*env)->SetObjectField(env,logical,state.fields[8],declared);
    (*env)->SetObjectField(env,logical,state.fields[4],loader);
    (*env)->SetObjectField(env,logical,state.fields[5],loader);
    (*env)->SetObjectField(env,logical,state.fields[6],resources);
    (*env)->SetObjectField(env,logical,state.fields[7],factory);
    (*env)->SetObjectField(env,logical,state.fields[2],guest);
    if((*env)->ExceptionCheck(env)||!keep(env,LOGICAL,logical)||!keep(env,DECLARED,declared))goto error;
    state.stage="LOGICAL_CONTEXT";
    jclass context_type=(*env)->FindClass(env,"android/content/Context");
    jmethodID package_name=context_type?(*env)->GetMethodID(env,context_type,"getPackageName","()Ljava/lang/String;"):NULL;
    jobject physical_package=package_name?(*env)->CallObjectMethod(env,host,package_name):NULL;
    if(!physical_package||(*env)->ExceptionCheck(env))goto error;
    jobject context=(*env)->CallStaticObjectMethod(env,state.classes[3],state.methods[4],thread,logical,physical_package);
    if(!context||(*env)->ExceptionCheck(env))goto error;
    (*env)->CallVoidMethod(env,context,state.methods[5],guest);
    if((*env)->ExceptionCheck(env)||!keep(env,CONTEXT,context)||!physical(env,false))goto error;
    state.stage="READY_FOR_ATTACH";
    return (*env)->NewLocalRef(env,context);
error:
    fail(env,state.stage);return NULL;
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_attachNative(JNIEnv *env,jclass unused,jobject wrapper){
    (void)unused;
    if(state.tid!=gettid()||state.attaches||!state.roots[CONTEXT]||!physical(env,false)){fail(env,"APPLICATION_ATTACH_STATE");return;}
    if((*env)->PushLocalFrame(env,24)<0)return;
    jobject cursor=wrapper;unsigned depth=0;
    while(cursor&&(*env)->IsInstanceOf(env,cursor,state.classes[6])&&depth<16){
        cursor=(*env)->GetObjectField(env,cursor,state.fields[10]);depth++;
    }
    if((*env)->ExceptionCheck(env)||!same(env,cursor,state.roots[CONTEXT])){fail(env,"APPLICATION_CONTEXT_UNWRAP");goto done;}
    state.stage="APPLICATION_ATTACH";
    state.attaches++;
    (*env)->CallNonvirtualVoidMethod(env,state.roots[GUEST],state.classes[0],state.methods[6],wrapper);
    if((*env)->ExceptionCheck(env))goto done;
    if(!physical(env,true)){fail(env,"APPLICATION_POST_ATTACH_IDENTITY");goto done;}
    state.stage="ATTACHED";
done:
    (*env)->PopLocalFrame(env,NULL);
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_checkNative(JNIEnv *env,jclass unused){
    (void)unused;
    if(!state.attaches||!physical(env,true))fail(env,"APPLICATION_OWNERSHIP_VIOLATION");
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_closeViewNative(JNIEnv *env,jclass unused){
    (void)unused;
    if((state.tid&&state.tid!=gettid())||!tp_art_reflection_toggle(env,false))fail(env,"APPLICATION_VIEW_CLOSE_FAILED");
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_reportNative(JNIEnv *env,jclass unused){
    (void)unused;struct tp_art_reflection_report view;tp_art_reflection_report(&view);
    char text[1024];snprintf(text,sizeof(text),"{\"stage\":\"%s\",\"constructs\":%u,\"attaches\":%u,\"physicalChecks\":%u,"
        "\"retainedGlobalRefs\":%u,\"viewReady\":%s,\"viewEnabled\":%s,\"viewTransitions\":%u,\"viewRoots\":%u,"
        "\"mappedGets\":%llu,\"mappedSets\":%llu,\"denials\":%llu}",state.stage,state.constructs,state.attaches,state.checks,state.retained,
        view.ready?"true":"false",view.enabled?"true":"false",view.transitions,view.retained_roots,
        (unsigned long long)view.mapped_gets,(unsigned long long)view.mapped_sets,(unsigned long long)view.denials);
    return (*env)->NewStringUTF(env,text);
}
