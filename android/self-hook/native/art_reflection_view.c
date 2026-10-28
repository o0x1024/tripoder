#define _GNU_SOURCE
#include "art_reflection_view.h"
#include "art_access.h"
#include <android/set_abort_message.h>
#include <dlfcn.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

typedef jobject (*get_function)(JNIEnv *,jobject,jobject);
typedef void (*set_function)(JNIEnv *,jobject,jobject,jobject);
enum root_index { FIELD, OWNER, PHYSICAL, LOGICAL, VALUE_TYPE, SECURITY, FIELD_TYPE, GET_METHOD, SET_METHOD, ROOT_COUNT };
static struct {
    _Atomic bool attempted, busy, ready, enabled, published;
    _Atomic unsigned transitions;
    _Atomic uint64_t gets, sets, mapped_gets, mapped_sets, denials, original_exceptions, metadata_decodes;
    jobject roots[ROOT_COUNT];
    _Atomic(jobject) logical;
    jfieldID field_id;
    jfieldID application_apk;
    jfieldID wrapper_base;
    get_function get;
    set_function set;
    uint32_t flags[2];
    uintptr_t quick[2];
} view;

static void fatal(const char *reason) { android_set_abort_message(reason); abort(); }
static int matches(JNIEnv *env,jobject field,jobject owner) {
    // The view owns one ActivityThread instance. Unrelated reflection (including
    // VM-generated opaque Field objects) must retain ART's own decoding path.
    if(!(*env)->IsSameObject(env,owner,view.roots[OWNER]))return 0;
    atomic_fetch_add_explicit(&view.metadata_decodes,1,memory_order_relaxed);
    jfieldID id=(*env)->FromReflectedField(env,field);
    if ((*env)->ExceptionCheck(env)) return -1;
    if (!id) fatal("TP_REFLECTION_FIELD_ID_MISSING");
    return id==view.field_id;
}
static void deny(JNIEnv *env) {
    atomic_fetch_add(&view.denials,1);
    (*env)->ThrowNew(env,(jclass)view.roots[SECURITY],"INITIAL_APPLICATION_IDENTITY_IMMUTABLE");
}
/**
 * True when candidate is an Application the guest attached to the same LoadedApk as the currently published one,
 * or wrapped around the same base Context root (shell/hot-fix delegate built via attachBaseContext).
 *
 * <p>Android does not require a process application to stay the object that was first attached: hot-fix proxies
 * and packer shells construct a delegate, attach it to their own LoadedApk or base Context, and then publish it
 * on {@code ActivityThread}. The immutable fact is the LoadedApk / base Context root, not the first Application
 * instance, so the view follows exactly that structure.
 */
static jobject base_root(JNIEnv *env,jobject app) {
    if (!app||!view.wrapper_base) return NULL;
    jobject cursor=(*env)->GetObjectField(env,app,view.wrapper_base);
    if (!cursor) return NULL;
    for (unsigned depth=0;depth<16&&(*env)->IsInstanceOf(env,cursor,(jclass)view.roots[VALUE_TYPE]);depth++) {
        jobject next=(*env)->GetObjectField(env,cursor,view.wrapper_base);
        if (!next) break;
        cursor=next;
    }
    return cursor;
}
static bool guest_attached(JNIEnv *env,jobject candidate,jobject current) {
    if (!candidate||!current||!view.application_apk) return false;
    if ((*env)->IsSameObject(env,candidate,view.roots[PHYSICAL])) return false;
    jobject candidate_apk=(*env)->GetObjectField(env,candidate,view.application_apk);
    jobject current_apk=(*env)->GetObjectField(env,current,view.application_apk);
    if (candidate_apk&&current_apk&&(*env)->IsSameObject(env,candidate_apk,current_apk)) return true;
    jobject candidate_base=base_root(env,candidate);
    jobject current_base=base_root(env,current);
    return candidate_base&&current_base&&(*env)->IsSameObject(env,candidate_base,current_base);
}
/** Publish a guest-owned delegate as the logical application, retaining every generation that was ever published. */
static bool publish(JNIEnv *env,jobject candidate,bool derived) {
    jobject root=(*env)->NewGlobalRef(env,candidate);
    if (!root) return false;
    for (unsigned attempt=0;attempt<4;attempt++) {
        jobject current=atomic_load_explicit(&view.logical,memory_order_acquire);
        if ((*env)->IsSameObject(env,candidate,current)) { (*env)->DeleteGlobalRef(env,root); return true; }
        if (!derived&&!guest_attached(env,candidate,current)) { (*env)->DeleteGlobalRef(env,root); return false; }
        // Old generations stay retained: a concurrent reader may still hold the value it loaded.
        if (atomic_compare_exchange_strong_explicit(&view.logical,&current,root,memory_order_release,memory_order_acquire))
            return true;
    }
    (*env)->DeleteGlobalRef(env,root);
    return false;
}
static jobject get(JNIEnv *env,jobject field,jobject owner) {
    if (!atomic_load_explicit(&view.ready,memory_order_acquire)) fatal("TP_REFLECTION_CALLBACK_NOT_READY");
    bool enabled=atomic_load_explicit(&view.enabled,memory_order_acquire);
    atomic_fetch_add(&view.gets,1);
    jobject result=view.get(env,field,owner);
    if ((*env)->ExceptionCheck(env)) { atomic_fetch_add(&view.original_exceptions,1); return result; }
    if (!enabled) return result;
    int selected=matches(env,field,owner);
    if (selected<0) return NULL;
    if (!selected) return result;
    atomic_fetch_add(&view.mapped_gets,1);
    if (!(*env)->IsSameObject(env,result,view.roots[PHYSICAL])) { deny(env); return NULL; }
    return atomic_load_explicit(&view.published,memory_order_acquire)?
        (*env)->NewLocalRef(env,atomic_load_explicit(&view.logical,memory_order_acquire)):NULL;
}
static void set(JNIEnv *env,jobject field,jobject owner,jobject value) {
    if (!atomic_load_explicit(&view.ready,memory_order_acquire)) fatal("TP_REFLECTION_CALLBACK_NOT_READY");
    bool enabled=atomic_load_explicit(&view.enabled,memory_order_acquire);
    atomic_fetch_add(&view.sets,1);
    int selected=enabled?matches(env,field,owner):0;
    if (selected<0) return;
    /* Preserve the real ART type-error/access-check ordering for invalid types.
     * Only a type-correct mapped write substitutes the immutable physical value. */
    if (selected&&value&&!(*env)->IsInstanceOf(env,value,(jclass)view.roots[VALUE_TYPE])) selected=0;
    view.set(env,field,owner,selected?view.roots[PHYSICAL]:value);
    if ((*env)->ExceptionCheck(env)) { atomic_fetch_add(&view.original_exceptions,1); return; }
    if (selected) {
        atomic_fetch_add(&view.mapped_sets,1);
        if (!publish(env,value,false)) deny(env);
    }
}

static bool original(JNIEnv *env,jclass field_type,const char *name,const char *signature,
                      jobject *method,struct tp_art_method_view *snapshot) {
    jmethodID id=(*env)->GetMethodID(env,field_type,name,signature);
    if (!id) return false;
    *method=(*env)->ToReflectedMethod(env,field_type,id,JNI_FALSE);
    Dl_info location;
    return *method&&tp_art_method_snapshot(env,*method,snapshot)&&(snapshot->flags&0x108)==0x100&&
        snapshot->data&&dladdr((void *)snapshot->data,&location)&&
        !strcmp(location.dli_fname,"/apex/com.android.art/lib64/libart.so");
}
static bool prepare(JNIEnv *env,jobject field,jobject owner,jobject physical,jobject logical,jclass value_type,bool pending) {
    bool expected=false;
    if (!atomic_compare_exchange_strong(&view.attempted,&expected,true)||!field||!owner||!physical||(!logical&&!pending)||!value_type||
        (*env)->ExceptionCheck(env)) return false;
    if ((*env)->PushLocalFrame(env,32)<0) return false;
    bool success=false;
    jclass field_type=(*env)->FindClass(env,"java/lang/reflect/Field");
    jclass security=(*env)->FindClass(env,"java/lang/SecurityException");
    if (!field_type||!security||!(*env)->IsInstanceOf(env,field,field_type)||
        !(*env)->IsInstanceOf(env,physical,value_type)||(logical&&!(*env)->IsInstanceOf(env,logical,value_type))||
        (*env)->IsSameObject(env,physical,logical)) goto done;
    jmethodID modifiers=(*env)->GetMethodID(env,field_type,"getModifiers","()I");
    jmethodID type=(*env)->GetMethodID(env,field_type,"getType","()Ljava/lang/Class;");
    jmethodID declaring=(*env)->GetMethodID(env,field_type,"getDeclaringClass","()Ljava/lang/Class;");
    if (!modifiers||!type||!declaring) goto done;
    view.application_apk=(*env)->GetFieldID(env,(jclass)value_type,"mLoadedApk","Landroid/app/LoadedApk;");
    if (!view.application_apk||(*env)->ExceptionCheck(env)) goto done;
    view.wrapper_base=(*env)->GetFieldID(env,(jclass)value_type,"mBase","Landroid/content/Context;");
    if (!view.wrapper_base||(*env)->ExceptionCheck(env)) goto done;
    jint flags=(*env)->CallIntMethod(env,field,modifiers);
    jobject declared_type=(*env)->CallObjectMethod(env,field,type);
    jobject declared_owner=(*env)->CallObjectMethod(env,field,declaring);
    if ((*env)->ExceptionCheck(env)||(flags&(8|16))||!declared_type||!declared_owner||
        !(*env)->IsSameObject(env,declared_type,value_type)||!(*env)->IsInstanceOf(env,owner,(jclass)declared_owner)) goto done;
    jfieldID id=(*env)->FromReflectedField(env,field);
    if (!id||(*env)->ExceptionCheck(env)) goto done;
    jobject actual=(*env)->GetObjectField(env,owner,id);
    if ((*env)->ExceptionCheck(env)||!(*env)->IsSameObject(env,actual,physical)) goto done;
    jobject get_method=NULL,set_method=NULL;
    struct tp_art_method_view get_state,set_state;
    if (!original(env,field_type,"get","(Ljava/lang/Object;)Ljava/lang/Object;",&get_method,&get_state)||
        !original(env,field_type,"set","(Ljava/lang/Object;Ljava/lang/Object;)V",&set_method,&set_state)||
        get_state.data==set_state.data) goto done;
    jobject refs[ROOT_COUNT]={field,owner,physical,logical,value_type,security,field_type,get_method,set_method};
    for (unsigned i=0;i<ROOT_COUNT;i++) {
        if(i==LOGICAL&&!refs[i])continue;
        view.roots[i]=(*env)->NewGlobalRef(env,refs[i]);
        if (!view.roots[i]) goto done;
    }
    view.field_id=id; view.get=(get_function)get_state.data; view.set=(set_function)set_state.data;
    view.flags[0]=get_state.flags;view.flags[1]=set_state.flags;
    view.quick[0]=get_state.quick;view.quick[1]=set_state.quick;
    atomic_store_explicit(&view.logical,view.roots[LOGICAL],memory_order_release);
    atomic_store_explicit(&view.published,!pending,memory_order_release);
    atomic_store_explicit(&view.ready,true,memory_order_release);
    success=true;
done:
    if (!success) for (unsigned i=0;i<ROOT_COUNT;i++) if (view.roots[i]) {
        (*env)->DeleteGlobalRef(env,view.roots[i]);view.roots[i]=NULL;
    }
    (*env)->PopLocalFrame(env,NULL);
    return success;
}
bool tp_art_reflection_prepare(JNIEnv *env,jobject field,jobject owner,jobject physical,jobject logical,jclass value_type){
    return prepare(env,field,owner,physical,logical,value_type,false);
}
bool tp_art_reflection_prepare_pending(JNIEnv *env,jobject field,jobject owner,jobject physical,jclass value_type){
    return prepare(env,field,owner,physical,NULL,value_type,true);
}
bool tp_art_reflection_promote(JNIEnv *env,jobject logical){
    if(!atomic_load_explicit(&view.ready,memory_order_acquire)||!atomic_load_explicit(&view.enabled,memory_order_acquire)
        ||!logical||(*env)->ExceptionCheck(env)||!(*env)->IsInstanceOf(env,logical,(jclass)view.roots[VALUE_TYPE])
        ||(*env)->IsSameObject(env,logical,view.roots[PHYSICAL]))return false;
    jobject root=(*env)->NewGlobalRef(env,logical);if(!root)return false;
    jobject expected=NULL;
    if(!atomic_compare_exchange_strong_explicit(&view.logical,&expected,root,memory_order_release,memory_order_relaxed)){
        (*env)->DeleteGlobalRef(env,root);return false;
    }
    // The promoted global is retained by the atomic owner until process exit.
    return true;
}
bool tp_art_reflection_adopt(JNIEnv *env,jobject application,bool derived){
    if((*env)->ExceptionCheck(env)||!application||!atomic_load_explicit(&view.ready,memory_order_acquire)
       ||!atomic_load_explicit(&view.enabled,memory_order_acquire)||!atomic_load_explicit(&view.published,memory_order_acquire))return false;
    return publish(env,application,derived);
}
bool tp_art_reflection_complete(JNIEnv *env){
    if((*env)->ExceptionCheck(env)||!atomic_load_explicit(&view.logical,memory_order_acquire))return false;
    atomic_store_explicit(&view.published,true,memory_order_release);return true;
}
jobject tp_art_reflection_value(JNIEnv *env){
    if(!atomic_load_explicit(&view.ready,memory_order_acquire))return NULL;
    jobject value=atomic_load_explicit(&view.enabled,memory_order_acquire)?
        (atomic_load_explicit(&view.published,memory_order_acquire)?atomic_load_explicit(&view.logical,memory_order_acquire):NULL):view.roots[PHYSICAL];
    return (*env)->NewLocalRef(env,value);
}

bool tp_art_reflection_toggle(JNIEnv *env,bool enabled) {
    bool expected=false;
    if (!atomic_compare_exchange_strong(&view.busy,&expected,true)) return false;
    bool success=false;
    if (!atomic_load_explicit(&view.ready,memory_order_acquire)||(*env)->ExceptionCheck(env)||
        atomic_load(&view.transitions)>=128||atomic_load(&view.enabled)==enabled) goto done;
    struct tp_art_method_view before[2];
    uintptr_t expected_data[2]={atomic_load(&view.enabled)?(uintptr_t)get:(uintptr_t)view.get,
                               atomic_load(&view.enabled)?(uintptr_t)set:(uintptr_t)view.set};
    for (unsigned i=0;i<2;i++) if (!tp_art_method_snapshot(env,view.roots[GET_METHOD+i],&before[i])||
        before[i].data!=expected_data[i]||before[i].flags!=view.flags[i]||before[i].quick!=view.quick[i]) goto done;
    if (!enabled) atomic_store_explicit(&view.enabled,false,memory_order_release);
    JNINativeMethod bindings[2]={{"get","(Ljava/lang/Object;)Ljava/lang/Object;",enabled?(void *)get:(void *)view.get},
        {"set","(Ljava/lang/Object;Ljava/lang/Object;)V",enabled?(void *)set:(void *)view.set}};
    if ((*env)->RegisterNatives(env,(jclass)view.roots[FIELD_TYPE],bindings,2)!=JNI_OK) fatal("TP_REFLECTION_REGISTER_FAILED");
    for (unsigned i=0;i<2;i++) {
        struct tp_art_method_view after;
        if (!tp_art_method_snapshot(env,view.roots[GET_METHOD+i],&after)||after.data!=(uintptr_t)bindings[i].fnPtr||
            after.flags!=view.flags[i]||after.quick!=view.quick[i]) fatal("TP_REFLECTION_REGISTER_STATE_MISMATCH");
    }
    if (enabled) atomic_store_explicit(&view.enabled,true,memory_order_release);
    atomic_fetch_add(&view.transitions,1);
    success=true;
done:
    atomic_store(&view.busy,false);
    return success;
}
void tp_art_reflection_report(struct tp_art_reflection_report *r) {
    if (!r) return;
    *r=(struct tp_art_reflection_report){.transitions=atomic_load(&view.transitions),
        .retained_roots=atomic_load(&view.ready)?ROOT_COUNT-1+(atomic_load(&view.logical)!=NULL):0,.ready=atomic_load(&view.ready),.enabled=atomic_load(&view.enabled),
        .gets=atomic_load(&view.gets),.sets=atomic_load(&view.sets),.mapped_gets=atomic_load(&view.mapped_gets),
        .mapped_sets=atomic_load(&view.mapped_sets),.denials=atomic_load(&view.denials),
        .original_exceptions=atomic_load(&view.original_exceptions),.metadata_decodes=atomic_load(&view.metadata_decodes)};
}
