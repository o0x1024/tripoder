#define _GNU_SOURCE
#include "../near_address.h"
#include "near_entry_leaf.h"
#include "entry_leaf_image.h"
#include <android/dlext.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <libunwind.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

static _Atomic unsigned attempted_leases;
static bool branch(uintptr_t pc,uintptr_t target,uint32_t *word){
    if((pc|target)&3u)return false;
    if(target>=pc){if(target-pc>=UINT64_C(0x8000000))return false;*word=0x14000000u|(uint32_t)((target-pc)/4);}
    else {if(pc-target>UINT64_C(0x8000000))return false;*word=0x14000000u|((0u-(uint32_t)((pc-target)/4))&0x03ffffffu);}
    return true;
}

struct module_check { uintptr_t base;bool found,header; };
static int visible(struct dl_phdr_info *info,size_t size,void *data){
    (void)size;struct module_check *check=data;if(info->dlpi_addr!=check->base)return 0;
    check->found=true;
    for(unsigned i=0;i<info->dlpi_phnum;i++)if(info->dlpi_phdr[i].p_type==PT_GNU_EH_FRAME)check->header=true;
    return 1;
}
static bool fdes(struct tp_near_entry_leaf *lease){
    // Query the actual loaded ELF metadata without stepping a synthetic stack.
    unw_context_t context;unw_cursor_t cursor;
    if(unw_getcontext(&context)||unw_init_local(&cursor,&context))return false;
    for(unsigned offset=0;offset<TP_LEAF_CODE_BYTES;offset+=4){
        unw_proc_info_t info={0};
        // A byte inside each range makes lookup independent of return-PC -1
        // conventions. Actual instruction PCs are exercised by the signal test.
        if(unw_set_reg(&cursor,UNW_REG_IP,lease->leaf+offset+1)||unw_get_proc_info(&cursor,&info)
            ||info.start_ip!=lease->leaf||info.end_ip!=lease->leaf+TP_LEAF_CODE_BYTES)return false;
        lease->fde_pcs++;
    }
    lease->fde_ranges=1;
    return true;
}
bool tp_near_entry_leaf_prepare(uintptr_t resume,struct tp_entry_owner_registry *registry,struct tp_near_entry_leaf *lease){
    if(!lease||lease->handle||!registry||(resume&3u)||!resume)return false;
    *lease=(struct tp_near_entry_leaf){.fd=-1,.stage="NEAR_RESERVATION"};
    unsigned attempt=atomic_load_explicit(&attempted_leases,memory_order_relaxed);
    do {if(attempt==16){lease->stage="LEASE_BUDGET";return false;}}
    while(!atomic_compare_exchange_weak_explicit(&attempted_leases,&attempt,attempt+1,memory_order_relaxed,memory_order_relaxed));
    void *reservation=tp_near_address_reserve(resume,TP_LEAF_LOAD_BYTES,PROT_NONE);if(reservation==MAP_FAILED){lease->error=errno;return false;}
    lease->base=(uintptr_t)reservation;lease->leaf=lease->base+TP_LEAF_CODE_RVA;lease->resume=resume;
    lease->post_offset=TP_LEAF_POST_OFFSET;lease->bytes=TP_LEAF_LOAD_BYTES;
    lease->stage="SEALED_ELF_FILE";
    unsigned char *image=malloc(sizeof(tp_leaf_elf));
    if(!image)goto failed;
    memcpy(image,tp_leaf_elf,sizeof(tp_leaf_elf));uint32_t back;
    if(!branch(lease->base+TP_LEAF_BACK_RVA,resume,&back)){free(image);goto failed;}
    memcpy(image+TP_LEAF_BACK_FILE,&back,4);uintptr_t owners=(uintptr_t)registry;
    memcpy(image+TP_LEAF_REGISTRY_FILE,&owners,8);
    lease->fd=(int)syscall(SYS_memfd_create,"tripoder-art-owner-leaf",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if(lease->fd<0){free(image);goto failed;}
    size_t offset=0;
    while(offset<sizeof(tp_leaf_elf)){
        ssize_t written=write(lease->fd,image+offset,sizeof(tp_leaf_elf)-offset);
        if(written<0&&errno==EINTR)continue;if(written<=0){free(image);goto failed;}offset+=(size_t)written;
    }
    int seals=F_SEAL_SEAL|F_SEAL_SHRINK|F_SEAL_GROW|F_SEAL_WRITE;
    if(fcntl(lease->fd,F_ADD_SEALS,seals)||fcntl(lease->fd,F_GET_SEALS)!=seals){free(image);goto failed;}
    lease->seals=(unsigned)seals;
    android_dlextinfo ext={.flags=ANDROID_DLEXT_USE_LIBRARY_FD|ANDROID_DLEXT_RESERVED_ADDRESS,
        .reserved_addr=reservation,.reserved_size=TP_LEAF_LOAD_BYTES,.library_fd=lease->fd};
    lease->stage="ELF_LOAD";
    lease->handle=android_dlopen_ext("tripoder-art-owner-leaf",RTLD_NOW|RTLD_LOCAL,&ext);
    if(!lease->handle){const char *reason=dlerror();if(reason)snprintf(lease->detail,sizeof(lease->detail),"%s",reason);free(image);goto failed_after_loader;}
    // After loading, even a rejected lease remains mapped until process exit.
    // Nothing has published a branch to it, so rejection cannot run partial code.
    struct module_check check={.base=lease->base};dl_iterate_phdr(visible,&check);
    lease->stage="ELF_IDENTITY_AND_FDE";
    bool ok=check.found&&check.header&&fcntl(lease->fd,F_GET_SEALS)==seals
        &&memcmp((void *)lease->leaf,image+TP_LEAF_CODE_FILE,TP_LEAF_CODE_BYTES)==0
        &&memcmp((void *)(lease->base+TP_LEAF_REGISTRY_RVA),&owners,8)==0&&fdes(lease);
    free(image);if(!ok){lease->error=EINVAL;return false;}
    lease->stage="PREPARED";return true;
failed:
    lease->error=errno;
    if(lease->fd>=0){close(lease->fd);lease->fd=-1;}
    munmap(reservation,TP_LEAF_LOAD_BYTES);
    return false;
failed_after_loader:
    // A failing loader may have consumed/unmapped the reservation. Do not
    // unmap an address range whose ownership is no longer ours to infer.
    lease->error=errno;if(lease->fd>=0){close(lease->fd);lease->fd=-1;}return false;
}
