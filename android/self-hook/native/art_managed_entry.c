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
    uintptr_t callers[64],relay,prepared_quick;
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
struct identity_snapshot {
    uintptr_t target_owner,backup_owner,target_data,backup_data;
    uint32_t flags,dex_index,method_index;unsigned mismatch;
};
static bool valid_snapshot(struct entry *e,struct identity_snapshot *out){
    struct identity_snapshot value={.target_owner=tp_art_storage_read_root(&e->target->owner),
        .backup_owner=tp_art_storage_read_root(&e->backup->owner),.target_data=e->target->data,.backup_data=e->backup->data,
        .flags=e->target->flags,.dex_index=e->target->dex_index,.method_index=e->target->indices&0xffff};
    value.mismatch=value.target_owner!=value.backup_owner?1:value.dex_index!=e->dex_index?2:
        value.method_index!=e->method_index?3:(!e->native&&value.target_data!=value.backup_data)?4:
        (value.flags&(e->native?0x001bffffu:0x0003ffffu))!=e->semantic_flags?5:((value.flags&0x100)!=0)!=e->native?6:0;
    if(out)*out=value;return value.mismatch==0;
}
static bool valid(struct entry *e){return valid_snapshot(e,NULL);}
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
static bool unhooked_managed_entry(uintptr_t quick){
    uintptr_t base=art.managed.access.base;
    return quick==art.managed.interpreter_bridge||quick==base+0x728040||quick==base+0x2bc5d0;
}
static bool claim_and_enable_nterp_locked(void){
    bool already=atomic_load_explicit(&interpreter.claimed,memory_order_acquire);
    volatile uint32_t *level=(volatile uint32_t *)((uintptr_t)interpreter.instrumentation+4);
    if(already)*level=2;
    bool accepted=tp_art_interpreter_claim_locked(&interpreter);
    if(already)*level=0;
    else if(accepted){
        uintptr_t runtime=*(uintptr_t *)(art.managed.access.base+0xe19200);
        uintptr_t jit_ptr=runtime?*(uintptr_t *)(runtime+0x298):0;
        uintptr_t jit_opt=jit_ptr?*(uintptr_t *)(jit_ptr+0x10):0;
        if(jit_opt)*(uint8_t *)jit_opt=0;
        if(runtime){*(uint8_t *)(runtime+0x362)=0;*(uint32_t *)(runtime+0x3f0)=1;}
        ((void (*)(void *,const char *,int,bool))(art.managed.access.base+0x8df864))(
            interpreter.instrumentation,tp_art_interpreter_client,0,false);
        for(unsigned i=0;i<TP_MANAGED_ENTRY_LIMIT;i++)if(entries[i].helper)entries[i].helper->quick=art.managed.interpreter_bridge;
    }
    return accepted;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_ManagedHook_initializeProcessNative(JNIEnv *env,jclass cls){
    (void)cls;
    if(atomic_load_explicit(&explicitly_initialized,memory_order_acquire)||!bind_process(env))return JNI_FALSE;
    if(!tp_art_entry_writer_activate())return JNI_FALSE;
    char jit=0,mutators=0;art.managed.jit_suspend(&jit);
    art.managed.access.suspend(&mutators,"tripoder-managed-process-start",false);
    bool accepted=claim_and_enable_nterp_locked();
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
    /* JNI identities and live mappings are checked before the pause. All
     * metadata preimages, backup roots, and helper publication are then checked
     * in ONE JIT/mutator snapshot instead of four separate SuspendAll cycles.
     * Every Method/Class/loader remains rooted throughout this interval. */
    uintptr_t target_address=tp_art_method_address(env,target),helper_address=tp_art_method_address(env,helper);
    if(!target_address||!helper_address)return JNI_FALSE;
    e->target=(void *)target_address;e->helper=(void *)helper_address;
    e->backup=tp_art_backup_lookup(e->target);
    if(!e->backup||e->target==e->helper)return JNI_FALSE;
    if(!keep(env,e,target)||!keep(env,e,helper)||!keep(env,e,owner))return JNI_FALSE;
    for(jsize i=0;i<count;i++){
        jobject caller=(*env)->GetObjectArrayElement(env,callers,i);
        uintptr_t address=caller?tp_art_method_address(env,caller):0;
        if(!address||address==(uintptr_t)e->target||address==(uintptr_t)e->helper||
            !keep(env,e,caller))return JNI_FALSE;
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
    struct tp_art_method_view before=*e->target,h=*e->helper;
    bool accepted=tp_art_backup_rooted_locked(e->target,e->backup)&&!(before.flags&0x82000400)&&
        (h.flags&0x10508)==8&&before.data&&h.data;
    for(unsigned i=0;i<e->caller_count;i++)if(((struct tp_art_method_view *)e->callers[i])->flags&0x500)accepted=false;
    if(accepted){
        e->instance=(before.flags&8)==0;e->native=(before.flags&0x100)!=0;
        e->semantic_flags=before.flags&(e->native?0x001bffffu:0x0003ffffu);e->dex_index=before.dex_index;e->method_index=before.indices&0xffff;
        e->prepared_quick=before.quick;
        accepted=valid(e);
        if(accepted){e->helper->flags=(h.flags&~UINT32_C(0x00900000))|UINT32_C(0x02000000);e->helper->quick=bridge;}
    }
    art.managed.access.resume(&mutators);art.managed.jit_resume(&jit);
    if(!accepted){munmap(page,4096);e->relay=0;return JNI_FALSE;}

    atomic_store(&e->desired_entry,e->native?art.managed.jni_bridge:bridge);
    e->writer_policy=(struct tp_entry_owner_policy){.method=(uintptr_t)e->target,.owner=e,
        .operation_generation=(uint64_t)index+1,.desired_entry=&e->desired_entry};
    atomic_store_explicit(&e->ready,true,memory_order_release);e->stage="PREPARED";return JNI_TRUE;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_ManagedHook_prepareBatchNative(JNIEnv *env,jclass cls,jint first,
    jobjectArray targets,jobjectArray helpers,jobjectArray owners,jobjectArray shorties,jobjectArray callerArrays){
    (void)cls;if(!targets||!helpers||!owners||!shorties||!callerArrays||first<0)return 0;
    jsize count=(*env)->GetArrayLength(env,targets);
    if(count<1||first+count>TP_MANAGED_ENTRY_LIMIT
        ||(*env)->GetArrayLength(env,helpers)!=count||(*env)->GetArrayLength(env,owners)!=count
        ||(*env)->GetArrayLength(env,shorties)!=count||(*env)->GetArrayLength(env,callerArrays)!=count)return first;
    if(!bind_process(env)){entries[first].stage="ART_BIND";return first;}
    for(jsize k=0;k<count;k++){
        jint index=first+k;struct entry *e=&entries[index];
        if(e->attempted)return index;
        e->attempted=true;e->stage="METHOD_IDENTITIES";
        jobject target=(*env)->GetObjectArrayElement(env,targets,k);
        jobject helper=(*env)->GetObjectArrayElement(env,helpers,k);
        jclass owner=(*env)->GetObjectArrayElement(env,owners,k);
        jstring shorty=(*env)->GetObjectArrayElement(env,shorties,k);
        jobjectArray callers=(*env)->GetObjectArrayElement(env,callerArrays,k);
        if(!target||!helper||!owner||!shorty||!callers)return index;
        jsize c_count=(*env)->GetArrayLength(env,callers),length=(*env)->GetStringUTFLength(env,shorty);
        if(c_count>64||length<1||length>33)return index;
        const char *text=(*env)->GetStringUTFChars(env,shorty,NULL);if(!text)return index;
        memcpy(e->shorty,text,(size_t)length+1);(*env)->ReleaseStringUTFChars(env,shorty,text);
        for(jsize i=0;i<length;i++)if(!strchr(i?"ZBSCIJFDL":"VZBSCIJFDL",e->shorty[i]))return index;
        uintptr_t target_address=tp_art_method_address(env,target),helper_address=tp_art_method_address(env,helper);
        if(!target_address||!helper_address)return index;
        e->target=(void *)target_address;e->helper=(void *)helper_address;
        e->backup=tp_art_backup_lookup_slot((unsigned)index,e->target);
        if(!e->backup||e->target==e->helper)return index;
        if(!keep(env,e,target)||!keep(env,e,helper)||!keep(env,e,owner))return index;
        for(jsize i=0;i<c_count;i++){
            jobject caller=(*env)->GetObjectArrayElement(env,callers,i);
            uintptr_t address=caller?tp_art_method_address(env,caller):0;
            if(!address||address==(uintptr_t)e->target||address==(uintptr_t)e->helper||!keep(env,e,caller))return index;
            for(jsize j=0;j<i;j++)if(e->callers[j]==address)return index;
            e->callers[i]=address;(*env)->DeleteLocalRef(env,caller);
        }
        e->caller_count=(unsigned)c_count;e->stage="RELAY_RX";
        (*env)->DeleteLocalRef(env,target);(*env)->DeleteLocalRef(env,helper);
        (*env)->DeleteLocalRef(env,owner);(*env)->DeleteLocalRef(env,shorty);(*env)->DeleteLocalRef(env,callers);
    }
    uintptr_t bridge=art.managed.interpreter_bridge;
    for(jsize base=0;base<count;base+=16){
        jsize chunk=count-base>16?16:count-base;
        size_t bytes=(size_t)chunk*4096;
        uint32_t *slab=mmap(NULL,bytes,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        if(slab==MAP_FAILED)return first+base;
        for(size_t i=0;i<(size_t)chunk*1024;i++)slab[i]=TP_A64_NOP;
        for(jsize j=0;j<chunk;j++){
            struct entry *e=&entries[first+base+j];
            uint32_t *page=slab+(size_t)j*1024;
            page[0]=TP_A64_BTI_C;page[1]=0x580000a0;page[2]=0x580000d0;page[3]=0xd61f0200;
            uintptr_t method=(uintptr_t)e->helper;
            memcpy(page+6,&method,8);memcpy(page+8,&bridge,8);
            e->relay=(uintptr_t)page;e->stage="HELPER_PREPARATION";
        }
        __builtin___clear_cache((char *)slab,(char *)slab+bytes);
        if(mprotect(slab,bytes,PROT_READ|PROT_EXEC|PROT_BTI)!=0){
            for(jsize j=0;j<chunk;j++)entries[first+base+j].relay=0;
            munmap(slab,bytes);return first+base;
        }
    }
    char jit=0,mutators=0;art.managed.jit_suspend(&jit);art.managed.access.suspend(&mutators,"tripoder-managed-helper",false);
    jint failed=-1;
    for(jsize k=0;k<count;k++){
        jint index=first+k;struct entry *e=&entries[index];
        struct tp_art_method_view before=*e->target,h=*e->helper;
        bool accepted=tp_art_backup_rooted_slot_locked((unsigned)index,e->target,e->backup)&&!(before.flags&0x82000400)&&
            (h.flags&0x10508)==8&&before.data&&h.data;
        for(unsigned i=0;i<e->caller_count;i++)if(((struct tp_art_method_view *)e->callers[i])->flags&0x500)accepted=false;
        if(accepted){
            e->instance=(before.flags&8)==0;e->native=(before.flags&0x100)!=0;
            e->semantic_flags=before.flags&(e->native?0x001bffffu:0x0003ffffu);e->dex_index=before.dex_index;e->method_index=before.indices&0xffff;
            e->prepared_quick=before.quick;
            accepted=valid(e);
            if(accepted){e->helper->flags=(h.flags&~UINT32_C(0x00900000))|UINT32_C(0x02000000);e->helper->quick=bridge;}
        }
        if(!accepted){failed=index;break;}
    }
    art.managed.access.resume(&mutators);art.managed.jit_resume(&jit);
    if(failed>=0){
        for(jsize k=0;k<count;k++){
            struct entry *e=&entries[first+k];
            if(e->relay){munmap((void *)e->relay,4096);e->relay=0;}
        }
        return failed;
    }
    for(jsize k=0;k<count;k++){
        jint index=first+k;struct entry *e=&entries[index];
        atomic_store(&e->desired_entry,e->native?art.managed.jni_bridge:bridge);
        e->writer_policy=(struct tp_entry_owner_policy){.method=(uintptr_t)e->target,.owner=e,
            .operation_generation=(uint64_t)index+1,.desired_entry=&e->desired_entry};
        atomic_store_explicit(&e->ready,true,memory_order_release);e->stage="PREPARED";
    }
    return -1;
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
    bool accepted=claim_and_enable_nterp_locked()&&tp_art_entry_writer_installed();
    unsigned failure=accepted?0:1;int failed_index=-1;uintptr_t observed_quick=0,expected_quick=0;uint32_t observed_flags=0;
    struct identity_snapshot identity={0};uintptr_t prepared_quick=0;uint32_t expected_flags=0,expected_dex=0,expected_index=0;
    // Validate EVERY preimage and GC root before registering or changing any entry.
    for(jsize i=0;accepted&&i<count;i++){
        int slot=indices[i];
        struct entry *e=&entries[slot];
        uintptr_t expected=e->transitions&&atomic_load(&e->active)?e->relay:(e->native?art.managed.jni_bridge:art.managed.interpreter_bridge);
        bool quick_ok=e->transitions==0&&!e->native?unhooked_managed_entry(e->target->quick):e->target->quick==expected;
        failure=!valid_snapshot(e,&identity)?2:!tp_art_backup_rooted_slot_locked((unsigned)slot,e->target,e->backup)?3:
            !quick_ok?4:(e->target->flags&UINT32_C(0x02000000))?5:0;
        accepted=failure==0;
        if(e->transitions==0)for(unsigned n=0;n<e->caller_count;n++)if(
            !unhooked_managed_entry(((struct tp_art_method_view *)e->callers[n])->quick)){accepted=false;failure=6;}
        if(!accepted){failed_index=slot;observed_quick=e->target->quick;expected_quick=expected;observed_flags=e->target->flags;
            prepared_quick=e->prepared_quick;expected_flags=e->semantic_flags;expected_dex=e->dex_index;expected_index=e->method_index;}
    }
    for(jsize i=0;accepted&&i<count;i++){
        struct entry *e=&entries[indices[i]];
        if(e->transitions==0)accepted=tp_entry_owner_register(&tp_art_entry_owners,&e->writer_policy)==TP_ENTRY_REGISTERED;
        if(!accepted){failure=7;failed_index=indices[i];}
    }
    for(jsize i=0;accepted&&i<count;i++){
        struct entry *e=&entries[indices[i]];if(!e->native)e->target->flags&=~UINT32_C(0x00100000);
        uintptr_t desired=enabled?e->relay:(e->native?art.managed.jni_bridge:art.managed.interpreter_bridge);
        atomic_store_explicit(&e->desired_entry,desired,memory_order_release);e->target->quick=desired;
        accepted=valid(e)&&e->target->quick==atomic_load_explicit(&e->desired_entry,memory_order_acquire);
        if(!accepted){failure=8;failed_index=indices[i];}
        if(accepted){atomic_store_explicit(&e->active,enabled,memory_order_release);e->transitions++;e->stage=enabled?"ACTIVE":"DISABLED";}
    }
    art.managed.access.resume(&mutators);art.managed.jit_resume(&jit);
    if(!accepted){char reason[512];snprintf(reason,sizeof(reason),"TP_MANAGED_ENTRY_OWNERSHIP stage=%u slot=%d quick=%lx expected=%lx prepared=%lx flags=%x identity=%u owners=%lx/%lx semantic=%x/%x dex=%u/%u index=%u/%u data=%lx/%lx",failure,failed_index,(unsigned long)observed_quick,(unsigned long)expected_quick,(unsigned long)prepared_quick,observed_flags,identity.mismatch,(unsigned long)identity.target_owner,(unsigned long)identity.backup_owner,identity.flags,expected_flags,identity.dex_index,expected_dex,identity.method_index,expected_index,(unsigned long)identity.target_data,(unsigned long)identity.backup_data);fatal(reason);}
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
