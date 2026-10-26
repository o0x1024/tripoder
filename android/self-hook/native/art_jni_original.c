#include "art_jni_original.h"
#include "art_jni_contract.h"
#include "art_framework_contract.h"
#include "abi/register_abi.h"
#include "abi/scalar_inbound.h"
#include "tripoder_hook_limits.h"
#include <android/set_abort_message.h>
#include <errno.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
struct original {struct tp_art_method_view *target,*backup;unsigned stack_size;bool critical,fast,reference;_Atomic bool ready;};
static struct original originals[TP_MANAGED_ENTRY_LIMIT];
static struct tp_art_access access;
static JavaVM *vm;static jclass helper;static jmethodID resolver;static void *linker;
static _Atomic bool bound;static bool attempted;
static void stop(const char *reason){android_set_abort_message(reason);abort();}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_NativeMethodOriginal_bindNative(JNIEnv *env,jclass cls,jobject reflected){
    TP_ART_MAPS_SCOPE;
    if(attempted)return JNI_FALSE;attempted=true;
    if(!tp_art_access_bind(&access))return JNI_FALSE;
#define CHECK(name,NAME) if(!tp_art_validate_code(&access,TP_JNI_##NAME##_RVA,tp_jni_##name##_bytes,sizeof(tp_jni_##name##_bytes)))return JNI_FALSE
    CHECK(registered,REGISTERED);CHECK(find_code,FIND_CODE);CHECK(register,REGISTER);
#undef CHECK
    uintptr_t runtime=*(uintptr_t *)(access.base+TP_FRAMEWORK_RUNTIME_RVA);
    if(!runtime||!tp_art_readable(runtime+0x270,8)||(linker=*(void **)(runtime+0x270))==NULL)return JNI_FALSE;
    struct tp_art_method_view method;
    if(!tp_art_method_snapshot(env,reflected,&method)||(method.flags&0x80108)!=0x80108)return JNI_FALSE;
    if((*env)->GetJavaVM(env,&vm)!=JNI_OK)return JNI_FALSE;
    helper=(*env)->NewGlobalRef(env,cls);resolver=(*env)->FromReflectedMethod(env,reflected);
    if(!helper||!resolver)return JNI_FALSE;
    atomic_store_explicit(&bound,true,memory_order_release);return JNI_TRUE;
}
JNIEXPORT jlong JNICALL Java_dev_tripoder_selfhook_NativeMethodOriginal_resolve(JNIEnv *env,jclass cls,jint index){
    (void)cls;if(!atomic_load_explicit(&bound,memory_order_acquire)||index<0||index>=TP_MANAGED_ENTRY_LIMIT||!atomic_load_explicit(&originals[index].ready,memory_order_acquire))stop("TP_JNI_ORIGINAL_UNKNOWN");
    struct original *entry=&originals[index];void *thread=access.current();
    if(!thread||*(JNIEnv **)((char *)thread+0xc8)!=env||(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)!=0)stop("TP_JNI_RESOLVER_STATE");
    void *(*registered)(void *,void *,void *)=(void *(*)(void *,void *,void *))(access.base+TP_JNI_REGISTERED_RVA);
    void *code=registered(linker,thread,entry->target);
    if(!code){
        void *(*find)(void *,void *,void *,bool)=(void *(*)(void *,void *,void *,bool))(access.base+TP_JNI_FIND_CODE_RVA);
        code=find(vm,entry->target,NULL,true);
        if((*env)->ExceptionCheck(env))return 0;
        if(!code){jclass error=(*env)->FindClass(env,"java/lang/UnsatisfiedLinkError");if(error)(*env)->ThrowNew(env,error,"Owned native method implementation is unresolved");return 0;}
        void *(*publish)(void *,void *,void *,void *)=(void *(*)(void *,void *,void *,void *))(access.base+TP_JNI_REGISTER_RVA);
        code=publish(linker,thread,entry->target,code);
    }
    return (*env)->ExceptionCheck(env)?0:(jlong)(uintptr_t)code;
}
static bool invoke(void *opaque,const struct tp_scalar_frame *frame,const uint64_t *stack,int incoming,struct tp_scalar_return *out,int *returned_errno){
    struct original *entry=opaque;*out=(struct tp_scalar_return){0};*returned_errno=incoming;
    if(!atomic_load_explicit(&entry->ready,memory_order_acquire))stop("TP_JNI_ORIGINAL_NOT_READY");
    JNIEnv *env=NULL;if((*vm)->GetEnv(vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK||!env)stop("TP_JNI_ORIGINAL_THREAD");
    void *thread=access.current();uintptr_t top=thread?*(uintptr_t *)((char *)thread+0xa8):0;
    bool runnable=thread&&(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)==0;
    if(!thread||*(JNIEnv **)((char *)thread+0xc8)!=env||!(top&1)||*(uintptr_t *)(top&~(uintptr_t)3)!=(uintptr_t)entry->backup
        ||runnable!=(entry->fast||entry->critical)||(!entry->critical&&(JNIEnv *)(uintptr_t)frame->gpr[0]!=env))stop("TP_JNI_ORIGINAL_FRAME");
    if((*env)->PushLocalFrame(env,16)<0)return true;
    jlong resolved=(*env)->CallStaticLongMethod(env,helper,resolver,(jint)(entry-originals));
    if(!(*env)->ExceptionCheck(env)){
        if(!resolved)stop("TP_JNI_ORIGINAL_NULL_IMPLEMENTATION");
        struct tp_register_arguments args={0};memcpy(args.gpr,frame->gpr,sizeof(args.gpr));memcpy(args.fpr,frame->fpr,sizeof(args.fpr));args.stack=stack;args.stack_size=entry->stack_size;
        errno=incoming;if(!tp_register_call((void (*)(void))(uintptr_t)resolved,&args,out))stop("TP_JNI_ORIGINAL_REGISTER_CALL");*returned_errno=errno;
    }
    jobject result=NULL;if(entry->reference&&!(*env)->ExceptionCheck(env))result=(jobject)(uintptr_t)out->gpr;
    result=(*env)->PopLocalFrame(env,result);if(entry->reference)out->gpr=(uintptr_t)result;
    return true;
}
uintptr_t tp_art_jni_original_prepare(JNIEnv *env,unsigned index,struct tp_art_method_view *target,struct tp_art_method_view *backup,jstring shorty){
    if(!atomic_load_explicit(&bound,memory_order_acquire)||index>=TP_MANAGED_ENTRY_LIMIT||!target||!backup||!shorty||!(target->flags&0x100))return 0;
    struct original *entry=&originals[index];if(atomic_load(&entry->ready))return 0;
    jsize length=(*env)->GetStringUTFLength(env,shorty);if(length<1||length>33)return 0;
    const char *types=(*env)->GetStringUTFChars(env,shorty,NULL);if(!types)return 0;
    bool critical=(target->flags&0x100000)!=0,valid=true;unsigned gpr=critical?0u:2u,fpr=0,spill=0;
    for(jsize i=0;i<length;i++){
        char type=types[i];if(!strchr(i?"ZBSCIJFDL":"VZBSCIJFDL",type)||(critical&&type=='L'))valid=false;
        if(!i)continue;if(type=='F'||type=='D'){if(fpr<8)fpr++;else spill++;}else{if(gpr<8)gpr++;else spill++;}
    }
    entry->reference=types[0]=='L';(*env)->ReleaseStringUTFChars(env,shorty,types);
    entry->stack_size=(spill*8+15)&~15u;if(!valid||entry->stack_size>TP_REGISTER_STACK_BYTES)return 0;
    entry->target=target;entry->backup=backup;entry->critical=critical;entry->fast=(target->flags&0x80000)!=0;
    struct tp_scalar_inbound inbound;if(tp_register_inbound_prepare(invoke,entry,&inbound)!=TP_SCALAR_OK)return 0;
    atomic_store_explicit(&entry->ready,true,memory_order_release);return (uintptr_t)inbound.entry;
}
