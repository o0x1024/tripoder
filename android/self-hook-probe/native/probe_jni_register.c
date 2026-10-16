#include "abi/register_abi.h"
#include "abi/scalar_inbound.h"
#include "art_access.h"
#include "art_framework_contract.h"
#include "coverage/owned_entry_registry.h"
#include "coverage/art_entry_writer.h"
#include "tripoder_a64.h"
#include <sys/mman.h>
#include <errno.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

#define LONGS jlong a,jlong b,jlong c,jlong d,jlong e,jlong f,jlong g,jlong h,jlong i,jlong j,jlong k,jlong l,jlong m,jlong n,jlong o,jlong p
#define VALUES {(uint64_t)a,(uint64_t)b,(uint64_t)c,(uint64_t)d,(uint64_t)e,(uint64_t)f,(uint64_t)g,(uint64_t)h,(uint64_t)i,(uint64_t)j,(uint64_t)k,(uint64_t)l,(uint64_t)m,(uint64_t)n,(uint64_t)o,(uint64_t)p}
static const uint64_t expected[16]={UINT64_C(0x8000000000000000),UINT64_MAX,0,1,UINT64_C(0x20000000000001),UINT64_C(0xfedcba9876543210),7,11,13,17,19,23,29,31,37,UINT64_C(0x7fffffffffffffff)};
struct context {unsigned mode;JavaVM *vm;jclass owner,helper;jobject receiver;void *(*current)(void);uintptr_t method;void (*original)(void);jmethodID callback;_Atomic unsigned calls,originals,pushes,pops,frames,errors;_Atomic(jweak) weak;};
static struct context contexts[4];static unsigned checks,failures;static const char *first;
static struct tp_art_access probe_access;static void *class_linker;
JNIEXPORT jlong JNICALL Java_dev_tripoder_selfhookprobe_JniRawProbe_registered(JNIEnv *env,jclass cls,jint index){
    (void)env;(void)cls;if(index<0||index>3||!contexts[index].method||!class_linker)return 0;
    void *thread=probe_access.current();if(!thread||(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)!=0)return 0;
    void *(*registered)(void *,void *,void *)=(void *(*)(void *,void *,void *))(probe_access.base+0x4531d4);
    return (jlong)(uintptr_t)registered(class_linker,thread,(void *)contexts[index].method);
}
static void check(bool ok,const char *name){checks++;if(!ok){failures++;if(!first)first=name;}}
static uint64_t hash(const uint64_t *values){uint64_t value=0;for(unsigned i=0;i<16;i++)value=((value<<3)|(value>>61))^values[i];return value;}
static jlong original_common(unsigned mode,JNIEnv *env,jclass owner,const uint64_t *values){
    struct context *ctx=&contexts[mode];atomic_fetch_add(&ctx->originals,1);
    if(mode!=2&&(!env||!(*env)->IsSameObject(env,owner,ctx->owner)))atomic_fetch_add(&ctx->errors,1);
    if(memcmp(values,expected,sizeof(expected)))atomic_fetch_add(&ctx->errors,1);
    uint64_t bits=hash(values);jlong result;memcpy(&result,&bits,8);errno=EDOM;return result;
}
static jlong original_values(JNIEnv *env,jclass owner,LONGS){uint64_t v[16]=VALUES;return original_common(0,env,owner,v);}
static jlong original_fast(JNIEnv *env,jclass owner,LONGS){uint64_t v[16]=VALUES;return original_common(1,env,owner,v);}
static jlong original_critical(LONGS){uint64_t v[16]=VALUES;return original_common(2,NULL,NULL,v);}
static jstring original_text(JNIEnv *env,jobject receiver,jstring a,jstring b,jlong exact,jdouble fraction){
    (void)a;(void)b;(void)exact;(void)fraction;struct context *ctx=&contexts[3];atomic_fetch_add(&ctx->originals,1);
    if(!(*env)->IsSameObject(env,receiver,ctx->receiver))atomic_fetch_add(&ctx->errors,1);return (*env)->NewStringUTF(env,"native");
}
static bool callback(void *opaque,const struct tp_scalar_frame *frame,const uint64_t *stack,int incoming,struct tp_scalar_return *out,int *returned_errno){
    struct context *ctx=opaque;atomic_fetch_add(&ctx->calls,1);JNIEnv *env=NULL;*returned_errno=incoming;*out=(struct tp_scalar_return){0};
    if((*ctx->vm)->GetEnv(ctx->vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK||!env){atomic_fetch_add(&ctx->errors,1);return true;}
    uintptr_t thread=(uintptr_t)ctx->current(),top=thread?*(uintptr_t *)(thread+0xa8):0;
    bool runnable=thread&&(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)==0;
    if(!thread||*(JNIEnv **)(thread+0xc8)!=env||!(top&1)||*(uintptr_t *)(top&~(uintptr_t)3)!=ctx->method
        ||runnable!=(ctx->mode==1||ctx->mode==2)||(ctx->mode!=2&&(JNIEnv *)(uintptr_t)frame->gpr[0]!=env)){
        atomic_fetch_add(&ctx->errors,1);return true;
    }
    atomic_fetch_add(&ctx->frames,1);if((*env)->PushLocalFrame(env,24)<0)return true;atomic_fetch_add(&ctx->pushes,1);
    struct tp_register_arguments args={0};memcpy(args.gpr,frame->gpr,sizeof(args.gpr));memcpy(args.fpr,frame->fpr,sizeof(args.fpr));
    uint64_t overflow[10]={0};unsigned hidden=ctx->mode==2?0:2,spill=ctx->mode==3?0:16-(8-hidden);
    for(unsigned i=0;i<spill;i++)overflow[i]=stack[i];args.stack=overflow;args.stack_size=(spill*8+15)&~15u;
    struct tp_scalar_return original={0};
    if(!tp_register_call(ctx->original,&args,&original)){atomic_fetch_add(&ctx->errors,1);goto done;}
    *returned_errno=errno;if((*env)->ExceptionCheck(env))goto done;
    if(ctx->mode!=3){
        jlong raw[16];for(unsigned i=0;i<16;i++){uint64_t bits=i<8-hidden?frame->gpr[i+hidden]:stack[i-(8-hidden)];memcpy(&raw[i],&bits,8);}
        if(memcmp(raw,expected,sizeof(expected)))atomic_fetch_add(&ctx->errors,1);
        jlongArray array=(*env)->NewLongArray(env,16);if(!array)goto done;(*env)->SetLongArrayRegion(env,array,0,16,raw);
        if(!atomic_load(&ctx->weak)){jweak weak=(*env)->NewWeakGlobalRef(env,array),empty=NULL;if(weak&&!atomic_compare_exchange_strong(&ctx->weak,&empty,weak))(*env)->DeleteWeakGlobalRef(env,weak);}
        jlong value;memcpy(&value,&original.gpr,8);jvalue parameters[3]={{.i=(jint)ctx->mode},{.j=value},{.l=array}};
        jlong result=(*env)->CallStaticLongMethodA(env,ctx->helper,ctx->callback,parameters);if(!(*env)->ExceptionCheck(env))memcpy(&out->gpr,&result,8);
    }else{
        jlong exact; jdouble fraction;memcpy(&exact,&frame->gpr[4],8);memcpy(&fraction,&frame->fpr[0],8);
        jvalue parameters[6]={{.l=(jobject)(uintptr_t)frame->gpr[1]},{.l=(jobject)(uintptr_t)frame->gpr[2]},
            {.l=(jobject)(uintptr_t)frame->gpr[3]},{.j=exact},{.d=fraction},{.l=(jobject)(uintptr_t)original.gpr}};
        jobject result=(*env)->CallStaticObjectMethodA(env,ctx->helper,ctx->callback,parameters);
        if(!(*env)->ExceptionCheck(env)){result=(*env)->PopLocalFrame(env,result);atomic_fetch_add(&ctx->pops,1);out->gpr=(uintptr_t)result;return true;}
    }
done:
    // Pending Java exceptions survive cleanup and the real generic JNI return.
    (*env)->PopLocalFrame(env,NULL);atomic_fetch_add(&ctx->pops,1);return true;
}
static struct tp_entry_owner_policy quick_policies[4];
static _Atomic uintptr_t quick_desired[4];
static void quick_branch(JNIEnv *env,jclass owner,jobject receiver,jmethodID *methods,JNINativeMethod *bindings,jvalue *parameters){
    struct tp_art_managed_access art;bool valid=tp_art_managed_bind(&art);
    jclass helper=(*env)->FindClass(env,"dev/tripoder/selfhookprobe/JniQuickBridge");
    jmethodID values=helper?(*env)->GetStaticMethodID(env,helper,"values","(JJJJJJJJJJJJJJJJ)J"):NULL;
    jmethodID text=helper?(*env)->GetStaticMethodID(env,helper,"text","(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;JD)Ljava/lang/String;"):NULL;
    jmethodID exercise=helper?(*env)->GetStaticMethodID(env,helper,"exercise","(Ldev/tripoder/selfhookprobe/JniRawTarget;)Z"):NULL;
    check(valid&&values&&text&&exercise&&tp_art_entry_writer_installed(),"NATIVE_QUICK_HELPER_AND_WRITER");if(failures)return;
    struct tp_art_method_view *targets[4],*helpers[4],before[4];uintptr_t relays[4];
    for(unsigned i=0;i<4;i++){
        jobject target=(*env)->ToReflectedMethod(env,owner,methods[i],i!=3),destination=(*env)->ToReflectedMethod(env,helper,i==3?text:values,JNI_TRUE);
        targets[i]=(void *)tp_art_method_address(env,target);helpers[i]=(void *)tp_art_method_address(env,destination);
        uint32_t *page=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        bool ready=page!=MAP_FAILED&&targets[i]&&helpers[i]&&tp_art_method_snapshot(env,target,&before[i]);
        if(ready){for(unsigned n=0;n<1024;n++)page[n]=TP_A64_NOP;page[0]=TP_A64_BTI_C;page[1]=0x580000a0;page[2]=0x580000d0;page[3]=0xd61f0200;
            uintptr_t method=(uintptr_t)helpers[i];memcpy(page+6,&method,8);memcpy(page+8,&art.interpreter_bridge,8);__builtin___clear_cache((char *)page,(char *)page+4096);
            ready=mprotect(page,4096,PROT_READ|PROT_EXEC|PROT_BTI)==0;relays[i]=(uintptr_t)page;
            atomic_store(&quick_desired[i],relays[i]);quick_policies[i]=(struct tp_entry_owner_policy){.method=(uintptr_t)targets[i],.owner=&contexts[i],.operation_generation=i+1,.desired_entry=&quick_desired[i]};
            ready=ready&&tp_entry_owner_register(&tp_art_entry_owners,&quick_policies[i])==TP_ENTRY_REGISTERED;
        }
        check(ready,"ROOTED_NATIVE_QUICK_RELAY_AND_OWNER");if(failures)return;
    }
    char jit=0,mutators=0;art.jit_suspend(&jit);art.access.suspend(&mutators,"tripoder-probe-native-quick",false);
    for(unsigned i=0;i<4;i++){helpers[i]->flags=(helpers[i]->flags&~UINT32_C(0x00900000))|UINT32_C(0x02000000);helpers[i]->quick=art.interpreter_bridge;targets[i]->quick=relays[i];
        valid=valid&&targets[i]->flags==before[i].flags&&targets[i]->data==before[i].data;}
    art.access.resume(&mutators);art.jit_resume(&jit);check(valid,"NATIVE_FLAGS_AND_DATA_UNCHANGED_ON_QUICK_PUBLICATION");
    for(unsigned i=0;i<3;i++){jlong value=(*env)->CallStaticLongMethodA(env,owner,methods[i],parameters);uint64_t bits;memcpy(&bits,&value,8);check(!(*env)->ExceptionCheck(env)&&bits==hash(expected)+77,"JNI_NATIVE_QUICK_DISPATCH");if(failures)return;}
    jstring message=(*env)->NewStringUTF(env,"jni-quick");jvalue args[4]={{.l=message},{.l=NULL},{.j=INT64_MIN},{.d=-0.0}};
    jobject result=(*env)->CallObjectMethodA(env,receiver,methods[3],args);check(!(*env)->ExceptionCheck(env)&&(*env)->IsSameObject(env,result,message),"JNI_NATIVE_QUICK_INSTANCE_DISPATCH");if(failures)return;
    check((*env)->CallStaticBooleanMethod(env,helper,exercise,receiver)&&!(*env)->ExceptionCheck(env),"DIRECT_REFLECTIVE_NATIVE_QUICK_WITH_GC");if(failures)return;
    check((*env)->UnregisterNatives(env,owner)==JNI_OK,"REAL_TARGET_UNREGISTER");if(failures)return;
    check((*env)->CallStaticBooleanMethod(env,helper,exercise,receiver)&&!(*env)->ExceptionCheck(env),"QUICK_OWNER_SURVIVES_UNREGISTER");if(failures)return;
    check((*env)->RegisterNatives(env,owner,bindings,4)==JNI_OK,"REAL_TARGET_REREGISTER");if(failures)return;
    check((*env)->CallStaticBooleanMethod(env,helper,exercise,receiver)&&!(*env)->ExceptionCheck(env),"QUICK_OWNER_SURVIVES_REREGISTER");if(failures)return;
    art.jit_suspend(&jit);art.access.suspend(&mutators,"tripoder-probe-native-quick-retire",false);valid=true;
    for(unsigned i=0;i<4;i++){atomic_store(&quick_desired[i],art.jni_bridge);targets[i]->quick=art.jni_bridge;valid=valid&&targets[i]->flags==before[i].flags;}
    art.access.resume(&mutators);art.jit_resume(&jit);check(valid,"NATIVE_QUICK_RETIRE_PRESERVES_FLAGS");
    valid=true;for(unsigned i=0;i<3;i++){jlong value=(*env)->CallStaticLongMethodA(env,owner,methods[i],parameters);uint64_t bits;memcpy(&bits,&value,8);valid=valid&&!(*env)->ExceptionCheck(env)&&bits==hash(expected)+41;}
    check(valid,"RETIRED_QUICK_USES_CURRENT_ART_BINDING");
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_JniRawProbe_run(JNIEnv *env,jclass unused){
    struct tp_art_access access;check(tp_art_access_bind(&access),"FIXED_ART_ACCESS");if(failures)goto done;
    probe_access=access;uintptr_t runtime=*(uintptr_t *)(access.base+TP_FRAMEWORK_RUNTIME_RVA);
    if(runtime&&tp_art_readable(runtime+0x270,8))class_linker=*(void **)(runtime+0x270);
    jmethodID registered=(*env)->GetStaticMethodID(env,unused,"registered","(I)J");
    jclass owner=(*env)->FindClass(env,"dev/tripoder/selfhookprobe/JniRawTarget"),helper=(*env)->FindClass(env,"dev/tripoder/selfhookprobe/JniRawCallback");
    check(owner&&helper,"OWNED_JNI_CLASSES");if(failures)goto done;
    jmethodID constructor=(*env)->GetMethodID(env,owner,"<init>","()V");jobject receiver=constructor?(*env)->NewObject(env,owner,constructor):NULL;
    jmethodID callbacks[2]={(*env)->GetStaticMethodID(env,helper,"values","(IJ[J)J"),(*env)->GetStaticMethodID(env,helper,"text","(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;JDLjava/lang/String;)Ljava/lang/String;")};
    check(receiver&&callbacks[0]&&callbacks[1]&&registered&&class_linker,"REAL_JNI_CALLBACK_METHODS");if(failures)goto done;
    const char *names[]={"values","fast","critical","text"},*longs="(JJJJJJJJJJJJJJJJ)J",*text="(Ljava/lang/String;Ljava/lang/String;JD)Ljava/lang/String;";
    void (*originals[])(void)={(void (*)(void))original_values,(void (*)(void))original_fast,(void (*)(void))original_critical,(void (*)(void))original_text};
    jmethodID methods[4];struct tp_art_method_view before[4];JNINativeMethod bindings[4];struct tp_scalar_inbound inbound[4];
    for(unsigned i=0;i<4;i++){
        methods[i]=i==3?(*env)->GetMethodID(env,owner,names[i],text):(*env)->GetStaticMethodID(env,owner,names[i],longs);
        jobject reflected=methods[i]?(*env)->ToReflectedMethod(env,owner,methods[i],i!=3):NULL;
        check(reflected&&tp_art_method_snapshot(env,reflected,&before[i])&&before[i].quick==access.base+0x2bc710
            &&(before[i].flags&0x180100)==(i==1?0x80100u:i==2?0x100100u:0x100u),"REAL_GENERIC_JNI_MODE_AND_FLAGS");if(failures)goto done;
        struct context *ctx=&contexts[i];ctx->mode=i;(*env)->GetJavaVM(env,&ctx->vm);ctx->owner=(*env)->NewGlobalRef(env,owner);ctx->helper=(*env)->NewGlobalRef(env,helper);ctx->receiver=(*env)->NewGlobalRef(env,receiver);
        ctx->current=access.current;ctx->method=tp_art_method_address(env,reflected);ctx->callback=callbacks[i==3];ctx->original=originals[i];
        check(ctx->owner&&ctx->helper&&ctx->receiver&&ctx->method&&tp_register_inbound_prepare(callback,ctx,&inbound[i])==TP_SCALAR_OK,"IMMUTABLE_REGISTER_ADAPTER");if(failures)goto done;
        bindings[i]=(JNINativeMethod){(char *)names[i],(char *)(i==3?text:longs),(void *)inbound[i].entry};
    }
    check((*env)->RegisterNatives(env,owner,bindings,4)==JNI_OK,"REAL_OWNED_NATIVE_REGISTRATION");if(failures)goto done;
    for(unsigned i=0;i<4;i++){
        jobject reflected=(*env)->ToReflectedMethod(env,owner,methods[i],i!=3);struct tp_art_method_view after;
        jlong effective=(*env)->CallStaticLongMethod(env,unused,registered,(jint)i);
        check(reflected&&tp_art_method_snapshot(env,reflected,&after)&&after.flags==before[i].flags&&after.quick==before[i].quick
            &&(uintptr_t)effective==(uintptr_t)inbound[i].entry
            &&(after.data==(uintptr_t)inbound[i].entry||(i==2&&after.data==before[i].data)),"ALL_NATIVE_FLAGS_AND_EFFECTIVE_BINDING_PRESERVED");if(failures)goto done;
    }
    jfieldID throwing=(*env)->GetStaticFieldID(env,helper,"throwing","Z"),failure=(*env)->GetStaticFieldID(env,helper,"FAILURE","Ljava/lang/IllegalStateException;");
    jobject expected_failure=failure?(*env)->GetStaticObjectField(env,helper,failure):NULL;
    jclass system=(*env)->FindClass(env,"java/lang/System");jmethodID gc=system?(*env)->GetStaticMethodID(env,system,"gc","()V"):NULL;
    check(throwing&&expected_failure&&gc,"CALLBACK_EXCEPTION_AND_GC_PROBES");if(failures)goto done;
    jvalue parameters[16];for(unsigned i=0;i<16;i++)memcpy(&parameters[i].j,&expected[i],8);
    for(unsigned mode=0;mode<3;mode++){
        unsigned loops=mode==2?64:16;bool values=true;
        for(unsigned n=0;n<loops;n++){jlong value=(*env)->CallStaticLongMethodA(env,owner,methods[mode],parameters);uint64_t bits;memcpy(&bits,&value,8);if((*env)->ExceptionCheck(env)||bits!=hash(expected)+41)values=false;}
        check(values,"JAVA_UPCALL_AND_18_ARGUMENT_ORIGINAL");if((*env)->ExceptionCheck(env))goto done;
        (*env)->SetStaticBooleanField(env,helper,throwing,JNI_TRUE);(void)(*env)->CallStaticLongMethodA(env,owner,methods[mode],parameters);
        jthrowable error=(*env)->ExceptionOccurred(env);(*env)->ExceptionClear(env);
        check(error&&(*env)->IsSameObject(env,error,expected_failure),"EXACT_PENDING_EXCEPTION_THROUGH_GENERIC_RETURN");if(error)(*env)->DeleteLocalRef(env,error);(*env)->SetStaticBooleanField(env,helper,throwing,JNI_FALSE);
        (*env)->CallStaticVoidMethod(env,system,gc);jweak weak=atomic_load(&contexts[mode].weak);
        check(weak&&(*env)->IsSameObject(env,weak,NULL),"LOCAL_FRAME_RELEASES_ARRAY_ROOTS");
        check(atomic_load(&contexts[mode].calls)==loops+1&&atomic_load(&contexts[mode].originals)==loops+1
            &&atomic_load(&contexts[mode].frames)==loops+1&&atomic_load(&contexts[mode].pushes)==loops+1
            &&atomic_load(&contexts[mode].pops)==loops+1&&atomic_load(&contexts[mode].errors)==0,"MODE_FRAME_STATE_AND_EXACT_COUNTS");
    }
    jmethodID matches=(*env)->GetStaticMethodID(env,helper,"textMatches","(Ljava/lang/String;Z)Z");jchar first_chars[]={0x3b1,0,0xd83d,0xde80},second_chars[]={0x3b2};
    jstring a=(*env)->NewString(env,first_chars,4),b=(*env)->NewString(env,second_chars,1);
    for(unsigned n=0;n<2;n++){
        jlong exact;uint64_t bits=UINT64_C(0xfedcba9876543210);memcpy(&exact,&bits,8);jvalue p[4]={{.l=a},{.l=n?NULL:b},{.j=exact},{.d=n?-0.0:1.25}};
        jobject result=(*env)->CallObjectMethodA(env,receiver,methods[3],p);check(!(*env)->ExceptionCheck(env)&&result&&(*env)->CallStaticBooleanMethod(env,helper,matches,result,(jboolean)n),"JNI_RECEIVER_UTF16_NULL_AND_FPR_ROOTS");if(result)(*env)->DeleteLocalRef(env,result);
    }
    check(atomic_load(&contexts[3].calls)==2&&atomic_load(&contexts[3].originals)==2&&atomic_load(&contexts[3].pushes)==2
        &&atomic_load(&contexts[3].pops)==2&&atomic_load(&contexts[3].errors)==0,"INSTANCE_REFERENCE_ORIGINAL_COUNTS");
    check(tp_scalar_inbound_remaining()==TP_NATIVE_ENTRY_LIMIT-4,"SHARED_INBOUND_BUDGET");
    quick_branch(env,owner,receiver,methods,bindings,parameters);
done:;
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);check(false,"UNEXPECTED_JNI_EXCEPTION");}
    char json[1400];snprintf(json,sizeof(json),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"firstFailure\":\"%s\",\"modes\":4,\"javaCallbacks\":104,\"quickManagedCalls\":28,\"publicScalarArguments\":16,\"privateIntegerStackBytes\":80,\"nativeFlagsPreserved\":true,\"hookOwnershipInstalled\":true,\"productAccepted\":false}",failures?"false":"true",checks,failures,first?first:"");
    return (*env)->NewStringUTF(env,json);
}
