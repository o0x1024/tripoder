#include <jni.h>
#include <stdio.h>
#include <string.h>

static unsigned classes, members;
static unsigned denied;
static char failures[2048];
static jobjectArray fixed_fields,fixed_methods;
static unsigned field_slot,method_slot,calibrated;
static const char *stage="NOT_STARTED";
static jclass type(JNIEnv *env,const char *name) {
    stage=name;
    jclass result=(*env)->FindClass(env,name);
    if(result)classes++;
    return result;
}
static jfieldID field(JNIEnv *env,jclass owner,const char *name,const char *signature) {
    stage=name;
    jfieldID id;
    if(fixed_fields){
        jobject reflected=(*env)->GetObjectArrayElement(env,fixed_fields,(jsize)field_slot++);
        id=reflected?(*env)->FromReflectedField(env,reflected):NULL;
        if(reflected)(*env)->DeleteLocalRef(env,reflected);
        if(id&&strcmp(name,"mDefaultClassLoader")&&strcmp(name,"mAppComponentFactory")){
            jfieldID reference=(*env)->GetFieldID(env,owner,name,signature);
            if(reference!=id)id=NULL;else calibrated++;
        }
    }else id=(*env)->GetFieldID(env,owner,name,signature);
    if(id)members++;
    else { denied++; snprintf(failures+strlen(failures),sizeof(failures)-strlen(failures),"%s%s",failures[0]?",":"",name); (*env)->ExceptionClear(env); }
    return id;
}
static jmethodID method(JNIEnv *env,jclass owner,const char *name,const char *signature,int is_static) {
    stage=name;
    jmethodID id;
    if(fixed_methods){
        jobject reflected=(*env)->GetObjectArrayElement(env,fixed_methods,(jsize)method_slot++);
        id=reflected?(*env)->FromReflectedMethod(env,reflected):NULL;
        if(reflected)(*env)->DeleteLocalRef(env,reflected);
        if(id&&strcmp(name,"<init>")&&strcmp(name,"createAppContext")){
            jmethodID reference=is_static?(*env)->GetStaticMethodID(env,owner,name,signature):(*env)->GetMethodID(env,owner,name,signature);
            if(reference!=id)id=NULL;else calibrated++;
        }
    }else id=is_static?(*env)->GetStaticMethodID(env,owner,name,signature):(*env)->GetMethodID(env,owner,name,signature);
    if(id)members++;
    else { denied++; snprintf(failures+strlen(failures),sizeof(failures)-strlen(failures),"%s%s",failures[0]?",":"",name); (*env)->ExceptionClear(env); }
    return id;
}
static jfieldID current_thread_field(JNIEnv *env,jclass owner){
    stage="sCurrentActivityThread";
    jfieldID id=NULL;
    if(fixed_fields){
        jobject reflected=(*env)->GetObjectArrayElement(env,fixed_fields,(jsize)field_slot++);
        id=reflected?(*env)->FromReflectedField(env,reflected):NULL;
        if(reflected)(*env)->DeleteLocalRef(env,reflected);
        jfieldID ordinary=(*env)->GetStaticFieldID(env,owner,"sCurrentActivityThread","Landroid/app/ActivityThread;");
        if(id&&id==ordinary)calibrated++;else id=NULL;
    }else id=(*env)->GetStaticFieldID(env,owner,"sCurrentActivityThread","Landroid/app/ActivityThread;");
    if(id)members++;
    else {denied++;snprintf(failures+strlen(failures),sizeof(failures)-strlen(failures),"%ssCurrentActivityThread",failures[0]?",":"");(*env)->ExceptionClear(env);}
    return id;
}
static jstring inspect(JNIEnv *env,jobject host) {
    if(classes||members)return NULL;
    if((*env)->PushLocalFrame(env,64)<0)return NULL;
    jstring result=NULL;
    jclass application=type(env,"android/app/Application");if(!application)goto done;
    jclass thread_type=type(env,"android/app/ActivityThread");if(!thread_type)goto done;
    jclass loaded=type(env,"android/app/LoadedApk");if(!loaded)goto done;
    jclass context=type(env,"android/app/ContextImpl");if(!context)goto done;
    jclass app_info=type(env,"android/content/pm/ApplicationInfo");if(!app_info)goto done;
    jclass work=type(env,"android/app/QueuedWork");if(!work)goto done;
    jclass wrapper=type(env,"android/content/ContextWrapper");if(!wrapper)goto done;
    jmethodID current_thread=method(env,thread_type,"currentActivityThread","()Landroid/app/ActivityThread;",1);
    jmethodID current_app=method(env,thread_type,"currentApplication","()Landroid/app/Application;",1);
    jfieldID initial=field(env,thread_type,"mInitialApplication","Landroid/app/Application;");
    jfieldID app_apk=field(env,application,"mLoadedApk","Landroid/app/LoadedApk;");
    jfieldID apk_app=field(env,loaded,"mApplication","Landroid/app/Application;");
    jfieldID instrumentation=field(env,thread_type,"mInstrumentation","Landroid/app/Instrumentation;");
    const char *names[]={"mClassLoader","mDefaultClassLoader","mResources","mAppComponentFactory","mApplicationInfo"};
    const char *signatures[]={"Ljava/lang/ClassLoader;","Ljava/lang/ClassLoader;","Landroid/content/res/Resources;",
        "Landroid/app/AppComponentFactory;","Landroid/content/pm/ApplicationInfo;"};
    for(unsigned i=0;i<5;i++)field(env,loaded,names[i],signatures[i]);
    field(env,app_info,"credentialProtectedDataDir","Ljava/lang/String;");
    jfieldID base=field(env,wrapper,"mBase","Landroid/content/Context;");
    jfieldID fixed_thread=current_thread_field(env,thread_type);
    method(env,loaded,"<init>","(Landroid/app/ActivityThread;Landroid/content/pm/ApplicationInfo;Landroid/content/res/CompatibilityInfo;Ljava/lang/ClassLoader;ZZZ)V",0);
    method(env,loaded,"getCompatibilityInfo","()Landroid/content/res/CompatibilityInfo;",0);
    method(env,context,"createAppContext","(Landroid/app/ActivityThread;Landroid/app/LoadedApk;Ljava/lang/String;)Landroid/app/ContextImpl;",1);
    method(env,context,"setOuterContext","(Landroid/content/Context;)V",0);
    method(env,application,"attach","(Landroid/content/Context;)V",0);
    method(env,work,"addFinisher","(Ljava/lang/Runnable;)V",1);
    method(env,work,"removeFinisher","(Ljava/lang/Runnable;)V",1);
    if(denied) {
        char text[2560];snprintf(text,sizeof(text),"{\"passed\":false,\"classes\":%u,\"members\":%u,\"denied\":%u,\"failures\":\"%s\",\"frameworkFieldsWritten\":false,\"methodBindingsChanged\":false,\"globalRefsRetained\":0,\"hiddenApiExemptionsSet\":false}",classes,members,denied,failures);
        stage="LOOKUP_DENIED";result=(*env)->NewStringUTF(env,text);goto done;
    }
    stage="PHYSICAL_OWNERSHIP";
    jobject thread=(*env)->CallStaticObjectMethod(env,thread_type,current_thread);
    jobject current=(*env)->CallStaticObjectMethod(env,thread_type,current_app);
    jobject physical_thread=(*env)->GetStaticObjectField(env,thread_type,fixed_thread);
    if((*env)->ExceptionCheck(env)||!thread||!current||!host)goto done;
    jobject initial_app=(*env)->GetObjectField(env,thread,initial);
    jobject apk=(*env)->GetObjectField(env,host,app_apk);
    jobject app_base=(*env)->GetObjectField(env,host,base);
    jobject instance=(*env)->GetObjectField(env,thread,instrumentation);
    if((*env)->ExceptionCheck(env)||!apk||!app_base||!instance)goto done;
    jobject physical=(*env)->GetObjectField(env,apk,apk_app);
    int owns=(*env)->IsSameObject(env,thread,physical_thread)&&(*env)->IsSameObject(env,current,host)&&(*env)->IsSameObject(env,initial_app,host)&&
        (*env)->IsSameObject(env,physical,host)&&(*env)->IsInstanceOf(env,app_base,context);
    char text[768];
    snprintf(text,sizeof(text),"{\"passed\":%s,\"classes\":%u,\"members\":%u,\"physicalOwnership\":%s,"
        "\"frameworkFieldsWritten\":false,\"methodBindingsChanged\":false,\"globalRefsRetained\":%u,\"hiddenApiExemptionsSet\":false,"
        "\"accessKind\":\"%s\",\"ordinaryJniCalibrations\":%u}",
        owns?"true":"false",classes,members,owns?"true":"false",fixed_fields?7:0,
        fixed_fields?"fixed-art-registry":"ordinary-jni",calibrated);
    stage=owns?"COMPLETE":"OWNERSHIP_MISMATCH";
    result=(*env)->NewStringUTF(env,text);
done:
    return (*env)->PopLocalFrame(env,result);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_FrameworkProbeActivity_inspect(JNIEnv *env,jclass unused,jobject host) {
    (void)unused;return inspect(env,host);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_FrameworkProbeActivity_inspectFixed(JNIEnv *env,jclass unused,
    jobject host,jobjectArray fields_arg,jobjectArray methods_arg) {
    (void)unused;
    if(!fields_arg||!methods_arg||(*env)->GetArrayLength(env,fields_arg)!=12||(*env)->GetArrayLength(env,methods_arg)!=9)return NULL;
    fixed_fields=fields_arg;fixed_methods=methods_arg;
    jstring result=inspect(env,host);
    fixed_fields=NULL;fixed_methods=NULL;
    return result;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_FrameworkProbeActivity_status(JNIEnv *env,jclass unused) {
    (void)unused;
    char text[256];snprintf(text,sizeof(text),"{\"stage\":\"%s\",\"classes\":%u,\"members\":%u}",stage,classes,members);
    return (*env)->NewStringUTF(env,text);
}
