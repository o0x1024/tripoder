#include "entry_branch_page.h"
#include "tripoder_thread_park.h"
#include "../park_syscall.h"
#include <string.h>

bool tp_entry_branch_prepare(struct tp_entry_branch_page *branch,uintptr_t entry,uintptr_t leaf,
    const char *file,const uint32_t image[static TP_CODE_PAGE_WORDS]){
    if(!branch||branch->prepared||!file||!image||!entry||!leaf||((entry|leaf)&3u)||(entry&4095)>4084)return false;
    unsigned offset=(unsigned)((entry&4095)/4);
    if(image[offset]!=0xd503233fu||image[offset+1]!=0xa9be7bfdu||image[offset+2]!=0xa9014ff4u)return false;
    uintptr_t pc=entry+8;uint32_t word;
    if(leaf>=pc){if(leaf-pc>=0x8000000u)return false;word=0x14000000u|(uint32_t)((leaf-pc)/4);}
    else {if(pc-leaf>0x8000000u)return false;word=0x14000000u|((0u-(uint32_t)((pc-leaf)/4))&0x03ffffffu);}
    if(!tp_code_page_claim(&branch->page,(void *)(entry&~(uintptr_t)4095),file,image))return false;
    memcpy(branch->patched,image,sizeof(branch->patched));branch->patched[offset+2]=word;
    branch->entry=entry;branch->leaf=leaf;branch->prepared=true;return true;
}
/* All other observed threads are parked. Only the audited direct syscall
 * commit/cache path and signal-release path may execute until end returns. */
__attribute__((noinline,visibility("hidden"))) int tp_entry_branch_commit_parked(struct tp_code_page_update *update,unsigned epoch){
    int result=tp_code_page_commit(update);
    if(tp_park_end(epoch)!=TP_PARK_OK){(void)tp_park_syscall(SYS_exit_group,195,0,0,0,0,0);__builtin_trap();}
    return result;
}
bool tp_entry_branch_publish(struct tp_entry_branch_page *branch,bool restore){
    if(!branch||!branch->prepared||branch->restored||(restore?!branch->active:branch->active))return false;
    struct tp_code_page_update update={0};
    int prepared=restore?tp_code_page_prepare_restore(&branch->page,branch->patched,&update):
        tp_code_page_prepare(&branch->page,branch->page.original_image,branch->patched,&update);
    if(prepared!=1)return false;
    struct tp_park_snapshot parked;
    enum tp_park_status status=tp_park_begin(&parked);branch->park_status=(int)status;
    if(status!=TP_PARK_OK){tp_code_page_cancel(&update);return false;}
    int changed=tp_entry_branch_commit_parked(&update,parked.epoch);
    if(!changed){tp_code_page_cancel(&update);return false;}
    tp_code_page_finish(&update);branch->commits++;branch->parked_threads=parked.parked;
    branch->active=!restore;branch->restored=restore;return true;
}
