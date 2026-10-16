#define _GNU_SOURCE
#include "art_access.h"
#include "art_managed_contract.h"
#include "coverage/art_entry_writer.h"
#include "coverage/art_entry_writer_contract.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_EntryWriterProbe_exerciseNative(JNIEnv *env,jclass cls,jobject target){
    (void)cls;if(!tp_art_entry_writer_installed()||!target)return NULL;
    struct tp_art_deopt_access art;if(!tp_art_deopt_bind(&art))return NULL;
#define CHECK(name,NAME) if(!tp_art_validate_code(&art.managed.access,TP_WRITER_##NAME##_RVA,tp_writer_##name##_bytes,sizeof(tp_writer_##name##_bytes)))return NULL
    CHECK(reinitialize,REINITIALIZE);CHECK(update,UPDATE);CHECK(cache_invalidate_all,CACHE_INVALIDATE_ALL);
#undef CHECK
    struct tp_art_method_view *method=(void *)tp_art_method_address(env,target);uintptr_t selected=0;
    if(!method||tp_entry_owner_select(&tp_art_entry_owners,(uintptr_t)method,art.managed.interpreter_bridge,&selected)!=TP_ENTRY_SELECTED
        ||selected==art.managed.interpreter_bridge)return NULL;
    // The exact central writer body proves Runtime+0x298 -> Jit, Jit+8 ->
    // JitCodeCache. InvalidateAllCompiledCode is a JitCodeCache method, NOT an
    // Instrumentation method. Never pass the similarly nearby object pointer.
    uintptr_t slot=art.managed.access.base+TP_ART_RUNTIME_INSTANCE_RVA;
    if(!tp_art_readable(slot,8))return NULL;uintptr_t runtime=*(uintptr_t *)slot;
    if(!runtime||!tp_art_readable(runtime+0x298,8))return NULL;uintptr_t jit=*(uintptr_t *)(runtime+0x298);
    if(!jit||!tp_art_readable(jit+8,8))return NULL;void *cache=*(void **)(jit+8);
    if(!cache||!tp_art_readable((uintptr_t)cache,8))return NULL;
    uintptr_t base=art.managed.access.base;
    void (*reinitialize)(void *,void *)=(void (*)(void *,void *))(base+TP_WRITER_REINITIALIZE_RVA);
    void (*update)(void *,void *,uintptr_t)=(void (*)(void *,void *,uintptr_t))(base+TP_WRITER_UPDATE_RVA);
    void (*invalidate)(void *)=(void (*)(void *))(base+TP_WRITER_CACHE_INVALIDATE_ALL_RVA);
    unsigned resets=0,updates=0,invalidations=0,errors=0;
    char paused_jit=0,mutators=0;art.managed.jit_suspend(&paused_jit);
    art.managed.access.suspend(&mutators,"tripoder-owned-writer-events",false);
    uintptr_t before=method->quick;
    for(unsigned i=0;i<64;i++){
        reinitialize(art.instrumentation,method);resets++;if(method->quick!=selected)errors++;
        update(art.instrumentation,method,art.managed.interpreter_bridge);updates++;if(method->quick!=selected)errors++;
        if(i%16==0){invalidate(cache);invalidations++;if(method->quick!=selected)errors++;}
    }
    uintptr_t after=method->quick;
    art.managed.access.resume(&mutators);art.managed.jit_resume(&paused_jit);
    char text[640];snprintf(text,sizeof(text),"{\"passed\":%s,\"reinitializeCalls\":%u,\"updateCalls\":%u,\"globalInvalidations\":%u,"
        "\"errors\":%u,\"before\":\"%lx\",\"after\":\"%lx\",\"desired\":\"%lx\",\"requested\":\"%lx\","
        "\"originalWritersExecuted\":true,\"fullEntryWriterClosure\":false}",
        before==selected&&after==selected&&errors==0?"true":"false",resets,updates,invalidations,errors,
        (unsigned long)before,(unsigned long)after,(unsigned long)selected,(unsigned long)art.managed.interpreter_bridge);
    return (*env)->NewStringUTF(env,text);
}
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_selfhookprobe_EntryWriterProbe_imageNative(JNIEnv *env,jclass cls,jint kind){
    (void)cls;const struct tp_entry_branch_page *page=tp_art_entry_writer_page();
    const struct tp_near_entry_leaf *leaf=tp_art_entry_writer_leaf();if(!page||!leaf||!tp_art_entry_writer_installed())return NULL;
    const void *source=NULL;char *allocated=NULL;size_t size=4096;
    if(kind==0)source=tp_writer_page_bytes;
    else if(kind==1)source=(void *)page->page.address;
    else if(kind==2){
        struct stat file;if(fcntl(leaf->fd,F_GET_SEALS)!=15||fstat(leaf->fd,&file)||file.st_size<=0||file.st_size>65536)return NULL;
        size=(size_t)file.st_size;allocated=malloc(size);if(!allocated)return NULL;
        size_t offset=0;while(offset<size){ssize_t n=pread(leaf->fd,allocated+offset,size-offset,(off_t)offset);
            if(n<0&&errno==EINTR)continue;if(n<=0){free(allocated);return NULL;}offset+=(size_t)n;}source=allocated;
    }else return NULL;
    jbyteArray result=(*env)->NewByteArray(env,(jsize)size);if(result)(*env)->SetByteArrayRegion(env,result,0,(jsize)size,source);
    free(allocated);return result;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_EntryWriterProbe_statusNative(JNIEnv *env,jclass cls){
    (void)cls;const struct tp_entry_branch_page *page=tp_art_entry_writer_page();const struct tp_near_entry_leaf *leaf=tp_art_entry_writer_leaf();
    if(!page||!leaf)return NULL;char text[768];
    snprintf(text,sizeof(text),"{\"installed\":%s,\"entry\":\"%lx\",\"leaf\":\"%lx\",\"leafBase\":\"%lx\",\"resume\":\"%lx\","
        "\"registry\":\"%lx\",\"seals\":%u,\"fdeRanges\":%u,\"fdePcs\":%u,\"pageCommits\":%u,\"parkedThreads\":%u,"
        "\"sourceBti\":%s,\"savedOriginalView\":\"%lx\","
        "\"processLifetimeLease\":true,\"fullEntryWriterClosure\":false}",tp_art_entry_writer_installed()?"true":"false",
        (unsigned long)page->entry,(unsigned long)leaf->leaf,(unsigned long)leaf->base,(unsigned long)leaf->resume,
        (unsigned long)&tp_art_entry_owners,leaf->seals,leaf->fde_ranges,leaf->fde_pcs,page->commits,page->parked_threads,
        page->page.source.bti?"true":"false",(unsigned long)page->page.original_view);
    return (*env)->NewStringUTF(env,text);
}
