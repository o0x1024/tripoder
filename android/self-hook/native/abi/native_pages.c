#include "native_hook_internal.h"
#include "../modules/linker_notifier.h"
#include "tripoder_thread_park.h"
#include <stdlib.h>
#include <string.h>
#include <errno.h>
static struct tp_native_page pages[TP_NATIVE_ENTRY_LIMIT];
struct tp_native_page *tp_native_claim_page(unsigned module,uint32_t generation,uintptr_t entry,const char *path){
    uintptr_t address=entry&~(uintptr_t)4095;struct tp_native_page *free_page=NULL;
    for(unsigned i=0;i<TP_NATIVE_ENTRY_LIMIT;i++){
        struct tp_native_page *page=&pages[i];if(!page->used){if(!free_page)free_page=page;continue;}
        if(page->module==module&&page->generation==generation&&(uintptr_t)page->page.address==address)
            return !strcmp(page->path,path)&&tp_code_page_current(&page->page,page->current)?page:NULL;
    }
    if(!free_page)return NULL;size_t path_length=strnlen(path,sizeof(free_page->path));if(path_length==sizeof(free_page->path))return NULL;
    uint32_t image[TP_CODE_PAGE_WORDS];memcpy(image,(const void *)address,sizeof(image));
    if(!tp_code_page_claim(&free_page->page,(void *)address,path,image))return NULL;
    free_page->module=module;free_page->generation=generation;memcpy(free_page->path,path,path_length+1);
    memcpy(free_page->current,image,sizeof(image));free_page->used=true;
    return free_page;
}
struct change {struct tp_native_page *page;uint32_t desired[TP_CODE_PAGE_WORDS];struct tp_code_page_update update;};
__attribute__((noinline)) static enum tp_native_status tp_native_commit_parked(struct change *changes,unsigned pages_count,const unsigned *handles,unsigned count,bool enable,const struct tp_park_snapshot *park,struct tp_park_context *blocked){
    enum tp_native_status result=TP_NATIVE_OK;
    if(!enable)for(unsigned t=0;t<park->observed;t++){
        struct tp_park_context context;
        if(tp_park_context(park->epoch,t,&context)!=TP_PARK_OK){result=TP_NATIVE_PARK;break;}
        bool pending=tp_scalar_inbound_pending_pc(context.pc);
        for(unsigned i=0;!pending&&i<count;i++){
            uintptr_t relay=tp_native_bindings[handles[i]].bridge.relay;
            pending=context.pc>=relay&&context.pc<relay+16;
        }
        if(pending){*blocked=context;result=TP_NATIVE_BUSY;break;}
    }
    if(enable)for(unsigned i=0;i<count;i++){
        struct tp_native_binding *binding=&tp_native_bindings[handles[i]];
        if(!tp_linker_publication_ready(binding->module,binding->generation)){result=TP_NATIVE_STALE;break;}
    }
    for(unsigned i=0;result==TP_NATIVE_OK&&i<pages_count;i++)if(!tp_code_page_validate(&changes[i].update)){result=TP_NATIVE_PREIMAGE;break;}
    if(result==TP_NATIVE_OK){
        for(unsigned i=0;i<pages_count;i++)if(!tp_code_page_commit(&changes[i].update))tp_native_stop(213);
        for(unsigned i=0;i<count;i++)atomic_store_explicit(&tp_native_bindings[handles[i]].phase,
            enable?TP_NATIVE_ACTIVE:TP_NATIVE_RETIRED,memory_order_release);
    }
    if(tp_park_end(park->epoch)!=TP_PARK_OK)tp_native_stop(214);return result;
}
bool tp_native_change(const unsigned *handles,unsigned count,bool enable){
    tp_native_error_reset();
    if(!handles||!count||count>TP_NATIVE_ENTRY_LIMIT)return tp_native_reject(TP_NATIVE_ARGUMENT,"BATCH_INPUT",0);
    if(pthread_mutex_lock(&tp_native_mutation))tp_native_stop(215);
    bool ok=false,cancel_only=false;unsigned phase=enable?TP_NATIVE_PREPARED:TP_NATIVE_ACTIVE;
    struct change *changes=NULL;unsigned pages_count=0,prepared=0;
    tp_native_reject(TP_NATIVE_STATE,"HANDLE_PHASE",0);
    if(handles[0]>=tp_native_used)goto done;
    if(!enable&&atomic_load_explicit(&tp_native_bindings[handles[0]].phase,memory_order_acquire)==TP_NATIVE_PREPARED){phase=TP_NATIVE_PREPARED;cancel_only=true;}
    for(unsigned i=0;i<count;i++){
        if(handles[i]>=tp_native_used||atomic_load_explicit(&tp_native_bindings[handles[i]].phase,memory_order_acquire)!=phase)goto done;
        for(unsigned j=0;j<i;j++)if(handles[i]==handles[j])goto done;
        struct tp_native_binding *binding=&tp_native_bindings[handles[i]];
        if(enable)for(unsigned j=0;j<tp_native_used;j++)if(j!=handles[i]&&tp_native_bindings[j].entry==binding->entry
            &&atomic_load_explicit(&tp_native_bindings[j].phase,memory_order_acquire)==TP_NATIVE_ACTIVE)goto done;
    }
    if(cancel_only){
        for(unsigned i=0;i<count;i++)atomic_store_explicit(&tp_native_bindings[handles[i]].phase,TP_NATIVE_RETIRED,memory_order_release);
        ok=true;goto done;
    }
    tp_native_reject(TP_NATIVE_RESOURCE,"BATCH_ALLOCATION",0);
    changes=calloc(count,sizeof(*changes));if(!changes){tp_native_reject(TP_NATIVE_RESOURCE,"BATCH_ALLOCATION",errno);goto done;}
    tp_native_reject(TP_NATIVE_PREIMAGE,"PAGE_PLAN",0);
    for(unsigned i=0;i<count;i++){
        struct tp_native_binding *binding=&tp_native_bindings[handles[i]];struct tp_native_page *page=binding->page;
        unsigned index=0;while(index<pages_count&&changes[index].page!=page)index++;
        if(index==pages_count){changes[index].page=page;memcpy(changes[index].desired,page->current,sizeof(page->current));pages_count++;}
        const struct tp_a64_entry_plan *plan=&binding->bridge.plan.entry;
        unsigned word=(unsigned)((plan->patch_address&4095u)/4u);
        uint32_t before=enable?plan->expected_word:plan->branch_word,after=enable?plan->branch_word:plan->expected_word;
        if(changes[index].desired[word]!=before)goto done;changes[index].desired[word]=after;
    }
    for(;prepared<pages_count;prepared++){
        struct change *change=&changes[prepared];
        /* Keep the shared page owner while any entry remains patched. Restore
         * the real file mapping once the last owned word is back to its source. */
        bool original=!memcmp(change->desired,change->page->page.original_image,sizeof(change->desired));
        int status=original?tp_code_page_prepare_restore(&change->page->page,change->page->current,&change->update):
            tp_code_page_prepare(&change->page->page,change->page->current,change->desired,&change->update);
        if(status!=1){tp_native_reject(TP_NATIVE_RESOURCE,"PAGE_PREPARATION",errno);goto done;}
    }
    if(tp_park_prepare_process()!=TP_PARK_OK){tp_native_reject(TP_NATIVE_PARK,"PARK_PREPARE",0);goto done;}
    struct tp_park_snapshot parked;int park_status=tp_park_begin(&parked);
    if(park_status!=TP_PARK_OK){tp_native_reject(park_status==TP_PARK_BUSY||park_status==TP_PARK_TIMEOUT?TP_NATIVE_BUSY:TP_NATIVE_PARK,"PARK_BEGIN",parked.system_error);goto done;}
    struct tp_park_context blocked={0};
    enum tp_native_status status=tp_native_commit_parked(changes,pages_count,handles,count,enable,&parked,&blocked);
    ok=status==TP_NATIVE_OK;
    if(!ok){tp_native_reject(status,"PARKED_VALIDATION",0);tp_native_error_location(handles[0],blocked.tid,blocked.pc);}
    if(ok)for(unsigned i=0;i<pages_count;i++){
        struct change *change=&changes[i];bool restored=change->update.restore_file;void *address=change->page->page.address;
        tp_code_page_finish(&change->update);
        /* Restoring consumes the original_view and clears the low-level claim.
         * The stable shared-page owner can still have PREPARED bindings, and
         * future activations need another retained original file view. Reclaim
         * the exact restored identity while publication leases still protect
         * this generation. Callback code never consults this mutable owner. */
        if(restored&&!tp_code_page_claim(&change->page->page,address,change->page->path,change->desired))tp_native_stop(221);
        memcpy(change->page->current,change->desired,sizeof(change->desired));
    }
done:
    if(!ok)for(unsigned i=0;i<prepared;i++)tp_code_page_cancel(&changes[i].update);
    free(changes);
    if(ok&&!enable)for(unsigned i=0;i<count;i++){
        struct tp_native_binding *binding=&tp_native_bindings[handles[i]];
        if(!tp_linker_release(binding->module,binding->generation))tp_native_stop(216);
    }
    if(ok)tp_native_error_reset();
    if(pthread_mutex_unlock(&tp_native_mutation))tp_native_stop(217);return ok;
}
