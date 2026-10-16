#define _GNU_SOURCE
#include "../near_address.h"
#include "native_bridge.h"
#include "tripoder_hook_limits.h"
#include "native_entry_image.h"
#include <android/dlext.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <libunwind.h>
#include <linux/memfd.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
static _Atomic unsigned attempts;



static bool fdes(struct tp_native_bridge *lease){
    unw_context_t context;unw_cursor_t cursor;if(unw_getcontext(&context)||unw_init_local(&cursor,&context))return false;
    uintptr_t entries[2]={lease->relay,lease->original};
    for(unsigned entry=0;entry<2;entry++)for(unsigned offset=0;offset<16;offset+=4){
        unw_proc_info_t info={0};
        if(unw_set_reg(&cursor,UNW_REG_IP,entries[entry]+offset+1)||unw_get_proc_info(&cursor,&info)
            ||info.start_ip!=entries[entry]||info.end_ip!=entries[entry]+16)return false;
        lease->fde_pcs++;
    }
    return true;
}
bool tp_native_bridge_prepare(const uint32_t *input,size_t available,uintptr_t entry,void (*callback)(void),struct tp_native_bridge *lease){
    if(!input||!lease||lease->handle||!callback||!entry||(entry&3u))return false;
    *lease=(struct tp_native_bridge){.fd=-1,.stage="NATIVE_NEAR_RESERVATION"};
    uintptr_t low,high;
    enum tp_a64_status address_status=tp_native_address_range(input,available,entry,TP_NATIVE_RELAY_RVA,TP_NATIVE_ORIGINAL_RVA,TP_NATIVE_LOAD_BYTES,&low,&high);
    if(address_status!=TP_A64_OK){lease->stage="NATIVE_ADDRESS_CONSTRAINT";lease->error=address_status;return false;}
    unsigned number=atomic_fetch_add_explicit(&attempts,1,memory_order_relaxed);if(number>=TP_NATIVE_ENTRY_LIMIT){lease->stage="NATIVE_LEASE_BUDGET";return false;}
    void *reservation=tp_near_address_reserve_range(low,high,entry,TP_NATIVE_LOAD_BYTES,PROT_NONE);if(reservation==MAP_FAILED){lease->error=errno;return false;}
    lease->base=(uintptr_t)reservation;lease->relay=lease->base+TP_NATIVE_RELAY_RVA;lease->original=lease->base+TP_NATIVE_ORIGINAL_RVA;
    lease->stage="NATIVE_PREFIX_PLAN";
    if(tp_native_prefix_plan(input,available,entry,lease->relay,lease->original,&lease->plan)!=TP_A64_OK)goto failed;
    lease->stage="NATIVE_SEALED_CODE_AND_FDE";
    unsigned char *image=malloc(sizeof(tp_native_entry_elf));if(!image)goto failed;
    memcpy(image,tp_native_entry_elf,sizeof(tp_native_entry_elf));uintptr_t target=(uintptr_t)callback;
    memcpy(image+TP_NATIVE_CALLBACK_FILE,&target,8);memcpy(image+TP_NATIVE_ORIGINAL_FILE,lease->plan.original,16);
    uint32_t auth=lease->plan.entry_signed?0xd50323bfu:TP_A64_NOP;memcpy(image+TP_NATIVE_AUTH_FILE,&auth,4);
    image[TP_NATIVE_RELAY_RA_START_FILE]=lease->plan.entry_signed?0x2d:0;image[TP_NATIVE_RELAY_RA_END_FILE]=lease->plan.entry_signed?0x2d:0;
    image[TP_NATIVE_EARLY_RA_STATE_FILE]=lease->plan.entry_signed?0x2d:0;
    image[TP_NATIVE_RA_STATE_FILE]=!lease->plan.entry_signed&&lease->plan.signed_ra?0x2d:0;
    image[TP_NATIVE_FRAME_FILE]=(unsigned char)(0x80|(lease->plan.frame&127u));image[TP_NATIVE_FRAME_FILE+1]=(unsigned char)(lease->plan.frame>>7);
    const unsigned saved_fields[2]={TP_NATIVE_SAVED1_FILE,TP_NATIVE_SAVED2_FILE};
    for(unsigned i=0;i<2;i++)if(lease->plan.saved[i].present){
        unsigned char *at=image+saved_fields[i];at[0]=0x05;at[1]=lease->plan.saved[i].reg;at[2]=lease->plan.saved[i].offset;
    }
    char name[96];snprintf(name,sizeof(name),"tripoder-native-entry-%u",number);
    lease->fd=(int)syscall(SYS_memfd_create,name,MFD_CLOEXEC|MFD_ALLOW_SEALING);if(lease->fd<0){free(image);goto failed;}
    size_t offset=0;
    while(offset<sizeof(tp_native_entry_elf)){
        ssize_t n=write(lease->fd,image+offset,sizeof(tp_native_entry_elf)-offset);
        if(n<0&&errno==EINTR)continue;if(n<=0){free(image);goto failed;}offset+=(size_t)n;
    }
    int seals=F_SEAL_SEAL|F_SEAL_SHRINK|F_SEAL_GROW|F_SEAL_WRITE;
    if(fcntl(lease->fd,F_ADD_SEALS,seals)||fcntl(lease->fd,F_GET_SEALS)!=seals){free(image);goto failed;}
    lease->seals=(unsigned)seals;
    android_dlextinfo ext={.flags=ANDROID_DLEXT_USE_LIBRARY_FD|ANDROID_DLEXT_RESERVED_ADDRESS,
        .reserved_addr=reservation,.reserved_size=TP_NATIVE_LOAD_BYTES,.library_fd=lease->fd};
    lease->stage="NATIVE_ELF_LOAD";lease->handle=android_dlopen_ext(name,RTLD_NOW|RTLD_LOCAL,&ext);
    if(!lease->handle){free(image);goto failed;}
    // Loaded code and FDE leases remain even if validation rejects. No branch
    // has been published; never dlclose code that could hold a late callback.
    lease->stage="NATIVE_IDENTITY_FDE";
    bool ok=fcntl(lease->fd,F_GET_SEALS)==seals&&memcmp((void *)lease->relay,image+TP_NATIVE_RELAY_FILE,16)==0
        &&memcmp((void *)lease->original,image+TP_NATIVE_ORIGINAL_FILE,16)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_CALLBACK_RVA),&target,8)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_RA_STATE_RVA),image+TP_NATIVE_RA_STATE_FILE,1)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_EARLY_RA_STATE_RVA),image+TP_NATIVE_EARLY_RA_STATE_FILE,1)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_RELAY_RA_START_RVA),image+TP_NATIVE_RELAY_RA_START_FILE,1)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_RELAY_RA_END_RVA),image+TP_NATIVE_RELAY_RA_END_FILE,1)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_FRAME_RVA),image+TP_NATIVE_FRAME_FILE,2)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_SAVED1_RVA),image+TP_NATIVE_SAVED1_FILE,3)==0
        &&memcmp((void *)(lease->base+TP_NATIVE_SAVED2_RVA),image+TP_NATIVE_SAVED2_FILE,3)==0&&fdes(lease);
    free(image);if(!ok){lease->error=EINVAL;return false;}lease->stage="PREPARED";return true;
failed:
    lease->error=errno;if(lease->fd>=0){close(lease->fd);lease->fd=-1;}
    munmap(reservation,TP_NATIVE_LOAD_BYTES);return false;
}
