#define _GNU_SOURCE
#include "art_access.h"
#include "art_method_storage.h"
#include "tripoder_a64.h"
#include <android/set_abort_message.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

/* x0 changes to a separate real Native ArtMethod before entering ART's JNI
 * bridge. The original target remains managed while its old frames exist.
 * Only the fixed owned static int(int) ABI is admitted here. */
static struct {
    struct tp_art_deopt_access art;
    struct tp_art_method_view *target,*backup,*helper;
    uintptr_t callers[64],relay;
    jobject roots[70];unsigned root_count,caller_count,transitions;
    jclass owner;jmethodID original_id;
    uint32_t semantic_flags,dex_index,indices;
    jint delta;
    const char *stage;
} hook={.stage="NOT_STARTED"};
static _Atomic bool attempted,ready,enabled,busy;
static _Atomic uint64_t callbacks,originals,inflight;

static void fatal(const char *reason){android_set_abort_message(reason);abort();}
static bool keep(JNIEnv *env,jobject value){
    if(!value||hook.root_count>=70)return false;
    jobject root=(*env)->NewGlobalRef(env,value);if(!root)return false;
    hook.roots[hook.root_count++]=root;return true;
}
static jint original(JNIEnv *env,jint value){
    jvalue args[]={{.i=value}};
    atomic_fetch_add_explicit(&originals,1,memory_order_relaxed);
    return (*env)->CallStaticIntMethodA(env,hook.owner,hook.original_id,args);
}
static jint delegate(JNIEnv *env,jclass unused,jint value){
    (void)unused;
    if(!atomic_load_explicit(&ready,memory_order_acquire))fatal("TP_INT_ENTRY_NOT_READY");
    bool apply=atomic_load_explicit(&enabled,memory_order_acquire);
    atomic_fetch_add_explicit(&inflight,1,memory_order_relaxed);
    atomic_fetch_add_explicit(&callbacks,1,memory_order_relaxed);
    jint result=original(env,value);
    atomic_fetch_sub_explicit(&inflight,1,memory_order_relaxed);
    if((*env)->ExceptionCheck(env))return 0;
    return (jint)((uint32_t)result+(apply?(uint32_t)hook.delta:0));
}
static unsigned target_mismatch(void){
    const uint32_t semantics=UINT32_C(0x0001ffff);
    return ((hook.target->flags&semantics)!=hook.semantic_flags?1u:0u)|
        (hook.target->owner!=hook.backup->owner?2u:0u)|(hook.target->dex_index!=hook.dex_index?4u:0u)|
        // Upper 16 bits are ART's mutable hotness count, not method identity.
        ((hook.target->indices&0xffff)!=(hook.indices&0xffff)?8u:0u)|
        (hook.target->data!=hook.backup->data?16u:0u)|((hook.target->flags&0x100)?32u:0u);
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_OwnedIntHook_prepareNative(JNIEnv *env,jclass self,jobject target,
    jobject helper,jclass owner,jobjectArray callers,jint delta){
    bool expected=false;
    if(!atomic_compare_exchange_strong(&attempted,&expected,true))return JNI_FALSE;
    hook.stage="ART_CONTRACT";
    if(!tp_art_deopt_bind(&hook.art)||!hook.art.managed.access.current()||!callers)return JNI_FALSE;
    jsize count=(*env)->GetArrayLength(env,callers);
    if(count<1||count>64)return JNI_FALSE;
    hook.stage="OWNED_METHODS";
    hook.target=(void *)tp_art_method_address(env,target);
    hook.backup=tp_art_backup_lookup(hook.target);
    hook.helper=(void *)tp_art_method_address(env,helper);
    struct tp_art_method_view t,b,h;
    if(!hook.target||!hook.backup||!hook.helper||hook.target==hook.backup||hook.target==hook.helper||hook.backup==hook.helper||
        !tp_art_method_snapshot(env,target,&t)||!tp_art_backup_snapshot(hook.target,&b)||!tp_art_method_snapshot(env,helper,&h)||
        (t.flags&UINT32_C(0x528))!=8||(b.flags&UINT32_C(0x508))!=8||(h.flags&UINT32_C(0x508))!=0x108||!t.data)return JNI_FALSE;
    // Storage bind admits only ART kPointer JNI IDs.
    hook.original_id=(jmethodID)hook.backup;
    if(!hook.original_id)return JNI_FALSE;
    if(!keep(env,target)||!keep(env,helper)||!keep(env,owner)||!keep(env,self))return JNI_FALSE;
    hook.owner=(jclass)hook.roots[2];hook.caller_count=(unsigned)count;hook.delta=delta;
    for(jsize i=0;i<count;i++){
        jobject caller=(*env)->GetObjectArrayElement(env,callers,i);
        struct tp_art_method_view view;
        uintptr_t address=caller?tp_art_method_address(env,caller):0;
        if(!address||address==(uintptr_t)hook.target||address==(uintptr_t)hook.backup||address==(uintptr_t)hook.helper||
            !tp_art_method_snapshot(env,caller,&view)||(view.flags&0x500))return JNI_FALSE;
        for(jsize j=0;j<i;j++)if(hook.callers[j]==address)return JNI_FALSE;
        hook.callers[i]=address;if(!keep(env,caller))return JNI_FALSE;(*env)->DeleteLocalRef(env,caller);
    }
    hook.stage="NATIVE_HELPER";
    JNINativeMethod binding={"delegate","(I)I",(void *)delegate};
    if((*env)->RegisterNatives(env,self,&binding,1)!=JNI_OK||!tp_art_method_snapshot(env,helper,&h)||
        h.data!=(uintptr_t)delegate||(h.flags&UINT32_C(0x108))!=0x108)return JNI_FALSE;
    hook.stage="RELAY_RX";
    void *page=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if(page==MAP_FAILED)return JNI_FALSE;
    uint32_t *words=page;for(unsigned i=0;i<1024;i++)words[i]=TP_A64_NOP;
    words[0]=TP_A64_BTI_C;
    words[1]=UINT32_C(0x580000a0); /* LDR x0, +20 -> literal at +24 */
    words[2]=UINT32_C(0x580000d0); /* LDR x16,+24 -> literal at +32 */
    words[3]=UINT32_C(0xd61f0200); /* BR x16; LR/SP/arguments untouched */
    uintptr_t method=(uintptr_t)hook.helper,bridge=hook.art.managed.jni_bridge;
    memcpy(words+6,&method,8);memcpy(words+8,&bridge,8);
    __builtin___clear_cache(page,(char *)page+4096);
    if(mprotect(page,4096,PROT_READ|PROT_EXEC|PROT_BTI)!=0){munmap(page,4096);return JNI_FALSE;}
    hook.relay=(uintptr_t)page;
    hook.stage="BACKUP_AND_CALLERS";
    char jit_scope=0,mutator_scope=0;
    hook.art.managed.jit_suspend(&jit_scope);
    hook.art.managed.access.suspend(&mutator_scope,"tripoder-owned-int-backup",false);
    bool valid=!(hook.target->flags&0x100),mutated=false;
    for(unsigned i=0;i<hook.caller_count;i++)if(hook.art.is_deopt(hook.art.instrumentation,(void *)hook.callers[i]))valid=false;
    if(valid){
        mutated=true;
        hook.semantic_flags=hook.target->flags&UINT32_C(0x0001ffff);
        hook.dex_index=hook.target->dex_index;hook.indices=hook.target->indices;
        for(unsigned i=0;i<hook.caller_count;i++)hook.art.deopt(hook.art.instrumentation,(void *)hook.callers[i]);
        for(unsigned i=0;i<hook.caller_count;i++)if(!hook.art.is_deopt(hook.art.instrumentation,(void *)hook.callers[i])||
            ((struct tp_art_method_view *)hook.callers[i])->quick!=hook.art.managed.interpreter_bridge)valid=false;
    }
    hook.art.managed.access.resume(&mutator_scope);
    hook.art.managed.jit_resume(&jit_scope);
    if(!valid){if(mutated)fatal("TP_INT_ENTRY_CALLER_DEOPT_STATE_MISMATCH");hook.stage="CALLER_REJECTED";return JNI_FALSE;}
    atomic_store_explicit(&ready,true,memory_order_release);hook.stage="PREPARED";return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_OwnedIntHook_toggle(JNIEnv *env,jclass self,jboolean active){
    (void)env;(void)self;bool expected=false;
    if(!atomic_load_explicit(&ready,memory_order_acquire)||!atomic_compare_exchange_strong(&busy,&expected,true))return JNI_FALSE;
    bool was_active=atomic_load(&enabled);
    if(was_active==(bool)active||hook.transitions>=128){atomic_store(&busy,false);return JNI_FALSE;}
    if(!active)atomic_store_explicit(&enabled,false,memory_order_release);
    char jit_scope=0,mutator_scope=0;
    hook.art.managed.jit_suspend(&jit_scope);
    hook.art.managed.access.suspend(&mutator_scope,"tripoder-owned-int-entry",false);
    unsigned mismatch=target_mismatch();
    if(hook.transitions&&hook.target->quick!=(was_active?hook.relay:hook.art.managed.interpreter_bridge))mismatch|=64;
    bool valid=mismatch==0;
    if(valid){
        // Only a compilation-control flag and quick entry change. In particular
        // kAccNative, data, owner, dex index and vtable indices remain managed.
        hook.target->flags=(hook.target->flags&~UINT32_C(0x00900000))|UINT32_C(0x02000000);
        hook.target->quick=active?hook.relay:hook.art.managed.interpreter_bridge;
        mismatch=target_mismatch();valid=mismatch==0;
    }
    hook.art.managed.access.resume(&mutator_scope);
    hook.art.managed.jit_resume(&jit_scope);
    if(!valid){char reason[160];snprintf(reason,sizeof(reason),"TP_INT_ENTRY_METADATA_OWNERSHIP_MISMATCH:mask=%u,transitions=%u",mismatch,hook.transitions);fatal(reason);}
    if(active)atomic_store_explicit(&enabled,true,memory_order_release);
    hook.transitions++;hook.stage=active?"ACTIVE":"DISABLED";atomic_store(&busy,false);return JNI_TRUE;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_OwnedIntHook_callOriginal(JNIEnv *env,jclass self,jint value){
    (void)self;if(!atomic_load_explicit(&ready,memory_order_acquire))fatal("TP_INT_ORIGINAL_NOT_READY");return original(env,value);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_OwnedIntHook_report(JNIEnv *env,jclass self){
    (void)self;char text[1024];
    struct tp_art_method_view view={0},helper={0},backup={0};
    if(atomic_load(&ready)&&(!tp_art_method_snapshot(env,hook.roots[0],&view)||
        !tp_art_method_snapshot(env,hook.roots[1],&helper)||!tp_art_backup_snapshot(hook.target,&backup)))return NULL;
    snprintf(text,sizeof(text),"{\"stage\":\"%s\",\"ready\":%s,\"enabled\":%s,\"transitions\":%u,\"retainedRoots\":%u,"
        "\"callbacks\":%llu,\"originals\":%llu,\"inflight\":%llu,\"callerCount\":%u,\"targetFlags\":%u,"
        "\"targetData\":\"%lx\",\"targetQuick\":\"%lx\",\"relay\":\"%lx\",\"retainedRelayPages\":%u,"
        "\"helperMethod\":\"%lx\",\"jniBridge\":\"%lx\",\"helperFlags\":%u,\"backupFlags\":%u,"
        "\"helperData\":\"%lx\",\"backupData\":\"%lx\",\"genericManagedHook\":false}",
        hook.stage,atomic_load(&ready)?"true":"false",atomic_load(&enabled)?"true":"false",hook.transitions,hook.root_count,
        (unsigned long long)atomic_load(&callbacks),(unsigned long long)atomic_load(&originals),(unsigned long long)atomic_load(&inflight),hook.caller_count,
        view.flags,(unsigned long)view.data,(unsigned long)view.quick,(unsigned long)hook.relay,hook.relay?1:0,
        (unsigned long)hook.helper,(unsigned long)hook.art.managed.jni_bridge,helper.flags,backup.flags,
        (unsigned long)helper.data,(unsigned long)backup.data);
    return (*env)->NewStringUTF(env,text);
}
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_selfhook_OwnedIntHook_relayBytes(JNIEnv *env,jclass self){
    (void)self;if(!atomic_load_explicit(&ready,memory_order_acquire)||!hook.relay)return NULL;
    jbyteArray bytes=(*env)->NewByteArray(env,4096);
    if(bytes)(*env)->SetByteArrayRegion(env,bytes,0,4096,(const jbyte *)hook.relay);
    return bytes;
}
