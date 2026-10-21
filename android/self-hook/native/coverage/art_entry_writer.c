#include "art_entry_writer.h"
#include "art_entry_writer_contract.h"
#include "../art_managed_contract.h"
#include "tripoder_thread_park.h"
#include <stdatomic.h>
#include <string.h>

static struct tp_near_entry_leaf leaf;
static struct tp_entry_branch_page page;
static _Atomic bool prepared,installed;
static atomic_flag activating=ATOMIC_FLAG_INIT;
static bool attempted;
static uintptr_t runtime_slot,bound_runtime,bound_jit;
static void *bound_cache;
static void (*invalidate_cache)(void *);
bool tp_art_entry_writer_prepare(const struct tp_art_access *access){
    if(atomic_load_explicit(&prepared,memory_order_acquire))return true;
    if(attempted||!access)return false;attempted=true;
    if(!tp_art_validate_code(access,TP_WRITER_PAGE_RVA,tp_writer_page_bytes,sizeof(tp_writer_page_bytes))
        ||!tp_art_validate_code(access,TP_WRITER_ENTRY_RVA,tp_writer_entry_bytes,sizeof(tp_writer_entry_bytes))
        ||!tp_art_validate_code(access,TP_WRITER_CACHE_INVALIDATE_ALL_RVA,tp_writer_cache_invalidate_all_bytes,sizeof(tp_writer_cache_invalidate_all_bytes))
        ||tp_park_prepare_process()!=TP_PARK_OK)return false;
    runtime_slot=access->base+TP_ART_RUNTIME_INSTANCE_RVA;
    if(!tp_art_readable(runtime_slot,8))return false;bound_runtime=*(uintptr_t *)runtime_slot;
    if(!bound_runtime||!tp_art_readable(bound_runtime+0x298,8))return false;bound_jit=*(uintptr_t *)(bound_runtime+0x298);
    if(bound_jit){
        if(!tp_art_readable(bound_jit+8,8))return false;bound_cache=*(void **)(bound_jit+8);
        if(!bound_cache||!tp_art_readable((uintptr_t)bound_cache,8))return false;
    }
    invalidate_cache=(void (*)(void *))(access->base+TP_WRITER_CACHE_INVALIDATE_ALL_RVA);
    uintptr_t entry=access->base+TP_WRITER_ENTRY_RVA;uint32_t image[TP_CODE_PAGE_WORDS];
    memcpy(image,tp_writer_page_bytes,sizeof(image));
    if(!tp_near_entry_leaf_prepare(entry+12,&tp_art_entry_owners,&leaf)
        ||!tp_entry_branch_prepare(&page,entry,leaf.leaf,"/apex/com.android.art/lib64/libart.so",image))return false;
    atomic_store_explicit(&prepared,true,memory_order_release);return true;
}
bool tp_art_entry_writer_activate(void){
    if(!atomic_load_explicit(&prepared,memory_order_acquire))return false;
    if(atomic_flag_test_and_set_explicit(&activating,memory_order_acquire))return false;
    bool active=atomic_load_explicit(&installed,memory_order_acquire),ok;
    if(active)ok=tp_code_page_current(&page.page,page.patched);
    else {ok=tp_entry_branch_publish(&page,false);if(ok)atomic_store_explicit(&installed,true,memory_order_release);}
    atomic_flag_clear_explicit(&activating,memory_order_release);return ok;
}
bool tp_art_entry_writer_installed(void){return atomic_load_explicit(&installed,memory_order_acquire);}
bool tp_art_entry_writer_invalidate_compiled_callers(void){
    if(!atomic_load_explicit(&installed,memory_order_acquire)||*(uintptr_t *)runtime_slot!=bound_runtime
        ||*(uintptr_t *)(bound_runtime+0x298)!=bound_jit)return false;
    if(!bound_jit)return true;
    if(*(void **)(bound_jit+8)!=bound_cache)return false;
    invalidate_cache(bound_cache);return true;
}
const struct tp_near_entry_leaf *tp_art_entry_writer_leaf(void){return atomic_load_explicit(&prepared,memory_order_acquire)?&leaf:NULL;}
const struct tp_entry_branch_page *tp_art_entry_writer_page(void){return atomic_load_explicit(&prepared,memory_order_acquire)?&page:NULL;}
