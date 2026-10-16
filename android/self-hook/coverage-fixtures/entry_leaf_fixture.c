#define _GNU_SOURCE
#include "../native/coverage/owned_entry_registry.h"
#include "../native/coverage/near_entry_leaf.h"
#include "../native/coverage/entry_branch_page.h"
#include "../native/coverage/art_entry_writer.h"
#include "tripoder_thread_park.h"
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unwind.h>
#include "entry_leaf_unwind.h"
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <unistd.h>

struct method { uintptr_t metadata[3]; _Atomic uintptr_t quick; _Atomic unsigned changes; };
static struct method owned,foreign;
static _Atomic uintptr_t desired;
static _Atomic unsigned errors,calls,ready,active_seen,disabled_seen;
static _Atomic bool start;
static bool attempted;
static struct tp_entry_owner_policy policy;
static uint32_t published_image[1024];static _Atomic bool image_ready;
static struct tp_near_entry_leaf publication_lease;
extern uintptr_t tp_entry_fixture_plain(struct method *,uintptr_t);
extern uintptr_t tp_entry_plain_sample_call(void *,uintptr_t);
extern void tp_entry_plain_sample_return(void);
#define fixture_target tp_entry_fixture_plain
static unsigned unwind_frames;
static bool unwind_routed_frame;
static int fixture_diagnostics=-1;
static _Unwind_Reason_Code trace(struct _Unwind_Context *context,void *ignored){
    (void)ignored;uintptr_t pc=_Unwind_GetIP(context);unwind_frames++;
    if(fixture_diagnostics>=0)dprintf(fixture_diagnostics,"sync frame=%u pc=%lx cfa=%lx expected=%lx\n",unwind_frames,
        (unsigned long)pc,(unsigned long)_Unwind_GetCFA(context),(unsigned long)((uintptr_t)fixture_target+20));
    if(pc==(uintptr_t)fixture_target+20){unwind_routed_frame=true;return _URC_END_OF_STACK;}
    return unwind_frames>=32?_URC_END_OF_STACK:_URC_NO_REASON;
}
__attribute__((visibility("hidden"))) uintptr_t tp_entry_fixture_commit(struct method *method,uintptr_t requested){
    // Warm and inspect the real NDK unwinder synchronously on the first call,
    // before worker creation. This does not assert signal-handler safety.
    if(!unwind_frames)_Unwind_Backtrace(trace,NULL);
    uintptr_t before=atomic_load(&method->quick);
    while(before!=requested){
        if(atomic_compare_exchange_weak(&method->quick,&before,requested)){atomic_fetch_add(&method->changes,1);break;}
    }
    return requested;
}
static void *worker(void *unused){
    (void)unused;atomic_fetch_add(&ready,1);while(!atomic_load_explicit(&start,memory_order_acquire)){}
    for(unsigned i=0;i<50000;i++){
        uintptr_t before,after;__asm__ volatile("mov %0,sp":"=r"(before));
        uintptr_t value=fixture_target(&owned,0x9000);
        __asm__ volatile("mov %0,sp":"=r"(after));
        if((value!=0x4000&&value!=0x8000)||before!=after)atomic_fetch_add(&errors,1);
        if(value==0x4000)atomic_fetch_add(&active_seen,1);if(value==0x8000)atomic_fetch_add(&disabled_seen,1);
        if(fixture_target(&foreign,0xa000)!=0xa000)atomic_fetch_add(&errors,1);
        atomic_fetch_add(&calls,2);
    }
    return NULL;
}
static jstring run(JNIEnv *env,jint fd){
    if(attempted)return NULL;attempted=true;
    if(fd<0)return NULL;
    (void)write(fd,"native-entered\n",15);
    fixture_diagnostics=fd;
    struct tp_near_entry_leaf *lease=&publication_lease;struct tp_entry_branch_page branch={0};bool baseline=false;
    struct tp_leaf_sample_range range={0};
    atomic_store(&desired,0x4000);
    policy=(struct tp_entry_owner_policy){.method=(uintptr_t)&owned,.owner=&owned,.operation_generation=1,.desired_entry=&desired};
    if(tp_entry_owner_register(&tp_art_entry_owners,&policy)!=TP_ENTRY_REGISTERED)return NULL;
    {
        baseline=fixture_target(&owned,0x9000)==0x9000;atomic_store(&owned.quick,0);atomic_store(&owned.changes,0);
        Dl_info info;uint32_t image[TP_CODE_PAGE_WORDS];
        memcpy(image,(void *)fixture_target,sizeof(image));
        bool prepared=baseline&&((uintptr_t)fixture_target&4095)==0&&dladdr((void *)fixture_target,&info)
            &&tp_park_prepare_process()==TP_PARK_OK&&tp_near_entry_leaf_prepare((uintptr_t)fixture_target+12,&tp_art_entry_owners,lease)
            &&tp_entry_branch_prepare(&branch,(uintptr_t)fixture_target,lease->leaf,info.dli_fname,image);
        if(!prepared||!tp_entry_branch_publish(&branch,false)){
            dprintf(fd,"near-leaf-error stage=%s errno=%d detail=%s park=%d\n",lease->stage?lease->stage:"PAGE_PREPARE",lease->error,lease->detail,branch.park_status);
            char error[256];snprintf(error,sizeof(error),"{\"passed\":false,\"stage\":\"%s\",\"errno\":%d,\"parkStatus\":%d}",
                lease->stage?lease->stage:"PAGE_PREPARE",lease->error,branch.park_status);return (*env)->NewStringUTF(env,error);
        }
        range=(struct tp_leaf_sample_range){.leaf=lease->leaf,.caller=(uintptr_t)tp_entry_plain_sample_return,
            .post_offset=lease->post_offset,.call=tp_entry_plain_sample_call};
        memcpy(published_image,(void *)fixture_target,sizeof(published_image));atomic_store_explicit(&image_ready,true,memory_order_release);
    }
    if(fixture_target(&owned,0x9000)!=0x4000||atomic_load(&owned.quick)!=0x4000||atomic_load(&owned.changes)!=1
        ||!unwind_frames||!unwind_routed_frame)abort();
    if(fixture_target(&owned,0x9000)!=0x4000||atomic_load(&owned.changes)!=1)abort();
    atomic_store(&desired,0x8000);
    if(fixture_target(&owned,0x9000)!=0x8000||atomic_load(&owned.changes)!=2)abort();
    if(fixture_target(&foreign,0xa000)!=0xa000||atomic_load(&foreign.changes)!=1)abort();
    pthread_t threads[4];unsigned created=0;
    for(;created<4;created++)if(pthread_create(&threads[created],NULL,worker,NULL)!=0)break;
    while(atomic_load(&ready)<created){}
    atomic_store(&desired,0x4000);
    atomic_store_explicit(&start,true,memory_order_release);
    while(created==4&&atomic_load(&calls)<1000){}
    for(unsigned i=0;i<100000;i++)atomic_store_explicit(&desired,(i&1)?0x4000:0x8000,memory_order_release);
    for(unsigned i=0;i<created;i++)if(pthread_join(threads[i],NULL)!=0)abort();
    if(created!=4)abort();
    struct tp_leaf_unwind_report async={0};bool async_ok=tp_entry_leaf_unwind(&owned,&foreign,fd,&range,&async);
    bool restored=false;
    {
        if(!tp_entry_branch_publish(&branch,true))abort();
        struct tp_page_identity current;
        restored=branch.restored&&tp_code_page_identity((void *)fixture_target,"r-xp",&current)&&current.inode>0
            &&memcmp((void *)fixture_target,branch.patched,sizeof(branch.patched))!=0
            &&fixture_target(&owned,0x9000)==0x9000;
    }
    char text[2560];snprintf(text,sizeof(text),"{\"passed\":%s,\"calls\":%u,\"threads\":4,\"stateWrites\":100000,\"errors\":%u,"
        "\"ownedChanges\":%u,\"foreignChanges\":%u,\"activeSeen\":%u,\"disabledSeen\":%u,\"originalBookkeepingPreserved\":true,\"originalSignedLrPreserved\":true,"
        "\"unwindFrames\":%u,\"synchronousRoutedFrameUnwound\":%s,\"artInterpositionInstalled\":%s,\"dynamicCodePublished\":true,"
        "\"plainBaseline\":%s,\"filePageRestored\":%s,\"pageCommits\":%u,\"parkedThreads\":%u,\"sealedElf\":%u,\"registeredFdeRanges\":%u,\"registeredFdePcs\":%u,\"postOffset\":%u,\"cfaOffset\":32,"
        "\"entryAddress\":\"%lx\",\"leafBase\":\"%lx\",\"resumeAddress\":\"%lx\",\"registryAddress\":\"%lx\","
        "\"asyncUnwindVerified\":%s,\"asyncAttempts\":%u,\"asyncSamples\":%u,\"asyncLeafMask\":%u,\"asyncStateMask\":%u,"
        "\"leafAddress\":\"%lx\",\"callerAddress\":\"%lx\",\"unwindThread\":\"sampled-native-worker\",\"controlledLeafOnly\":true,"
        "\"signalFrames\":%u,\"asyncFailures\":%u,\"handlerRestored\":%s}",
        atomic_load(&errors)==0&&atomic_load(&active_seen)>0&&atomic_load(&disabled_seen)>0&&unwind_routed_frame&&async_ok&&restored?"true":"false",atomic_load(&calls),atomic_load(&errors),
        atomic_load(&owned.changes),atomic_load(&foreign.changes),atomic_load(&active_seen),atomic_load(&disabled_seen),unwind_frames,unwind_routed_frame?"true":"false",
        tp_art_entry_writer_installed()?"true":"false",baseline?"true":"false",restored?"true":"false",branch.commits,branch.parked_threads,lease->seals,lease->fde_ranges,lease->fde_pcs,range.post_offset,
        (unsigned long)fixture_target,(unsigned long)lease->base,(unsigned long)lease->resume,(unsigned long)&tp_art_entry_owners,
        async_ok?"true":"false",async.attempts,async.samples,async.leaf_mask,async.state_mask,(unsigned long)async.leaf,(unsigned long)async.caller,
        async.signal_frames,async.failures,async.restored?"true":"false");
    return (*env)->NewStringUTF(env,text);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_EntryOwnerProbe_runNative(JNIEnv *env,jclass type,jint fd){
    (void)type;return run(env,fd);
}
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_selfhookprobe_EntryOwnerProbe_fixturePage(JNIEnv *env,jclass type,jboolean published){
    (void)type;if(published&&!atomic_load_explicit(&image_ready,memory_order_acquire))return NULL;
    jbyteArray bytes=(*env)->NewByteArray(env,4096);
    if(bytes)(*env)->SetByteArrayRegion(env,bytes,0,4096,published?(jbyte *)published_image:(jbyte *)tp_entry_fixture_plain);
    return bytes;
}
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_selfhookprobe_EntryOwnerProbe_publishedElf(JNIEnv *env,jclass type){
    (void)type;int fd=publication_lease.fd;struct stat file;
    if(!atomic_load_explicit(&image_ready,memory_order_acquire)||fd<0||fcntl(fd,F_GET_SEALS)!=15||fstat(fd,&file)
        ||file.st_size<=0||file.st_size>65536)return NULL;
    size_t size=(size_t)file.st_size;char *data=malloc(size);if(!data)return NULL;
    size_t offset=0;
    while(offset<size){ssize_t n=pread(fd,data+offset,size-offset,(off_t)offset);if(n<0&&errno==EINTR)continue;
        if(n<=0){free(data);return NULL;}offset+=(size_t)n;}
    jbyteArray bytes=(*env)->NewByteArray(env,(jsize)size);if(bytes)(*env)->SetByteArrayRegion(env,bytes,0,(jsize)size,(jbyte *)data);
    free(data);return bytes;
}
