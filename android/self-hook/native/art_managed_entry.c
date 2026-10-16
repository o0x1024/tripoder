#define _GNU_SOURCE
#include "art_access.h"
#include "art_method_storage.h"
#include "managed_abi.h"
#include "abi/scalar_inbound.h"
#include "art_interpreter_policy.h"
#include "coverage/owned_entry_registry.h"
#include "coverage/art_entry_writer.h"
#include "tripoder_a64.h"
#include <android/set_abort_message.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

struct entry {
    struct tp_art_method_view *target,*backup,*helper;
    jobject roots[67];unsigned root_count,caller_count;
    _Atomic unsigned transitions;
    uintptr_t callers[64],relay;
    char shorty[34];
    uint32_t semantic_flags,dex_index,method_index;
    bool instance,attempted,native;
    _Atomic bool ready,active;
    _Atomic uint64_t originals;
    _Atomic uintptr_t desired_entry;
    struct tp_entry_owner_policy writer_policy;
    _Atomic(const char *) stage;
};
static struct entry entries[TP_MANAGED_ENTRY_LIMIT];
static struct tp_art_deopt_access art;
static struct tp_art_interpreter_policy interpreter;
static _Atomic bool bound;
static _Atomic bool explicitly_initialized;
static void fatal(const char *reason){android_set_abort_message(reason);abort();}
static bool keep(JNIEnv *env,struct entry *e,jobject value){
    if(!value||e->root_count==67)return false;
    jobject ref=(*env)->NewGlobalRef(env,value);if(!ref)return false;e->roots[e->root_count++]=ref;return true;
}
static bool valid(struct entry *e){
    return tp_art_storage_read_root(&e->target->owner)==tp_art_storage_read_root(&e->backup->owner)&&e->target->dex_index==e->dex_index&&
        (e->target->indices&0xffff)==e->method_index&&(e->native||e->target->data==e->backup->data)&&
        (e->target->flags&(e->native?0x001bffffu:0x0003ffffu))==e->semantic_flags&&((e->target->flags&0x100)!=0)==e->native;
}
/* Called under ManagedHook.class serialization. Binding does not claim policy. */
static bool bind_process(JNIEnv *env){
    if(atomic_load_explicit(&bound,memory_order_acquire))return true;
    if(!tp_art_deopt_bind(&art)||!tp_managed_abi_bind(env)||!tp_art_interpreter_bind(&art,&interpreter)
        ||!tp_art_entry_writer_prepare(&art.managed.access))return false;
    atomic_store_explicit(&bound,true,memory_order_release);return true;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_ManagedHook_nativeOriginalRemainingNative(JNIEnv *env,jclass cls){
    (void)env;(void)cls;return (jint)tp_scalar_inbound_remaining();
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_ManagedHook_capacityNative(JNIEnv *env,jclass cls){
    (void)env;(void)cls;return TP_MANAGED_ENTRY_LIMIT;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_ManagedHook_initializeProcessNative(JNIEnv *env,jclass cls){
    (void)cls;
    if(atomic_load_explicit(&explicitly_initialized,memory_order_acquire)||!bind_process(env))return JNI_FALSE;
    if(!tp_art_entry_writer_activate())return JNI_FALSE;
    char jit=0,mutators=0;art.managed.jit_suspend(&jit);
    art.managed.access.suspend(&mutators,"tripoder-managed-process-start",false);
    bool accepted=tp_art_interpreter_claim_locked(&interpreter);
    art.managed.access.resume(&mutators);art.managed.jit_resume(&jit);
    if(!accepted)fatal("TP_MANAGED_PROCESS_POLICY");
    atomic_store_explicit(&explicitly_initialized,true,memory_order_release);return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_ManagedHook_prepareNative(JNIEnv *env,jclass cls,jint index,
    jobject target,jobject helper,jclass owner,jstring shorty,jobjectArray callers){
    (void)cls;if(index<0||index>=TP_MANAGED_ENTRY_LIMIT||!target||!helper||!owner||!shorty||!callers)return JNI_FALSE;
    struct entry *e=&entries[index];if(e->attempted)return JNI_FALSE;e->attempted=true;e->stage="ART_BIND";
    if(!bind_process(env))return JNI_FALSE;
    jsize count=(*env)->GetArrayLength(env,callers),length=(*env)->GetStringUTFLength(env,shorty);
    if(count>64||length<1||length>33)return JNI_FALSE;
    const char *text=(*env)->GetStringUTFChars(env,shorty,NULL);if(!text)return JNI_FALSE;
    memcpy(e->shorty,text,(size_t)length+1);(*env)->ReleaseStringUTFChars(env,shorty,text);
    for(jsize i=0;i<length;i++)if(!strchr(i?"ZBSCIJFDL":"VZBSCIJFDL",e->shorty[i]))return JNI_FALSE;
    e->stage="METHOD_IDENTITIES";
    struct tp_art_method_view before,h,backup;
    uintptr_t target_address,helper_address;
    if(!tp_art_method_observe(env,target,&target_address,&before)||!tp_art_method_observe(env,helper,&helper_address,&h))return JNI_FALSE;
    e->target=(void *)target_address;e->helper=(void *)helper_address;
    e->backup=tp_art_backup_lookup(e->target);
    if(!e->target||!e->helper||!e->backup||e->target==e->helper||
        !tp_art_backup_snapshot(e->target,&backup)||(before.flags&0x82000400)||(h.flags&0x10508)!=8||!before.data||!h.data)return JNI_FALSE;
    e->instance=(before.flags&8)==0;e->native=(before.flags&0x100)!=0;
    if(!keep(env,e,target)||!keep(env,e,helper)||!keep(env,e,owner))return JNI_FALSE;
    for(jsize i=0;i<count;i++){
        jobject caller=(*env)->GetObjectArrayElement(env,callers,i);struct tp_art_method_view v;
        uintptr_t address=0;bool observed=caller&&tp_art_method_observe(env,caller,&address,&v);
        if(!address||address==(uintptr_t)e->target||address==(uintptr_t)e->helper||
            !observed||(v.flags&0x500)||!keep(env,e,caller))return JNI_FALSE;
        for(jsize j=0;j<i;j++)if(e->callers[j]==address)return JNI_FALSE;
        e->callers[i]=address;(*env)->DeleteLocalRef(env,caller);
    }
    e->caller_count=(unsigned)count;e->stage="RELAY_RX";
    uint32_t *page=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if(page==MAP_FAILED)return JNI_FALSE;
    for(unsigned i=0;i<1024;i++)page[i]=TP_A64_NOP;
    page[0]=TP_A64_BTI_C;page[1]=0x580000a0;page[2]=0x580000d0;page[3]=0xd61f0200;
    uintptr_t method=(uintptr_t)e->helper,bridge=art.managed.interpreter_bridge;
    memcpy(page+6,&method,8);memcpy(page+8,&bridge,8);__builtin___clear_cache((char *)page,(char *)page+4096);
    if(mprotect(page,4096,PROT_READ|PROT_EXEC|PROT_BTI)!=0){munmap(page,4096);return JNI_FALSE;}
    e->relay=(uintptr_t)page;e->stage="HELPER_PREPARATION";
    char jit=0,mutators=0;art.managed.jit_suspend(&jit);art.managed.access.suspend(&mutators,"tripoder-managed-helper",false);
    e->semantic_flags=e->target->flags&(e->native?0x001bffffu:0x0003ffffu);e->dex_index=e->target->dex_index;e->method_index=e->target->indices&0xffff;
    e->helper->flags=(e->helper->flags&~UINT32_C(0x00900000))|UINT32_C(0x02000000);e->helper->quick=bridge;
    bool accepted=valid(e);
    art.managed.access.resume(&mutators);art.managed.jit_resume(&jit);
    if(!accepted)return JNI_FALSE;

    atomic_store(&e->desired_entry,e->native?art.managed.jni_bridge:bridge);
    e->writer_policy=(struct tp_entry_owner_policy){.method=(uintptr_t)e->target,.owner=e,
        .operation_generation=(uint64_t)index+1,.desired_entry=&e->desired_entry};
    atomic_store_explicit(&e->ready,true,memory_order_release);e->stage="PREPARED";return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_ManagedHook_toggleBatchNative(JNIEnv *env,jclass cls,jintArray incoming,jboolean enabled){
    (void)cls;if(!incoming)return JNI_FALSE;jsize count=(*env)->GetArrayLength(env,incoming);
    if(count<1||count>TP_MANAGED_ENTRY_LIMIT)return JNI_FALSE;
    jint indices[TP_MANAGED_ENTRY_LIMIT];bool selected[TP_MANAGED_ENTRY_LIMIT]={false};
    (*env)->GetIntArrayRegion(env,incoming,0,count,indices);if((*env)->ExceptionCheck(env))return JNI_FALSE;
    for(jsize i=0;i<count;i++){
        int index=indices[i];if(index<0||index>=TP_MANAGED_ENTRY_LIMIT||selected[index])return JNI_FALSE;selected[index]=true;
        struct entry *e=&entries[index];if(!atomic_load(&e->ready)||atomic_load(&e->active)==(bool)enabled||e->transitions==256)return JNI_FALSE;
    }
    if(enabled&&!tp_art_entry_writer_activate())return JNI_FALSE;
    char jit=0,mutators=0;art.managed.jit_suspend(&jit);art.managed.access.suspend(&mutators,"tripoder-managed-batch-toggle",false);
    bool accepted=tp_art_interpreter_claim_locked(&interpreter)&&tp_art_entry_writer_installed();
    // Validate EVERY preimage and GC root before registering or changing any entry.
    for(jsize i=0;accepted&&i<count;i++){
        struct entry *e=&entries[indices[i]];
        accepted=valid(e)&&tp_art_backup_rooted_locked(e->target,e->backup)&&
            e->target->quick==(e->transitions&&atomic_load(&e->active)?e->relay:(e->native?art.managed.jni_bridge:art.managed.interpreter_bridge))&&
            (e->target->flags&UINT32_C(0x02000000))==0;
        if(e->transitions==0)for(unsigned n=0;n<e->caller_count;n++)if(
            ((struct tp_art_method_view *)e->callers[n])->quick!=art.managed.interpreter_bridge)accepted=false;
    }
    for(jsize i=0;accepted&&i<count;i++){
        struct entry *e=&entries[indices[i]];
        if(e->transitions==0)accepted=tp_entry_owner_register(&tp_art_entry_owners,&e->writer_policy)==TP_ENTRY_REGISTERED;
    }
    for(jsize i=0;accepted&&i<count;i++){
        struct entry *e=&entries[indices[i]];if(!e->native)e->target->flags&=~UINT32_C(0x00100000);
        uintptr_t desired=enabled?e->relay:(e->native?art.managed.jni_bridge:art.managed.interpreter_bridge);
        atomic_store_explicit(&e->desired_entry,desired,memory_order_release);e->target->quick=desired;
        accepted=valid(e)&&e->target->quick==atomic_load_explicit(&e->desired_entry,memory_order_acquire);
        if(accepted){atomic_store_explicit(&e->active,enabled,memory_order_release);e->transitions++;e->stage=enabled?"ACTIVE":"DISABLED";}
    }
    art.managed.access.resume(&mutators);art.managed.jit_resume(&jit);
    if(!accepted)fatal("TP_MANAGED_ENTRY_OWNERSHIP");
    return JNI_TRUE;
}
JNIEXPORT jobject JNICALL Java_dev_tripoder_selfhook_ManagedHook_originalNative(JNIEnv *env,jclass cls,jint index,jobject receiver,jobjectArray args){
    (void)cls;if(index<0||index>=TP_MANAGED_ENTRY_LIMIT||!atomic_load(&entries[index].ready)){
        jclass error=(*env)->FindClass(env,"java/lang/IllegalStateException");if(error)(*env)->ThrowNew(env,error,"ORIGINAL_ENTRY_UNAVAILABLE");return NULL;
    }
    struct entry *e=&entries[index];atomic_fetch_add_explicit(&e->originals,1,memory_order_relaxed);
    return tp_managed_abi_original(env,e->roots[2],(jmethodID)e->backup,e->instance,e->shorty,receiver,args);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_ManagedHook_reportNative(JNIEnv *env,jclass cls,jint index){
    (void)cls;if(index<0||index>=TP_MANAGED_ENTRY_LIMIT)return NULL;struct entry *e=&entries[index];char text[1280];struct tp_art_method_view backup={0};
    bool rooted=atomic_load(&e->ready)&&tp_art_backup_snapshot(e->target,&backup);
    struct tp_art_method_view target={0};
    if(atomic_load(&e->ready)&&!tp_art_method_snapshot(env,e->roots[0],&target))return NULL;
    snprintf(text,sizeof(text),"{\"stage\":\"%s\",\"ready\":%s,\"active\":%s,\"transitions\":%u,\"originals\":%llu,"
        "\"retainedRoots\":%u,\"retainedRelayPages\":%u,\"knownCallers\":%u,\"backupRooted\":%s,\"shorty\":\"%s\","
        "\"instance\":%s,\"relay\":\"%lx\",\"helperMethod\":\"%lx\",\"interpreterBridge\":\"%lx\","
        "\"targetFlags\":%u,\"backupFlags\":%u,\"targetQuick\":\"%lx\",\"desiredEntry\":\"%lx\",\"writerOwnerRegistered\":%s,"
        "\"writerInterpositionInstalled\":%s,\"completeEntryWriterClosure\":false,\"compilationOwnership\":\"process-interpreter-lease\","
        "\"processInterpreterOwned\":%s,\"observedInstrumentationLevel\":%d,\"processPolicyClaims\":%u,\"fullCallerCoverage\":false}",e->stage?e->stage:"NOT_STARTED",atomic_load(&e->ready)?"true":"false",
        atomic_load(&e->active)?"true":"false",e->transitions,(unsigned long long)atomic_load(&e->originals),e->root_count,e->relay?1:0,
        e->caller_count,rooted?"true":"false",e->shorty,e->instance?"true":"false",(unsigned long)e->relay,(unsigned long)e->helper,
        (unsigned long)art.managed.interpreter_bridge,target.flags,backup.flags,(unsigned long)target.quick,
        (unsigned long)atomic_load(&e->desired_entry),e->transitions?"true":"false",tp_art_entry_writer_installed()?"true":"false",
        atomic_load_explicit(&interpreter.claimed,memory_order_acquire)?"true":"false",
        atomic_load(&interpreter.observed_level),atomic_load(&interpreter.claims));return (*env)->NewStringUTF(env,text);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_ManagedHook_processPolicyNative(JNIEnv *env,jclass cls){
    (void)cls;char text[640];
    snprintf(text,sizeof(text),"{\"bound\":%s,\"owned\":%s,\"observedLevel\":%d,\"claims\":%u,"
        "\"client\":\"%s\",\"processLifetimeLease\":true,\"policy\":\"process-interpreter-lease\","
        "\"explicitInitialization\":%s,\"preexistingCompiledFramesCovered\":false,\"fullCallerCoverage\":false}",
        bound?"true":"false",atomic_load_explicit(&interpreter.claimed,memory_order_acquire)?"true":"false",
        bound?atomic_load(&interpreter.observed_level):-1,atomic_load(&interpreter.claims),tp_art_interpreter_client,
        atomic_load_explicit(&explicitly_initialized,memory_order_acquire)?"true":"false");
    return (*env)->NewStringUTF(env,text);
}
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_selfhook_ManagedHook_relayNative(JNIEnv *env,jclass cls,jint index){
    (void)cls;if(index<0||index>=TP_MANAGED_ENTRY_LIMIT||!atomic_load(&entries[index].ready))return NULL;
    jbyteArray result=(*env)->NewByteArray(env,4096);
    if(result)(*env)->SetByteArrayRegion(env,result,0,4096,(const jbyte *)entries[index].relay);
    return result;
}
