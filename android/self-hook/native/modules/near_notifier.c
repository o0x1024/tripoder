#define _GNU_SOURCE
#include "../near_address.h"
#include "near_notifier.h"
#include "notifier_image.h"
#include <android/dlext.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <libunwind.h>
#include <link.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>


static bool fdes(struct tp_near_notifier *lease){
    unw_context_t context;unw_cursor_t cursor;
    if(unw_getcontext(&context)||unw_init_local(&cursor,&context))return false;
    for(unsigned offset=0;offset<TP_NOTIFIER_CODE_BYTES;offset+=4){
        unw_proc_info_t info={0};
        if(unw_set_reg(&cursor,UNW_REG_IP,lease->entry+offset+1)||unw_get_proc_info(&cursor,&info)
            ||info.start_ip!=lease->entry||info.end_ip!=lease->entry+TP_NOTIFIER_CODE_BYTES)return false;
        lease->fde_pcs++;
    }
    return true;
}
bool tp_near_notifier_prepare(uintptr_t origin,void (*callback)(void),struct tp_near_notifier *lease){
    if(!lease||lease->handle||!callback||!origin||(origin&3u))return false;
    *lease=(struct tp_near_notifier){.fd=-1,.stage="NOTIFIER_NEAR_RESERVATION"};
    void *reservation=tp_near_address_reserve(origin,TP_NOTIFIER_LOAD_BYTES,PROT_NONE);if(reservation==MAP_FAILED){lease->error=errno;return false;}
    lease->base=(uintptr_t)reservation;lease->entry=lease->base+TP_NOTIFIER_CODE_RVA;
    lease->stage="NOTIFIER_SEALED_ELF";
    unsigned char *image=malloc(sizeof(tp_notifier_elf));if(!image)goto failed;
    memcpy(image,tp_notifier_elf,sizeof(tp_notifier_elf));uintptr_t target=(uintptr_t)callback;
    memcpy(image+TP_NOTIFIER_CALLBACK_FILE,&target,8);
    lease->fd=(int)syscall(SYS_memfd_create,"tripoder-linker-notifier",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if(lease->fd<0){free(image);goto failed;}
    size_t offset=0;
    while(offset<sizeof(tp_notifier_elf)){
        ssize_t n=write(lease->fd,image+offset,sizeof(tp_notifier_elf)-offset);
        if(n<0&&errno==EINTR)continue;if(n<=0){free(image);goto failed;}offset+=(size_t)n;
    }
    int seals=F_SEAL_SEAL|F_SEAL_SHRINK|F_SEAL_GROW|F_SEAL_WRITE;
    if(fcntl(lease->fd,F_ADD_SEALS,seals)||fcntl(lease->fd,F_GET_SEALS)!=seals){free(image);goto failed;}
    lease->seals=(unsigned)seals;
    android_dlextinfo ext={.flags=ANDROID_DLEXT_USE_LIBRARY_FD|ANDROID_DLEXT_RESERVED_ADDRESS,
        .reserved_addr=reservation,.reserved_size=TP_NOTIFIER_LOAD_BYTES,.library_fd=lease->fd};
    lease->stage="NOTIFIER_ELF_LOAD";
    lease->handle=android_dlopen_ext("tripoder-linker-notifier",RTLD_NOW|RTLD_LOCAL,&ext);
    if(!lease->handle){free(image);lease->error=errno;close(lease->fd);lease->fd=-1;return false;}
    /* Loader may consume the reservation even on failure. Never reclaim an
     * inferred range. Loaded code/FDE remains leased until process exit. */
    lease->stage="NOTIFIER_IDENTITY_FDE";
    bool ok=fcntl(lease->fd,F_GET_SEALS)==seals
        &&memcmp((void *)lease->entry,image+TP_NOTIFIER_CODE_FILE,TP_NOTIFIER_CODE_BYTES)==0
        &&memcmp((void *)(lease->base+TP_NOTIFIER_CALLBACK_RVA),&target,8)==0&&fdes(lease);
    free(image);if(!ok){lease->error=EINVAL;return false;}
    lease->stage="PREPARED";return true;
failed:
    lease->error=errno;if(lease->fd>=0){close(lease->fd);lease->fd=-1;}
    munmap(reservation,TP_NOTIFIER_LOAD_BYTES);return false;
}
