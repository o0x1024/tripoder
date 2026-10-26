#include "art_method_storage.h"
#include "tripoder_hook_limits.h"
#include "art_framework_contract.h"
#include "art_storage_contract.h"
#include "art_jni_original.h"
#include <android/set_abort_message.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ClassExt's native-root visitor is the GC owner; LinearAlloc owns storage.
 * Neither a foreign declared method slot nor an untracked malloc clone is used.
 * Allocation occurs in a real FastNative/Runnable transition with a registered
 * handle scope. Publication occurs later, in ordinary JNI under SuspendAll.
 * Each class owns one fixed-capacity map; only our maps can gain entries. */
struct string_view { const char *data; size_t size; };
struct handle_scope { uintptr_t link; uint32_t capacity,size; volatile uint32_t refs[2]; };
_Static_assert(offsetof(struct handle_scope,refs)==16 && sizeof(struct handle_scope)==24,"pinned ART handle scope");
struct storage_slot {
    jobject owner,loader,target_ref,methods,caches,ext_box,ext;
    struct tp_art_method_view *target,*backup;
    uint32_t initial_owner;
    uintptr_t initial_ext,initial_methods,initial_caches;
    unsigned position;
    _Atomic bool published;
};
static struct storage_slot slots[TP_MANAGED_ENTRY_LIMIT];
static _Atomic unsigned used;
static struct tp_art_managed_access art;
static uintptr_t (*decode_object)(void *,jobject);
static uintptr_t (*ensure_ext)(volatile uint32_t *,void *);
static void (*set_arrays)(uintptr_t,uintptr_t,uintptr_t);
static void *(*get_allocator)(void *,uintptr_t);
static void *(*allocate)(void *,void *,size_t,unsigned);
static void *(*find_field)(uintptr_t,struct string_view,struct string_view);
static jfieldID (*encode_field)(void *);
static void *linker;
static jclass class_class;
static jmethodID fast_allocate,fast_owner;
static uintptr_t (*art_read_root)(const uint32_t *);
static _Atomic unsigned read_barrier_adjustments;
static _Atomic uint64_t owner_comparisons,owner_lookup_ns;
static bool attempted;
static _Atomic bool bound;
static const char *stage="NOT_STARTED";
/* Only code metadata; captured before any rejection and formatted after the
 * existing mutator scopes have resumed. Never dump the entire root table into
 * an exception: logd can otherwise discard the causal Java frames. */
static jint preparation_index=-1;
static uint32_t preparation_flags;
static bool preparation_data;
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhook_OriginalMethodStorage_preparationFailureIndexNative(JNIEnv *env,jclass cls){
    (void)env;(void)cls;return preparation_index;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_OriginalMethodStorage_preparationFailureNative(JNIEnv *env,jclass cls){
    (void)cls;char output[256];
    snprintf(output,sizeof(output),"stage=%s;batchIndex=%d;allocations=%u;flags=0x%x;hasData=%s",stage,preparation_index,atomic_load(&used),preparation_flags,preparation_data?"true":"false");
    return (*env)->NewStringUTF(env,output);
}
static void fatal(const char *reason){android_set_abort_message(reason);abort();}
static jobject root(JNIEnv *env,jobject local){return local?(*env)->NewGlobalRef(env,local):NULL;}
static uintptr_t read_root(const uint32_t *value){
    uint32_t before=*value;uintptr_t canonical=art_read_root(value);
    if(canonical!=before)atomic_fetch_add_explicit(&read_barrier_adjustments,1,memory_order_relaxed);
    return canonical;
}
uintptr_t tp_art_storage_read_root(const uint32_t *value){return read_root(value);}

/* Class equality stays in a single Runnable transition. JNI handles remain
 * rooted across concurrent copying; no raw heap address becomes a map key. */
static unsigned unique_owner_slots[TP_MANAGED_ENTRY_LIMIT];
static unsigned unique_owner_count;
static jint find_owner_fast(JNIEnv *env,jclass unused,jclass owner){
    (void)unused;if(!atomic_load_explicit(&bound,memory_order_acquire)||!owner)return -2;
    void *thread=art.access.current();if(!thread||(atomic_load_explicit((_Atomic uint32_t *)thread,memory_order_relaxed)>>24)!=0)return -2;
    struct timespec start,end;bool clock_ok=clock_gettime(CLOCK_MONOTONIC,&start)==0;
    jint previous=-1;unsigned comps=0;
    for(unsigned k=unique_owner_count;k>0;k--){
        unsigned i=unique_owner_slots[k-1];comps++;
        if(slots[i].owner&&(*env)->IsSameObject(env,owner,slots[i].owner)){
            if(!atomic_load_explicit(&slots[i].published,memory_order_acquire)){previous=-2;break;}
            previous=(jint)i;break;
        }
    }
    atomic_fetch_add(&owner_comparisons,comps);
    if(clock_ok&&clock_gettime(CLOCK_MONOTONIC,&end)==0)atomic_fetch_add(&owner_lookup_ns,(uint64_t)(end.tv_sec-start.tv_sec)*1000000000u+(uint64_t)(end.tv_nsec-start.tv_nsec));
    return previous;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_OriginalMethodStorage_preparationMetrics(JNIEnv *env,jclass cls){
    (void)cls;uint64_t calls=0,ns=0;tp_art_mapping_metrics(&calls,&ns);char output[256];
    snprintf(output,sizeof(output),"{\"refreshes\":%llu,\"mappingCalls\":%llu,\"mappingNs\":%llu,\"ownerComparisons\":%llu,\"ownerLookupNs\":%llu}",(unsigned long long)tp_art_refresh_count(),(unsigned long long)calls,(unsigned long long)ns,(unsigned long long)atomic_load(&owner_comparisons),(unsigned long long)atomic_load(&owner_lookup_ns));
    return (*env)->NewStringUTF(env,output);
}
static void *cached_loader_field, *cached_dex_cache_field;
static jboolean allocate_fast(JNIEnv *env,jclass unused,jint index){
    (void)unused;
    if(!atomic_load_explicit(&bound,memory_order_acquire)||index<0||(unsigned)index>=used)return JNI_FALSE;
    struct storage_slot *s=&slots[index];
    if(s->backup||s->published)return JNI_FALSE;
    void *thread=art.access.current();
    if(!thread)return JNI_FALSE;
    uintptr_t owner=decode_object(thread,s->owner);
    if(!owner||owner>UINT32_MAX)return JNI_FALSE;
    uintptr_t *top=(uintptr_t *)((char *)thread+0x118);
    struct handle_scope scope={.link=*top,.capacity=2,.size=2,.refs={(uint32_t)owner,0}};
    *top=(uintptr_t)&scope;
    bool ok=false;
    do {
        stage="ENSURE_CLASS_EXT";
        uintptr_t ext=ensure_ext(&scope.refs[0],thread);
        if(!ext||ext>UINT32_MAX)break;
        scope.refs[1]=(uint32_t)ext;
        // Never resize or replace another subsystem's roots. Reuse is only
        // allowed when both fixed-capacity arrays are already ours.
        uintptr_t old_caches=read_root((uint32_t *)(ext+0x1c)),old_methods=read_root((uint32_t *)(ext+0x20));
        if((old_caches||old_methods)&&(old_caches!=decode_object(thread,s->caches)||old_methods!=decode_object(thread,s->methods)))break;
        // Class.getClassLoader() exposes a BootClassLoader wrapper on Android.
        // ART's allocator requires the real Class.classLoader field (null for
        // boot classes). Resolve it in this Runnable transition, with roots.
        stage="ACTUAL_DEFINING_LOADER";
        if(!cached_loader_field){
            uintptr_t class_type=decode_object(thread,class_class);
            cached_loader_field=find_field(class_type,(struct string_view){"classLoader",11},
                (struct string_view){"Ljava/lang/ClassLoader;",sizeof("Ljava/lang/ClassLoader;")-1});
        }
        void *loader_field=cached_loader_field;
        if(!loader_field)break;
        jobject loader_ref=(*env)->GetObjectField(env,s->owner,encode_field(loader_field));
        if((*env)->ExceptionCheck(env))break;
        if(loader_ref){s->loader=root(env,loader_ref);(*env)->DeleteLocalRef(env,loader_ref);if(!s->loader)break;}
        uintptr_t loader=s->loader?decode_object(thread,s->loader):0;
        if(s->loader&&!loader)break;
        stage="LINEAR_ALLOCATION";
        void *allocator=get_allocator(linker,loader);
        if(!allocator)break;
        s->backup=allocate(allocator,thread,sizeof(*s->backup),5); // kArtMethod
        if(!s->backup||((uintptr_t)s->backup&7))break;
        memset(s->backup,0,sizeof(*s->backup));
        stage="PREPARE_ROOT_ARRAYS";
        // ART DecodeJObject accepts registered stack handles (tag 00). Copy
        // into a real JNI local before returning or popping the scope.
        jobject ext_ref=(*env)->NewLocalRef(env,(jobject)&scope.refs[1]);
        if(!ext_ref)break;
        (*env)->SetObjectArrayElement(env,s->ext_box,0,ext_ref);
        (*env)->DeleteLocalRef(env,ext_ref);
        if(!cached_dex_cache_field){
            uintptr_t class_type=decode_object(thread,class_class);
            cached_dex_cache_field=find_field(class_type,(struct string_view){"dexCache",8},
                (struct string_view){"Ljava/lang/Object;",sizeof("Ljava/lang/Object;")-1});
        }
        void *field=cached_dex_cache_field;
        if(!field){stage="DEX_CACHE_FIELD_NOT_FOUND";break;}
        jfieldID id=encode_field(field);
        jobject cache=(*env)->GetObjectField(env,s->owner,id);
        if(!cache||(*env)->ExceptionCheck(env))break;
        (*env)->SetObjectArrayElement(env,s->caches,(jsize)s->position,cache);
        (*env)->DeleteLocalRef(env,cache);
        ok=!(*env)->ExceptionCheck(env);
    }while(false);
    if(*top!=(uintptr_t)&scope)fatal("TP_BACKUP_HANDLE_SCOPE_OWNERSHIP");
    *top=scope.link;
    return ok?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_OriginalMethodStorage_bindNative(JNIEnv *env,jclass cls,jobject allocator,jobject owner_lookup){
    TP_ART_MAPS_SCOPE;
    if(attempted)return JNI_FALSE;
    attempted=true;
    stage="STORAGE_ART_CONTRACT";
    if(!tp_art_managed_bind(&art))return JNI_FALSE;
#define STORAGE(name,NAME) stage=#NAME; if(!tp_art_validate_code(&art.access,TP_STORAGE_##NAME##_RVA,tp_storage_##name##_bytes,sizeof(tp_storage_##name##_bytes)))return JNI_FALSE
    STORAGE(ensure_ext,ENSURE_EXT);STORAGE(set_arrays,SET_ARRAYS);STORAGE(allocator,ALLOCATOR);
    STORAGE(linear_alloc,LINEAR_ALLOC);STORAGE(cc_visit,CC_VISIT);STORAGE(mc_visit,MC_VISIT);
    STORAGE(read_root,READ_ROOT);STORAGE(mark_root,MARK_ROOT);
#undef STORAGE
#define FRAMEWORK(name,NAME) stage=#NAME; if(!tp_art_validate_code(&art.access,TP_FRAMEWORK_##NAME##_RVA,tp_framework_##name##_bytes,sizeof(tp_framework_##name##_bytes)))return JNI_FALSE
    FRAMEWORK(decode_object,DECODE_OBJECT);FRAMEWORK(find_field,FIND_FIELD);FRAMEWORK(encode_field,ENCODE_FIELD);
    FRAMEWORK(method_id_layout,METHOD_ID_LAYOUT);FRAMEWORK(method_layout,METHOD_LAYOUT);FRAMEWORK(decode_method,DECODE_METHOD);
#undef FRAMEWORK
    stage="RUNTIME_LAYOUT";
    uintptr_t base=art.access.base,runtime=*(uintptr_t *)(base+TP_FRAMEWORK_RUNTIME_RVA);
    if(!runtime||!tp_art_readable(runtime+TP_FRAMEWORK_ID_TYPE_OFFSET,4)||
        *(uint32_t *)(runtime+TP_FRAMEWORK_ID_TYPE_OFFSET)!=0||!tp_art_readable(runtime+0x270,8))return JNI_FALSE;
    stage="CLASS_LINKER_LAYOUT";
    linker=*(void **)(runtime+0x270);
    if(!linker||!tp_art_readable((uintptr_t)linker+0x170,4)||*(unsigned *)((char *)linker+0x170)!=8)return JNI_FALSE;
    decode_object=(uintptr_t (*)(void *,jobject))(base+TP_FRAMEWORK_DECODE_OBJECT_RVA);
    art_read_root=(uintptr_t (*)(const uint32_t *))(base+TP_STORAGE_READ_ROOT_RVA);
    ensure_ext=(uintptr_t (*)(volatile uint32_t *,void *))(base+TP_STORAGE_ENSURE_EXT_RVA);
    set_arrays=(void (*)(uintptr_t,uintptr_t,uintptr_t))(base+TP_STORAGE_SET_ARRAYS_RVA);
    get_allocator=(void *(*)(void *,uintptr_t))(base+TP_STORAGE_ALLOCATOR_RVA);
    allocate=(void *(*)(void *,void *,size_t,unsigned))(base+TP_STORAGE_LINEAR_ALLOC_RVA);
    find_field=(void *(*)(uintptr_t,struct string_view,struct string_view))(base+TP_FRAMEWORK_FIND_FIELD_RVA);
    encode_field=(jfieldID (*)(void *))(base+TP_FRAMEWORK_ENCODE_FIELD_RVA);
    class_class=root(env,(*env)->FindClass(env,"java/lang/Class"));
    JNINativeMethod entries[]={{"allocateNative","(I)Z",(void *)allocate_fast},{"findOwnerNative","(Ljava/lang/Class;)I",(void *)find_owner_fast}};
    if(!class_class||(*env)->RegisterNatives(env,cls,entries,2)!=JNI_OK)return JNI_FALSE;
    stage="FAST_ALLOCATION_FLAGS";
    struct tp_art_method_view view;
    if(!tp_art_method_snapshot(env,allocator,&view)||(view.flags&0x80108)!=0x80108
        ||!tp_art_method_snapshot(env,owner_lookup,&view)||(view.flags&0x80108)!=0x80108)return JNI_FALSE;
    fast_allocate=(*env)->FromReflectedMethod(env,allocator);fast_owner=(*env)->FromReflectedMethod(env,owner_lookup);
    if(!fast_allocate||!fast_owner)return JNI_FALSE;
    stage="BOUND";atomic_store_explicit(&bound,true,memory_order_release);return JNI_TRUE;
}

/* Called with all mutators suspended. Concurrent copying can leave old root
 * addresses here: SuspendAll alone is not a GC-completion barrier. Use ART's
 * read barrier (as DecodeJObject does), never compare raw references. No JNI,
 * explicit allocation or I/O; ART may perform its own read-barrier marking. */
static bool rooted(struct storage_slot *s,void *thread){
    uintptr_t owner=decode_object(thread,s->owner),ext=decode_object(thread,s->ext);
    uintptr_t methods=decode_object(thread,s->methods),caches=decode_object(thread,s->caches);
    if(!owner||!ext||!methods||!caches||read_root((uint32_t *)(owner+0x14))!=ext||
        read_root((uint32_t *)(ext+0x20))!=methods||read_root((uint32_t *)(ext+0x1c))!=caches||
        *(uint32_t *)(methods+8)!=TP_MANAGED_ENTRY_LIMIT||*(uint32_t *)(caches+8)!=TP_MANAGED_ENTRY_LIMIT||
        *(uintptr_t *)(methods+16+s->position*8)!=(uintptr_t)s->backup||
        read_root((uint32_t *)(caches+12+s->position*4))!=read_root((uint32_t *)(owner+0x10))||read_root(&s->backup->owner)!=owner)return false;
    uintptr_t array=*(uintptr_t *)(owner+0x30);
    unsigned count=*(unsigned *)array;
    uintptr_t start=array+8,end=start+(size_t)count*32,backup=(uintptr_t)s->backup;
    return count&&count<=4096&&(uintptr_t)s->target>=start&&(uintptr_t)s->target<end&&
        (backup<start||backup>=end)&&read_root(&s->target->owner)==owner;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_OriginalMethodStorage_prepareNative(JNIEnv *env,jclass cls,
    jobject target,jclass owner,jstring shorty){
    preparation_index=0;preparation_flags=0;preparation_data=false;stage="STORAGE_INPUT";
    if(!atomic_load(&bound)||used==TP_MANAGED_ENTRY_LIMIT||!target||!owner)return JNI_FALSE;
    uintptr_t target_address;struct tp_art_method_view before;
    stage="TARGET_OBSERVATION";
    if(!tp_art_method_observe(env,target,&target_address,&before))return JNI_FALSE;
    preparation_flags=before.flags;preparation_data=before.data!=0;stage="TARGET_METHOD_SHAPE";
    if((before.flags&0x80000400)!=0||!before.data)return JNI_FALSE;
    // ArtMethods are stable LinearAlloc metadata, retained by each slot's
    // Method/Class/loader roots. Compare metadata identity without per-slot
    // Native→Runnable JNI transitions. Heap Class equality uses the FastNative
    // helper above, where JNI's own read barriers retain exact identity.
    stage="TARGET_ALREADY_STORED";
    for(unsigned i=0;i<used;i++)if((uintptr_t)slots[i].target==target_address)return JNI_FALSE;
    stage="OWNER_LOOKUP";
    jint previous_index=(*env)->CallStaticIntMethod(env,cls,fast_owner,owner);
    if((*env)->ExceptionCheck(env)||previous_index< -1||(unsigned)(previous_index+1)>used)return JNI_FALSE;
    struct storage_slot *previous=previous_index<0?NULL:&slots[previous_index];
    unsigned index=used++;
    struct storage_slot *s=&slots[index];
    s->position=index;
    s->target=(void *)target_address;
    s->owner=root(env,owner);s->target_ref=root(env,target);
    s->methods=root(env,previous?previous->methods:(jobject)(*env)->NewLongArray(env,TP_MANAGED_ENTRY_LIMIT));
    jclass cache_class=(*env)->FindClass(env,"java/lang/DexCache"),object_class=(*env)->FindClass(env,"java/lang/Object");
    if(!cache_class||!object_class)return JNI_FALSE;
    s->caches=root(env,previous?previous->caches:(jobject)(*env)->NewObjectArray(env,TP_MANAGED_ENTRY_LIMIT,cache_class,NULL));
    s->ext_box=root(env,(*env)->NewObjectArray(env,1,object_class,NULL));
    if(!s->target||!s->owner||!s->target_ref||!s->methods||!s->caches||!s->ext_box)return JNI_FALSE;
    if(!(*env)->CallStaticBooleanMethod(env,cls,fast_allocate,(jint)index)||(*env)->ExceptionCheck(env))return JNI_FALSE;
    s->ext=root(env,(*env)->GetObjectArrayElement(env,s->ext_box,0));
    if(!s->ext)return JNI_FALSE;
    (*env)->DeleteGlobalRef(env,s->ext_box);s->ext_box=NULL;
    uintptr_t native_original=0;
    if(before.flags&0x100){native_original=tp_art_jni_original_prepare(env,index,s->target,s->backup,shorty);if(!native_original)return JNI_FALSE;}
    stage="PUBLISH_ROOTED_BACKUP";
    void *thread=art.access.current();char jit=0,mutators=0;
    art.jit_suspend(&jit);art.access.suspend(&mutators,"tripoder-backup-root-publication",false);
    uintptr_t ext=decode_object(thread,s->ext),klass=decode_object(thread,s->owner);
    bool valid=ext&&klass&&read_root(&s->target->owner)==klass&&!(s->target->flags&0x400)&&
        ((!*(uint32_t *)(ext+0x1c)&&!*(uint32_t *)(ext+0x20))||
         (read_root((uint32_t *)(ext+0x1c))==decode_object(thread,s->caches)&&read_root((uint32_t *)(ext+0x20))==decode_object(thread,s->methods)));
    if(valid){
        art.copy(s->backup,s->target,8);
        s->initial_owner=(uint32_t)read_root(&s->backup->owner);
        s->initial_ext=ext;s->initial_methods=decode_object(thread,s->methods);s->initial_caches=decode_object(thread,s->caches);
        if(native_original){s->backup->data=native_original;s->backup->quick=art.jni_bridge;}
        else{s->backup->flags=(s->backup->flags&~UINT32_C(0x00900000))|UINT32_C(0x02000000);s->backup->quick=art.interpreter_bridge;}
        uintptr_t methods=decode_object(thread,s->methods);
        if(*(uintptr_t *)(methods+16+s->position*8))fatal("TP_BACKUP_MAP_SLOT_OWNED");
        *(uintptr_t *)(methods+16+s->position*8)=(uintptr_t)s->backup;
        // Re-publish identical array roots to execute ClassExt's card barrier
        // when an already-published native-root array gains a method.
        set_arrays(ext,methods,decode_object(thread,s->caches));
        s->published=true;valid=rooted(s,thread);
    }
    art.access.resume(&mutators);art.jit_resume(&jit);
    if(!valid&&s->published)fatal("TP_BACKUP_ROOT_PUBLICATION_MISMATCH");
    if(valid&&!previous&&unique_owner_count<TP_MANAGED_ENTRY_LIMIT)unique_owner_slots[unique_owner_count++]=index;
    stage=valid?"PREPARED":"CLASS_EXT_OWNERSHIP_REJECTED";return valid?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_OriginalMethodStorage_prepareBatchNative(JNIEnv *env,jclass cls,
    jobjectArray targets,jobjectArray owners,jobjectArray shorties){
    preparation_index=-1;preparation_flags=0;preparation_data=false;stage="BATCH_INPUT";
    if(!atomic_load(&bound)||!targets||!owners||!shorties)return JNI_FALSE;
    jsize count=(*env)->GetArrayLength(env,targets);
    if(count<1||(unsigned)count>TP_MANAGED_ENTRY_LIMIT-used
        ||(*env)->GetArrayLength(env,owners)!=count||(*env)->GetArrayLength(env,shorties)!=count)return JNI_FALSE;
    static uintptr_t batch_addresses[TP_MANAGED_ENTRY_LIMIT];
    static struct tp_art_method_view batch_before[TP_MANAGED_ENTRY_LIMIT];
    static uintptr_t batch_native_originals[TP_MANAGED_ENTRY_LIMIT];
    static bool batch_new_owner[TP_MANAGED_ENTRY_LIMIT];
    for(jsize k=0;k<count;k++){
        preparation_index=k;stage="TARGET_ADDRESS";
        jobject target=(*env)->GetObjectArrayElement(env,targets,k);
        uintptr_t addr=target?tp_art_method_address(env,target):0;
        (*env)->DeleteLocalRef(env,target);
        if(!addr)return JNI_FALSE;
        stage="TARGET_ALREADY_STORED";
        for(unsigned i=0;i<used;i++)if((uintptr_t)slots[i].target==addr)return JNI_FALSE;
        stage="DUPLICATE_BATCH_TARGET";
        for(jsize j=0;j<k;j++)if(batch_addresses[j]==addr)return JNI_FALSE;
        batch_addresses[k]=addr;
    }
    stage="CURRENT_THREAD";if(art.access.current()==NULL)return JNI_FALSE;
    char obs_scope=0;
    art.access.suspend(&obs_scope,"tripoder-owned-method-batch-snapshot",false);
    for(jsize k=0;k<count;k++){
        uint32_t words[8];
        for(unsigned i=0;i<8;i++)words[i]=((volatile uint32_t *)batch_addresses[k])[i];
        memcpy(&batch_before[k],words,sizeof(words));
    }
    art.access.resume(&obs_scope);
    for(jsize k=0;k<count;k++){
        preparation_index=k;preparation_flags=batch_before[k].flags;preparation_data=batch_before[k].data!=0;stage="TARGET_METHOD_SHAPE";
        if((batch_before[k].flags&0x80000400)!=0||!batch_before[k].data)return JNI_FALSE;
    }
    stage="METADATA_CLASSES";
    jclass cache_class=(*env)->FindClass(env,"java/lang/DexCache"),object_class=(*env)->FindClass(env,"java/lang/Object");
    if(!cache_class||!object_class)return JNI_FALSE;
    unsigned first=used;
    unsigned prev_unique_owners=unique_owner_count;
    bool alloc_ok=true;
    for(jsize k=0;k<count;k++){
        preparation_index=k;stage="BATCH_TARGET_ROOTS";
        preparation_flags=batch_before[k].flags;preparation_data=batch_before[k].data!=0;
        jobject target=(*env)->GetObjectArrayElement(env,targets,k);
        jclass owner=(*env)->GetObjectArrayElement(env,owners,k);
        jstring shorty=(*env)->GetObjectArrayElement(env,shorties,k);
        if(!target||!owner||!shorty){alloc_ok=false;break;}
        stage="OWNER_LOOKUP";jint previous_index=(*env)->CallStaticIntMethod(env,cls,fast_owner,owner);
        if((*env)->ExceptionCheck(env)||previous_index< -1||(unsigned)(previous_index+1)>used){alloc_ok=false;break;}
        struct storage_slot *previous=previous_index<0?NULL:&slots[previous_index];
        unsigned index=used++;
        struct storage_slot *s=&slots[index];
        s->position=index;
        s->target=(void *)batch_addresses[k];
        s->owner=root(env,owner);s->target_ref=root(env,target);
        s->methods=root(env,previous?previous->methods:(jobject)(*env)->NewLongArray(env,TP_MANAGED_ENTRY_LIMIT));
        s->caches=root(env,previous?previous->caches:(jobject)(*env)->NewObjectArray(env,TP_MANAGED_ENTRY_LIMIT,cache_class,NULL));
        s->ext_box=root(env,(*env)->NewObjectArray(env,1,object_class,NULL));
        if(!s->target||!s->owner||!s->target_ref||!s->methods||!s->caches||!s->ext_box){alloc_ok=false;break;}
        if(!(*env)->CallStaticBooleanMethod(env,cls,fast_allocate,(jint)index)||(*env)->ExceptionCheck(env)){alloc_ok=false;break;}
        s->ext=root(env,(*env)->GetObjectArrayElement(env,s->ext_box,0));
        if(!s->ext){alloc_ok=false;break;}
        (*env)->DeleteGlobalRef(env,s->ext_box);s->ext_box=NULL;
        batch_native_originals[k]=0;
        if(batch_before[k].flags&0x100){
            batch_native_originals[k]=tp_art_jni_original_prepare(env,index,s->target,s->backup,shorty);
            if(!batch_native_originals[k]){alloc_ok=false;break;}
        }
        // Mark provisionally published so subsequent methods in the same batch
        // with the same declaring class reuse this ClassExt and arrays via find_owner_fast!
        atomic_store_explicit(&s->published,true,memory_order_release);
        batch_new_owner[k]=(previous==NULL);
        if(batch_new_owner[k]&&unique_owner_count<TP_MANAGED_ENTRY_LIMIT)unique_owner_slots[unique_owner_count++]=index;
        (*env)->DeleteLocalRef(env,target);(*env)->DeleteLocalRef(env,owner);(*env)->DeleteLocalRef(env,shorty);
    }
    if(!alloc_ok){
        for(unsigned idx=first;idx<used;idx++)atomic_store_explicit(&slots[idx].published,false,memory_order_release);
        unique_owner_count=prev_unique_owners;
        return JNI_FALSE;
    }
    stage="PUBLISH_ROOTED_BACKUP";
    void *thread=art.access.current();char jit=0,mutators=0;
    art.jit_suspend(&jit);art.access.suspend(&mutators,"tripoder-backup-root-publication",false);
    bool valid=true;
    for(jsize k=0;k<count&&valid;k++){
        unsigned index=first+(unsigned)k;
        struct storage_slot *s=&slots[index];
        uintptr_t ext=decode_object(thread,s->ext),klass=decode_object(thread,s->owner);
        valid=ext&&klass&&read_root(&s->target->owner)==klass&&!(s->target->flags&0x400)&&
            ((!*(uint32_t *)(ext+0x1c)&&!*(uint32_t *)(ext+0x20))||
             (read_root((uint32_t *)(ext+0x1c))==decode_object(thread,s->caches)&&read_root((uint32_t *)(ext+0x20))==decode_object(thread,s->methods)));
        if(valid){
            art.copy(s->backup,s->target,8);
            s->initial_owner=(uint32_t)read_root(&s->backup->owner);
            s->initial_ext=ext;s->initial_methods=decode_object(thread,s->methods);s->initial_caches=decode_object(thread,s->caches);
            if(batch_native_originals[k]){s->backup->data=batch_native_originals[k];s->backup->quick=art.jni_bridge;}
            else{s->backup->flags=(s->backup->flags&~UINT32_C(0x00900000))|UINT32_C(0x02000000);s->backup->quick=art.interpreter_bridge;}
            uintptr_t methods=decode_object(thread,s->methods);
            if(*(uintptr_t *)(methods+16+s->position*8))fatal("TP_BACKUP_MAP_SLOT_OWNED");
            *(uintptr_t *)(methods+16+s->position*8)=(uintptr_t)s->backup;
            set_arrays(ext,methods,decode_object(thread,s->caches));
            valid=rooted(s,thread);
        }
    }
    art.access.resume(&mutators);art.jit_resume(&jit);
    if(!valid)fatal("TP_BACKUP_ROOT_PUBLICATION_MISMATCH");
    stage="PREPARED";return JNI_TRUE;
}

struct tp_art_method_view *tp_art_backup_lookup(const struct tp_art_method_view *target){
    for(unsigned i=used;i>0;i--)if(slots[i-1].published&&slots[i-1].target==target)return slots[i-1].backup;
    return NULL;
}
struct tp_art_method_view *tp_art_backup_lookup_slot(unsigned index,const struct tp_art_method_view *target){
    if(index<used&&slots[index].published&&slots[index].target==target)return slots[index].backup;
    return tp_art_backup_lookup(target);
}
bool tp_art_backup_rooted_locked(const struct tp_art_method_view *target,const struct tp_art_method_view *backup){
    void *thread=art.access.current();if(!thread)return false;
    for(unsigned i=used;i>0;i--)if(slots[i-1].published&&slots[i-1].target==target)
        return slots[i-1].backup==backup&&rooted(&slots[i-1],thread);
    return false;
}
bool tp_art_backup_rooted_slot_locked(unsigned index,const struct tp_art_method_view *target,const struct tp_art_method_view *backup){
    void *thread=art.access.current();if(!thread)return false;
    if(index<used&&slots[index].published&&slots[index].target==target)
        return slots[index].backup==backup&&rooted(&slots[index],thread);
    return tp_art_backup_rooted_locked(target,backup);
}
int tp_art_backup_snapshot(const struct tp_art_method_view *target,struct tp_art_method_view *backup){
    for(unsigned i=0;i<used;i++)if(slots[i].published&&slots[i].target==target){
        char scope=0;void *thread=art.access.current();if(!thread)return 0;
        art.access.suspend(&scope,"tripoder-backup-root-snapshot",false);
        bool valid=rooted(&slots[i],thread);if(valid){*backup=*slots[i].backup;backup->owner=(uint32_t)read_root(&slots[i].backup->owner);}
        art.access.resume(&scope);return valid;
    }
    return 0;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhook_OriginalMethodStorage_report(JNIEnv *env,jclass cls){
    (void)cls;unsigned published=0,valid=0,moved=0,root_moves=0,retained=0,boot=0;
    struct observation { bool present,rooted; uintptr_t owner,target_owner,backup_owner,containers[3]; };
    unsigned count=atomic_load(&used);
    if(count>TP_MANAGED_ENTRY_LIMIT)return NULL;
    struct observation *observed=calloc(count?count:1,sizeof(*observed));
    if(!observed)return NULL;
    // Capture all addresses in one mutator pause; format/allocate only after
    // resuming. These are historical evidence addresses, never live handles.
    if(atomic_load(&bound)){
        char scope=0;void *thread=art.access.current();if(!thread){free(observed);return NULL;}
        art.access.suspend(&scope,"tripoder-backup-root-evidence",false);
        for(unsigned i=0;i<count;i++)if(slots[i].published){
            struct storage_slot *s=&slots[i];struct observation *o=&observed[i];
            o->present=true;o->rooted=rooted(s,thread);o->owner=decode_object(thread,s->owner);
            o->target_owner=read_root(&s->target->owner);o->backup_owner=read_root(&s->backup->owner);
            o->containers[0]=decode_object(thread,s->ext);o->containers[1]=decode_object(thread,s->methods);
            o->containers[2]=decode_object(thread,s->caches);
        }
        art.access.resume(&scope);
    }
    for(unsigned i=0;i<count;i++)if(observed[i].present){
        struct storage_slot *s=&slots[i];struct observation *o=&observed[i];published++;retained+=s->loader?6u:5u;boot+=s->loader?0u:1u;
        if(o->rooted){valid++;moved+=o->owner!=s->initial_owner;
            root_moves+=(o->containers[0]!=s->initial_ext)+(o->containers[1]!=s->initial_methods)+(o->containers[2]!=s->initial_caches);}
    }
    size_t capacity=2048+(size_t)count*512;
    char *text=malloc(capacity);if(!text){free(observed);return NULL;}
    int length=snprintf(text,capacity,"{\"stage\":\"%s\",\"allocations\":%u,\"published\":%u,\"rootedOutsideDeclaredTables\":%u,"
        "\"observedOwnerMoves\":%u,\"observedContainerRootMoves\":%u,\"retainedSlotRoots\":%u,\"retainedRegistryRoots\":%u,"
        "\"obsoleteFlag\":false,\"jniIdType\":0,\"bootAllocations\":%u,\"readBarrierAdjustedReads\":%u,\"rootSnapshots\":[",
        stage,count,published,valid,moved,root_moves,retained,class_class?1:0,boot,atomic_load(&read_barrier_adjustments));
    if(length<0||(size_t)length>=capacity){free(observed);free(text);return NULL;}
    size_t offset=(size_t)length;bool first=true;
    for(unsigned i=0;i<count;i++)if(observed[i].present){
        struct storage_slot *s=&slots[i];struct observation *o=&observed[i];
        length=snprintf(text+offset,capacity-offset,"%s{\"slot\":%u,\"rooted\":%s,\"target\":\"%lx\",\"backup\":\"%lx\","
            "\"bootAllocator\":%s,\"ownerBefore\":\"%lx\",\"ownerAfter\":\"%lx\",\"targetOwner\":\"%lx\",\"backupOwner\":\"%lx\","
            "\"containersBefore\":[\"%lx\",\"%lx\",\"%lx\"],\"containersAfter\":[\"%lx\",\"%lx\",\"%lx\"]}",
            first?"":",",i,o->rooted?"true":"false",(unsigned long)s->target,(unsigned long)s->backup,s->loader?"false":"true",
            (unsigned long)s->initial_owner,(unsigned long)o->owner,(unsigned long)o->target_owner,(unsigned long)o->backup_owner,
            (unsigned long)s->initial_ext,(unsigned long)s->initial_methods,(unsigned long)s->initial_caches,
            (unsigned long)o->containers[0],(unsigned long)o->containers[1],(unsigned long)o->containers[2]);
        if(length<0||(size_t)length>=capacity-offset){free(observed);free(text);return NULL;}offset+=(size_t)length;first=false;
    }
    if(offset+3>capacity){free(observed);free(text);return NULL;}memcpy(text+offset,"]}",3);
    jstring result=(*env)->NewStringUTF(env,text);free(observed);free(text);return result;
}
