#include "tripoder_native_hook.h"
#include "tripoder_hook_limits.h"
#include "native_hook_internal.h"
#include <jni.h>
#include <limits.h>
#include <stdatomic.h>
#include <string.h>
#include <stdio.h>
struct context {JavaVM *vm;jobject receiver;jmethodID dispatch;struct tp_scalar_signature signature;};
static struct context contexts[TP_NATIVE_ENTRY_LIMIT];
static _Atomic unsigned used;
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhook_NativeHook_contract0(JNIEnv *env,jclass type){
    (void)type;jlong values[]={TP_SCALAR_VOID,TP_SCALAR_I32,TP_SCALAR_U32,TP_SCALAR_I64,TP_SCALAR_U64,TP_SCALAR_F32,TP_SCALAR_F64,TP_SCALAR_MAX_ARGS,TP_NATIVE_ENTRY_LIMIT};
    jlongArray result=(*env)->NewLongArray(env,9);if(result)(*env)->SetLongArrayRegion(env,result,0,9,values);return result;
}
static struct context *reserve(void){
    unsigned index=atomic_load_explicit(&used,memory_order_relaxed);
    do{if(index==TP_NATIVE_ENTRY_LIMIT)return NULL;}while(!atomic_compare_exchange_weak(&used,&index,index+1));return &contexts[index];
}
static void rejected(JNIEnv *env,const char *message){
    if((*env)->ExceptionCheck(env))return;jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if(type){(*env)->ThrowNew(env,type,message);(*env)->DeleteLocalRef(env,type);}
}
static void operation_failure(JNIEnv *env){
    struct tp_native_error error;tp_native_last_error(&error);char message[256];
    snprintf(message,sizeof(message),"NATIVE_%s:stage=%s:errno=%d",tp_native_status_name(error.status),error.stage,error.system_error);
    rejected(env,message);
}
static bool invoke(void *opaque,uint64_t token,const struct tp_scalar_value *values,unsigned count,int incoming,struct tp_scalar_result *out){
    struct context *context=opaque;JNIEnv *env=NULL;bool attached=false,pushed=false,ok=false;
    /* Java arithmetic uses its canonical IEEE environment. The outer Native
     * invocation saves caller state and propagates original fenv side effects;
     * original0 temporarily reinstates it, then restores this Java state. */
    __asm__ volatile("msr fpcr,xzr\n\tmsr fpsr,xzr\n\tisb":::"memory");
    jint status=(*context->vm)->GetEnv(context->vm,(void **)&env,JNI_VERSION_1_6);
    if(status==JNI_EDETACHED){
        if((*context->vm)->AttachCurrentThread(context->vm,&env,NULL)!=JNI_OK)return false;attached=true;
    }else if(status!=JNI_OK)return false;
    if((*env)->ExceptionCheck(env))goto done;
    if((*env)->PushLocalFrame(env,16)<0)goto done;pushed=true;
    jlong raw[TP_SCALAR_MAX_ARGS];for(unsigned i=0;i<count;i++)memcpy(&raw[i],&values[i].bits,8);
    jlongArray arguments=(*env)->NewLongArray(env,(jsize)count);if(!arguments)goto done;
    (*env)->SetLongArrayRegion(env,arguments,0,(jsize)count,raw);if((*env)->ExceptionCheck(env))goto done;
    jvalue parameters[3]={{.j=(jlong)token},{.l=arguments},{.i=incoming}};
    jlongArray result=(*env)->CallObjectMethodA(env,context->receiver,context->dispatch,parameters);
    if((*env)->ExceptionCheck(env)||!result||(*env)->GetArrayLength(env,result)!=2)goto done;
    jlong returned[2];(*env)->GetLongArrayRegion(env,result,0,2,returned);if((*env)->ExceptionCheck(env)||returned[1]<INT_MIN||returned[1]>INT_MAX)goto done;
    out->value.type=context->signature.result;memcpy(&out->value.bits,&returned[0],8);out->error_number=(int)returned[1];ok=true;
done:
    /* A C export cannot propagate a Java Throwable through its ABI. Return
     * failure to the outer native boundary, which exits without an ACK. Clear
     * only for JNI cleanup/detach; no script/original continuation follows. */
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);ok=false;}
    if(pushed)(*env)->PopLocalFrame(env,NULL);
    if(attached&&(*context->vm)->DetachCurrentThread(context->vm)!=JNI_OK)tp_native_stop(224);
    return ok;
}
static bool signature(JNIEnv *env,jint result,jintArray arguments,struct tp_scalar_signature *out){
    if(!arguments)return false;jsize count=(*env)->GetArrayLength(env,arguments);if(count<0||count>(jsize)TP_SCALAR_MAX_ARGS)return false;
    jint types[TP_SCALAR_MAX_ARGS];(*env)->GetIntArrayRegion(env,arguments,0,count,types);if((*env)->ExceptionCheck(env))return false;
    *out=(struct tp_scalar_signature){.count=(unsigned)count,.result=(enum tp_scalar_type)result};
    for(jsize i=0;i<count;i++)out->arguments[i]=(enum tp_scalar_type)types[i];struct tp_scalar_layout layout;
    return tp_scalar_plan(out,&layout)==TP_SCALAR_OK;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_NativeHook_prepare0(JNIEnv *env,jclass type,jint module,jlong generation,jlong rva,jbyteArray bytes,jint result,jintArray arguments,jobject receiver){
    (void)type;if(module<0||generation<=0||(uint64_t)generation>UINT32_MAX||rva<0||!bytes||!receiver)return -1;
    jsize length=(*env)->GetArrayLength(env,bytes);if(length<=0||length>=1024)return -1;
    char path[1024];(*env)->GetByteArrayRegion(env,bytes,0,length,(jbyte *)path);if((*env)->ExceptionCheck(env)||memchr(path,0,(size_t)length))return -1;path[length]=0;
    struct tp_scalar_signature plan;if(!signature(env,result,arguments,&plan))return -1;
    jclass owner=(*env)->GetObjectClass(env,receiver);if(!owner)return -1;
    jmethodID dispatch=(*env)->GetMethodID(env,owner,"dispatchNative","(J[JI)[J");(*env)->DeleteLocalRef(env,owner);if(!dispatch)return -1;
    struct context *context=reserve();if(!context)return -1;
    if((*env)->GetJavaVM(env,&context->vm)!=JNI_OK)return -1;context->dispatch=dispatch;context->signature=plan;
    context->receiver=(*env)->NewGlobalRef(env,receiver);if(!context->receiver)return -1;
    unsigned handle;
    if(!tp_native_prepare((unsigned)module,(uint32_t)generation,(uintptr_t)rva,path,&plan,invoke,context,&handle)){
        (*env)->DeleteGlobalRef(env,context->receiver);context->receiver=NULL;operation_failure(env);return -1;
    }
    return (jint)handle;
}
static int transition(JNIEnv *env,jintArray ids,bool enable){
    if(!ids){rejected(env,"NATIVE_BATCH_INPUT");return -1;}jsize count=(*env)->GetArrayLength(env,ids);if(count<=0||count>TP_NATIVE_ENTRY_LIMIT){rejected(env,"NATIVE_BATCH_INPUT");return -1;}
    jint raw[TP_NATIVE_ENTRY_LIMIT];unsigned handles[TP_NATIVE_ENTRY_LIMIT];
    (*env)->GetIntArrayRegion(env,ids,0,count,raw);if((*env)->ExceptionCheck(env))return -1;
    for(jsize i=0;i<count;i++){if(raw[i]<0){rejected(env,"NATIVE_HANDLE_INPUT");return -1;}handles[i]=(unsigned)raw[i];}
    struct tp_native_report before;bool cancel=!enable&&tp_native_report(handles[0],&before)&&before.phase==TP_NATIVE_PREPARED;
    if(enable?tp_native_publish(handles,(unsigned)count):tp_native_retire(handles,(unsigned)count)){
        if(cancel)for(jsize i=0;i<count;i++){
            struct context *context=tp_native_bindings[handles[i]].context;
            if(context->receiver){(*env)->DeleteGlobalRef(env,context->receiver);context->receiver=NULL;}
        }
        return 0;
    }
    struct tp_native_error error;tp_native_last_error(&error);
    if(error.status==TP_NATIVE_BUSY)return 1;
    operation_failure(env);return -1;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_NativeHook_publish0(JNIEnv *env,jclass type,jintArray ids){(void)type;return transition(env,ids,true);}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_NativeHook_retire0(JNIEnv *env,jclass type,jintArray ids){(void)type;return transition(env,ids,false);}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhook_NativeHook_original0(JNIEnv *env,jclass type,jlong token,jintArray types,jlongArray raw,jint incoming){
    (void)type;struct tp_scalar_result result={0};struct tp_scalar_signature plan;bool parsed=signature(env,TP_SCALAR_VOID,types,&plan);
    jlong bits[TP_SCALAR_MAX_ARGS];struct tp_scalar_value values[TP_SCALAR_MAX_ARGS];
    if(!raw||!parsed||(*env)->GetArrayLength(env,raw)!=(jsize)plan.count)parsed=false;
    if(parsed){
        (*env)->GetLongArrayRegion(env,raw,0,(jsize)plan.count,bits);if((*env)->ExceptionCheck(env))parsed=false;
        else for(unsigned i=0;i<plan.count;i++){values[i].type=plan.arguments[i];memcpy(&values[i].bits,&bits[i],8);}
    }
    bool ok=tp_native_original((uint64_t)token,parsed?values:NULL,parsed?plan.count:UINT_MAX,incoming,&result);
    if(!ok){rejected(env,"NATIVE_ORIGINAL_TOKEN_OR_VALUES_REJECTED");return NULL;}
    jlong returned[2];memcpy(&returned[0],&result.value.bits,8);returned[1]=result.error_number;
    jlongArray output=(*env)->NewLongArray(env,2);if(output)(*env)->SetLongArrayRegion(env,output,0,2,returned);return output;
}
