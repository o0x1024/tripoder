#include "art_reflection_view.h"
#include "physical_application.h"
#include <stdatomic.h>
#include <stdio.h>
#include <unistd.h>

enum { HOST,GUEST,INFO,LOADER,OWNER,FACTORY,RESOURCES,THREAD,PHYSICAL,LOGICAL,CONTEXT,DECLARED,PREPARED_FACTORY,ROOTS };
static struct {
    jclass classes[7];jfieldID fields[12];jmethodID methods[10];jobject roots[ROOTS];
    unsigned retained,checks,constructs,attaches;
    pid_t tid;
    const char *stage;
    const char *broken;
} state={.stage="NOT_STARTED"};
static _Atomic bool attempted,ownership_ready,view_attempted,begun,package_attempted,creation_attempted;
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
/**
 * True when candidate resolves inside this installation's verified DEX sequence.
 *
 * <p>The ownership root is the defining loader that holds the verified DEX, not the {@code RuntimeGuestLoader}
 * facade the runtime publishes as the application loader. Android itself lets an application replace the loader it
 * publishes on its own {@code LoadedApk}; hot-fix and plugin runtimes do so during {@code attachBaseContext}. RePlugin,
 * for example, installs a sibling loader built with {@code parent = original.getParent()} and keeps the original as a
 * delegation field, so a parent-chain test rooted at the facade rejects a legitimate plugin loader. Rooting the test at
 * the DEX sequence accepts every loader that can only resolve this guest's classes, while a loader from another
 * installation never reaches this root.
 */
static bool rooted_loader(JNIEnv *env,jobject candidate){
    if(!candidate||!state.roots[OWNER])return false;
    if(same(env,candidate,state.roots[OWNER]))return true;
    jclass type=(*env)->FindClass(env,"java/lang/ClassLoader");
    if(!type)return false;
    jmethodID parent=(*env)->GetMethodID(env,type,"getParent","()Ljava/lang/ClassLoader;");
    if(!parent)return false;
    for(unsigned depth=0;candidate&&depth<32;depth++){
        jobject next=(*env)->CallObjectMethod(env,candidate,parent);
        if((*env)->ExceptionCheck(env))return false;
        if(same(env,next,state.roots[OWNER]))return true;
        candidate=next;
    }
    return false;
}
static bool owned_loader(JNIEnv *env,jobject candidate){
    if(rooted_loader(env,candidate))return true;
    if(!candidate||!state.roots[GUEST]||(*env)->ExceptionCheck(env))return false;
    jclass class_type=(*env)->FindClass(env,"java/lang/Class"),loader_type=(*env)->FindClass(env,"java/lang/ClassLoader");
    if(!class_type||!loader_type)return false;
    jmethodID defining=(*env)->GetMethodID(env,class_type,"getClassLoader","()Ljava/lang/ClassLoader;"),
        name=(*env)->GetMethodID(env,class_type,"getName","()Ljava/lang/String;"),
        load=(*env)->GetMethodID(env,loader_type,"loadClass","(Ljava/lang/String;)Ljava/lang/Class;");
    if(!defining||!name||!load)return false;
    jobject candidate_class=(*env)->GetObjectClass(env,candidate),application_class=(*env)->GetObjectClass(env,state.roots[GUEST]);
    jobject loader_owner=(*env)->CallObjectMethod(env,candidate_class,defining);
    if((*env)->ExceptionCheck(env)||!rooted_loader(env,loader_owner))return false;
    jobject application_owner=(*env)->CallObjectMethod(env,application_class,defining);
    if((*env)->ExceptionCheck(env)||!rooted_loader(env,application_owner))return false;
    jobject application_name=(*env)->CallObjectMethod(env,application_class,name);
    if((*env)->ExceptionCheck(env))return false;
    jobject resolved=(*env)->CallObjectMethod(env,candidate,load,application_name);
    return !(*env)->ExceptionCheck(env)&&same(env,resolved,application_class);
}
/** A guest-owned Application is one the guest attached to its own LoadedApk: the structural fact a hot-fix proxy creates. */
static bool guest_attached(JNIEnv *env,jobject application){
    if(!application||!state.roots[LOGICAL])return false;
    if(!(*env)->IsInstanceOf(env,application,state.classes[0]))return false;
    jobject apk=(*env)->GetObjectField(env,application,state.fields[1]);
    return same(env,apk,state.roots[LOGICAL]);
}
/** A delegate Application a hot-fix or plugin framework built around the guest's own base context (attachBaseContext without attach). It is derived from this guest's Context, which no foreign application can hold. */
static bool guest_delegate(JNIEnv *env,jobject application){
    if(!application||!state.roots[CONTEXT]||!(*env)->IsInstanceOf(env,application,state.classes[0]))return false;
    jobject cursor=application;
    for(unsigned depth=0;cursor&&depth<16&&(*env)->IsInstanceOf(env,cursor,state.classes[6]);depth++){
        cursor=(*env)->GetObjectField(env,cursor,state.fields[10]);
        if(same(env,cursor,state.roots[CONTEXT]))return true;
    }
    return false;
}
static bool physical(JNIEnv *env,bool attached){
    if(state.tid&&state.tid!=gettid())return false;
    if((*env)->PushLocalFrame(env,64)<0)return false;
    jobject host=state.roots[HOST],guest=state.roots[GUEST],thread=state.roots[THREAD];
    jobject current=(*env)->GetStaticObjectField(env,state.classes[1],state.fields[11]);
    jobject initial=(*env)->GetObjectField(env,thread,state.fields[0]);
    jobject apk=(*env)->GetObjectField(env,host,state.fields[1]);
    jobject host_owner=(*env)->GetObjectField(env,state.roots[PHYSICAL],state.fields[2]);
    // Which invariant broke is recorded so a failure names the field a guest rewrote, instead of one undifferentiated identity error.
    state.broken="";
    bool valid=!(*env)->ExceptionCheck(env);
    if(!valid)state.broken="exception";
    else if(!same(env,current,thread))valid=false,state.broken="ActivityThread.currentActivityThread";
    // The process application is guest-owned state once a hot-fix proxy replaces it: what remains fixed is that the
    // replacement was attached to this guest's LoadedApk, so no foreign application can become the process application.
    else if(!same(env,initial,host)&&!guest_attached(env,initial)&&!guest_delegate(env,initial))valid=false,state.broken="ActivityThread.mInitialApplication";
    else if(!same(env,apk,state.roots[PHYSICAL]))valid=false,state.broken="host.mLoadedApk";
    else if(!same(env,host_owner,host))valid=false,state.broken="host LoadedApk.mApplication";
    if(valid&&state.roots[LOGICAL]){
        jobject owner=(*env)->GetObjectField(env,state.roots[LOGICAL],state.fields[2]);
        valid=((!attached&&!owner)||same(env,owner,guest)||guest_attached(env,owner)||guest_delegate(env,owner))&&!same(env,state.roots[PHYSICAL],state.roots[LOGICAL]);
        if(!valid){
            state.broken="logical LoadedApk.mApplication";
            // Name the relation of the replacing Application to the three LoadedApk identities, so a plugin framework's move is visible.
            jobject owner_apk=owner&&(*env)->IsInstanceOf(env,owner,state.classes[0])?(*env)->GetObjectField(env,owner,state.fields[1]):NULL;
            static char detail[112];
            snprintf(detail,sizeof(detail),"logical LoadedApk.mApplication[owner=%s,att=%d,host=%d,apkL=%d,apkP=%d,apkN=%d]",owner?"set":"null",(int)attached,(int)same(env,owner,host),(int)same(env,owner_apk,state.roots[LOGICAL]),(int)same(env,owner_apk,state.roots[PHYSICAL]),(int)(owner_apk==NULL));
            state.broken=detail;
            (*env)->ExceptionClear(env);
        }
        // The class loader pair belongs to the guest, exactly as its resources do: platform hot-fix and plugin runtimes
        // replace it during attachBaseContext to publish patch/plugin DEX. Accept any replacement that still resolves
        // inside this installation's verified DEX sequence, and reject one that would escape it.
        const unsigned expected[]={OWNER,OWNER,RESOURCES,state.roots[FACTORY]?FACTORY:PREPARED_FACTORY,DECLARED};
        const char *names[]={"logical.mClassLoader","logical.mDefaultClassLoader","logical.mResources","logical.mAppComponentFactory","logical.mApplicationInfo"};
        for(unsigned i=0;valid&&i<5;i++){
            jobject value=(*env)->GetObjectField(env,state.roots[LOGICAL],state.fields[4+i]);
            if(expected[i]==RESOURCES&&value&&!same(env,value,state.roots[RESOURCES])){
                // The guest's own LoadedApk.mResources is the guest's to replace (resource hot-update and theming frameworks do). It holds no host identity, so follow it instead of rejecting it.
                jobject followed=(*env)->NewGlobalRef(env,value);
                if(followed){(*env)->DeleteGlobalRef(env,state.roots[RESOURCES]);state.roots[RESOURCES]=followed;}
            }
            if(expected[i]==OWNER){
                if(!owned_loader(env,value)){valid=false;state.broken=names[i];}
            }else{
                valid=(!value&&!state.roots[expected[i]])||same(env,value,state.roots[expected[i]]);
                if(!valid)state.broken=names[i];
            }
        }
    }
    if(valid&&attached){jobject guest_apk=(*env)->GetObjectField(env,guest,state.fields[1]);
        valid=same(env,guest_apk,state.roots[LOGICAL]);if(!valid)state.broken="guest.mLoadedApk";}
    (*env)->PopLocalFrame(env,NULL);
    if(valid)state.checks++;
    return valid;
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_prepareOwnershipNative(JNIEnv *env,jclass unused,
    jobject host,jobjectArray fields,jobjectArray methods){
    (void)unused;bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true)){fail(env,"APPLICATION_ALREADY_CONFIGURED");return;}
    state.stage="MEMBER_IDS";
    if(!fields||!methods||(*env)->GetArrayLength(env,fields)!=12||(*env)->GetArrayLength(env,methods)!=10)goto error;
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
    for(unsigned i=0;i<10;i++){
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
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_preparePackageNative(JNIEnv *env,jclass unused,
    jobject host,jobject info,jobject loader,jobject owner,jobject resources){
    (void)unused;bool expected=false;
    if(!atomic_load_explicit(&begun,memory_order_acquire)||!atomic_compare_exchange_strong(&package_attempted,&expected,true)
        ||!same(env,host,state.roots[HOST])){fail(env,"APPLICATION_PACKAGE_STATE");return;}
    state.tid=gettid();
    if(!keep(env,INFO,info)||!keep(env,LOADER,loader)||!keep(env,OWNER,owner)||!keep(env,RESOURCES,resources))goto error;
    state.stage="PACKAGE_LOADER_OWNERSHIP";
    if(!owned_loader(env,loader)||!physical(env,false))goto error;
    jobject thread=state.roots[THREAD],apk=state.roots[PHYSICAL];
    state.stage="LOGICAL_LOADED_APK";
    jmethodID copy=(*env)->GetMethodID(env,state.classes[4],"<init>","(Landroid/content/pm/ApplicationInfo;)V");
    jfieldID data_dir=(*env)->GetFieldID(env,state.classes[4],"dataDir","Ljava/lang/String;");
    jfieldID component_factory=(*env)->GetFieldID(env,state.classes[4],"appComponentFactory","Ljava/lang/String;");
    if(!copy||!data_dir||!component_factory)goto error;
    jobject declared=info;
    jobject construction=(*env)->NewObject(env,state.classes[4],copy,info);
    jobject data=(*env)->GetObjectField(env,info,data_dir);
    if(!declared||!construction||!data||(*env)->ExceptionCheck(env))goto error;
    (*env)->SetObjectField(env,declared,state.fields[9],data);
    (*env)->SetObjectField(env,construction,state.fields[9],data);
    (*env)->SetObjectField(env,construction,component_factory,NULL);
    jfieldID primary_cpu_abi=(*env)->GetFieldID(env,state.classes[4],"primaryCpuAbi","Ljava/lang/String;");
    if(!primary_cpu_abi)goto error;
    jobject abi=(*env)->NewStringUTF(env,"arm64-v8a");if(!abi)goto error;
    (*env)->SetObjectField(env,declared,primary_cpu_abi,abi);
    (*env)->SetObjectField(env,construction,primary_cpu_abi,abi);(*env)->DeleteLocalRef(env,abi);
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
    // The real constructor selected its SDK factory without executing a guest
    // selector. Retain it during the later declared factory's initializer,
    // exactly as createOrUpdateClassLoaderLocked does before assignment.
    jobject prepared_factory=(*env)->GetObjectField(env,logical,state.fields[7]);
    if(!prepared_factory||(*env)->ExceptionCheck(env)||!keep(env,PREPARED_FACTORY,prepared_factory))goto error;
    if((*env)->ExceptionCheck(env)||!keep(env,LOGICAL,logical)||!keep(env,DECLARED,declared))goto error;
    if(!tp_physical_bind_logical(env,host,logical))goto error;
    if(!physical(env,false))goto error;
    state.stage="PACKAGE_PREPARED_BEFORE_FACTORY";return;
error:
    fail(env,state.stage);
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_publishFactoryNative(JNIEnv *env,jclass unused,jobject factory){
    (void)unused;
    if(state.tid!=gettid()||!state.roots[LOGICAL]||state.roots[GUEST]||state.roots[FACTORY]||!factory||!physical(env,false)){fail(env,"APPLICATION_FACTORY_ORDER");return;}
    if(!keep(env,FACTORY,factory)){fail(env,"APPLICATION_FACTORY_ROOT");return;}
    (*env)->SetObjectField(env,state.roots[LOGICAL],state.fields[7],factory);
    if((*env)->ExceptionCheck(env)||!physical(env,false)){fail(env,"APPLICATION_FACTORY_PUBLICATION");return;}
    state.stage="FACTORY_PUBLISHED";
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_publishLoaderNative(JNIEnv *env,jclass unused,jobject loader){
    (void)unused;
    if(state.tid!=gettid()||!state.roots[FACTORY]||state.roots[GUEST]||!physical(env,false)||!owned_loader(env,loader)){fail(env,"APPLICATION_LOADER_ORDER");return;}
    jobject selected=(*env)->NewGlobalRef(env,loader);if(!selected){fail(env,"APPLICATION_LOADER_ROOT");return;}
    (*env)->DeleteGlobalRef(env,state.roots[LOADER]);state.roots[LOADER]=selected;
    (*env)->SetObjectField(env,state.roots[LOGICAL],state.fields[4],loader);
    if((*env)->ExceptionCheck(env)||!physical(env,false)){fail(env,"APPLICATION_LOADER_PUBLICATION");return;}
    state.stage="LOADER_PUBLISHED";
}
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_createNative(JNIEnv *env,jclass unused,
    jobject host,jobject guest,jobject info,jobject loader,jobject owner,jobject factory,jobject resources){
    (void)unused;bool expected=false;
    if(!atomic_load_explicit(&begun,memory_order_acquire)||!atomic_compare_exchange_strong(&creation_attempted,&expected,true)
        ||!same(env,host,state.roots[HOST])||!same(env,info,state.roots[INFO])||!same(env,loader,state.roots[LOADER])
        ||!same(env,owner,state.roots[OWNER])||!same(env,factory,state.roots[FACTORY])){fail(env,"APPLICATION_CREATE_STATE");return NULL;}
    (void)resources;
    if(!keep(env,GUEST,guest)||!physical(env,false)||!tp_art_reflection_promote(env,guest))goto error;
    jobject thread=state.roots[THREAD],logical=state.roots[LOGICAL];
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
    // Android's LoadedApk assigns mApplication after newApplication/attach returns.
    // Keep a delegate explicitly installed by the guest; otherwise publish the attached instance now.
    jobject installed=(*env)->GetObjectField(env,state.roots[LOGICAL],state.fields[2]);
    if(!installed)(*env)->SetObjectField(env,state.roots[LOGICAL],state.fields[2],state.roots[GUEST]);
    if(!tp_art_reflection_complete(env)) {fail(env,"APPLICATION_PUBLICATION_FAILED");goto done;}
    if(!physical(env,true)){static char why[192];snprintf(why,sizeof(why),"APPLICATION_POST_ATTACH_IDENTITY:%s",state.broken?state.broken:"?");fail(env,why);goto done;}
    state.stage="ATTACHED";
done:
    (*env)->PopLocalFrame(env,NULL);
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_checkNative(JNIEnv *env,jclass unused){
    (void)unused;
    if(!state.attaches||!physical(env,true)){static char why[160];snprintf(why,sizeof(why),"APPLICATION_OWNERSHIP_VIOLATION:%s",state.broken&&*state.broken?state.broken:"attachment");fail(env,why);}
}
/** The class loader the guest currently publishes on its own LoadedApk, which hot-fix frameworks may replace. */
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_loaderNative(JNIEnv *env,jclass unused){
    (void)unused;
    if(!state.roots[LOGICAL])return NULL;
    return (*env)->GetObjectField(env,state.roots[LOGICAL],state.fields[4]);
}
/** The guest's LoadedApk: the object its real ContextImpl exposes as mPackageInfo. */
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_packageInfoNative(JNIEnv *env,jclass unused){
    (void)unused;
    if(!state.roots[LOGICAL])return NULL;
    return (*env)->NewLocalRef(env,state.roots[LOGICAL]);
}
/** The Application the guest currently publishes on its LoadedApk; a hot-fix proxy replaces it during onCreate. */
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_installedApplicationNative(JNIEnv *env,jclass unused){
    (void)unused;
    if(!state.roots[LOGICAL])return NULL;
    return (*env)->GetObjectField(env,state.roots[LOGICAL],state.fields[2]);
}
/** The guest may explicitly delegate its Application through getApplicationContext().
 * Only an Application already attached to this exact logical LoadedApk may become the component owner. */
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_publishApplicationNative(JNIEnv *env,jclass unused,jobject application){
    (void)unused;
    if(state.tid!=gettid()||!state.attaches||!physical(env,true)||(!guest_attached(env,application)&&!guest_delegate(env,application))){
        fail(env,"APPLICATION_PUBLICATION_OWNER_MISMATCH");return;
    }
    if(!tp_art_reflection_adopt(env,application,!guest_attached(env,application))){fail(env,"APPLICATION_PUBLICATION_VIEW_REJECTED");return;}
    (*env)->SetObjectField(env,state.roots[LOGICAL],state.fields[2],application);
    if((*env)->ExceptionCheck(env)||!physical(env,true)){fail(env,"APPLICATION_PUBLICATION_FAILED");return;}
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_closeViewNative(JNIEnv *env,jclass unused){
    (void)unused;
    if((state.tid&&state.tid!=gettid())||!tp_art_reflection_toggle(env,false))fail(env,"APPLICATION_VIEW_CLOSE_FAILED");
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_ApplicationAdapter_reportNative(JNIEnv *env,jclass unused){
    (void)unused;struct tp_art_reflection_report view;tp_art_reflection_report(&view);
    char text[1024];snprintf(text,sizeof(text),"{\"stage\":\"%s\",\"constructs\":%u,\"attaches\":%u,\"physicalChecks\":%u,"
        "\"retainedGlobalRefs\":%u,\"viewReady\":%s,\"viewEnabled\":%s,\"viewTransitions\":%u,\"viewRoots\":%u,"
        "\"mappedGets\":%llu,\"mappedSets\":%llu,\"denials\":%llu,\"metadataDecodes\":%llu}",state.stage,state.constructs,state.attaches,state.checks,state.retained,
        view.ready?"true":"false",view.enabled?"true":"false",view.transitions,view.retained_roots,
        (unsigned long long)view.mapped_gets,(unsigned long long)view.mapped_sets,(unsigned long long)view.denials,(unsigned long long)view.metadata_decodes);
    return (*env)->NewStringUTF(env,text);
}
