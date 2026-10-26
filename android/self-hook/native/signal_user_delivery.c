#define _GNU_SOURCE
#include "signal_user_delivery.h"
#include "tripoder_thread_park.h"
#include "park_syscall.h"
#include "art_access.h"
#include "code_page.h"
#include <elf.h>
#include <signal.h>
#include <stdatomic.h>
#include <string.h>

/* A fixed ART delivery role, not a user native-hook target. No RX mutation,
 * Java callback, changed declared sigaction mask, or alternate syscall policy. */
enum { POINTER_RVA=0x11678, CALL_RVA=0x6fb8, RETURN_RVA=0x6fd0 };
static const uint8_t build_id[]={0x53,0xec,0xd3,0xed,0xb9,0x21,0x7c,0x4d,0x3e,0x63,0x8c,0x25,0x54,0x55,0x25,0x19};
static const uint32_t call_image[]={0xf0000048,0xd10043a1,0x52800040,0xf9433d08,0xaa1f03e2,0xd63f0100};
static _Atomic bool attempted,active;
static _Atomic uintptr_t owner,original;
static _Atomic unsigned outcome=TP_SIGNAL_DELIVERY_STATE;
static _Atomic uint64_t role_calls,other_calls;
typedef int (*mask_function)(int,const sigset64_t *,sigset64_t *);
_Static_assert(ATOMIC_LLONG_LOCK_FREE==2,"signal callback atomics are lock free");
_Static_assert(sizeof(sigset64_t)==8,"pinned ART mask ABI is 64 bits");

__attribute__((noinline)) static int user_mask(int how,const sigset64_t *set,sigset64_t *old){
    mask_function real=(mask_function)atomic_load_explicit(&original,memory_order_acquire);
    uintptr_t caller=(uintptr_t)__builtin_return_address(0);
    if(caller==atomic_load_explicit(&owner,memory_order_acquire)+RETURN_RVA&&how==SIG_SETMASK&&set&&old==NULL){
        uint64_t bits;memcpy(&bits,set,sizeof(bits));bits&=~(UINT64_C(1)<<(SIGSYS-1));
        sigset64_t physical;memcpy(&physical,&bits,sizeof(physical));
        atomic_fetch_add_explicit(&role_calls,1,memory_order_relaxed);
        return real(how,&physical,old);
    }
    atomic_fetch_add_explicit(&other_calls,1,memory_order_relaxed);return real(how,set,old);
}
static bool contains(const Elf64_Phdr *p,uintptr_t start,size_t size){
    return p->p_vaddr<=start&&size<=p->p_memsz&&start-p->p_vaddr<=p->p_memsz-size;
}
static bool image(uintptr_t base,uintptr_t libc,uintptr_t real_mask){
    if(!base||!tp_art_readable(base,sizeof(Elf64_Ehdr)))return false;
    const Elf64_Ehdr *h=(const void *)base;
    if(memcmp(h->e_ident,ELFMAG,SELFMAG)||h->e_ident[EI_CLASS]!=ELFCLASS64||h->e_machine!=EM_AARCH64||h->e_phentsize!=sizeof(Elf64_Phdr)
        ||!h->e_phnum||h->e_phnum>32||h->e_phoff>65536||!tp_art_readable(base+h->e_phoff,h->e_phnum*sizeof(Elf64_Phdr)))return false;
    const Elf64_Phdr *headers=(const void *)(base+h->e_phoff);bool identity=false,code=false,data=false;
    for(unsigned i=0;i<h->e_phnum;i++){
        const Elf64_Phdr *p=&headers[i];
        if(p->p_type==PT_LOAD){
            if((p->p_flags&(PF_R|PF_W|PF_X))==(PF_R|PF_X)&&contains(p,CALL_RVA,sizeof(call_image)))code=true;
            if((p->p_flags&(PF_R|PF_W|PF_X))==(PF_R|PF_W)&&p->p_filesz==0&&contains(p,POINTER_RVA,sizeof(uintptr_t)))data=true;
        }
        if(p->p_type!=PT_NOTE||p->p_memsz>65536||!tp_art_readable(base+p->p_vaddr,(unsigned)p->p_memsz))continue;
        const uint8_t *at=(const void *)(base+p->p_vaddr);size_t left=p->p_memsz;
        while(left>=sizeof(Elf64_Nhdr)){
            Elf64_Nhdr n;memcpy(&n,at,sizeof(n));size_t names=((size_t)n.n_namesz+3)&~(size_t)3,desc=((size_t)n.n_descsz+3)&~(size_t)3;
            if(names>left-sizeof(n)||desc>left-sizeof(n)-names)break;
            if(n.n_type==NT_GNU_BUILD_ID&&n.n_namesz==4&&n.n_descsz==sizeof(build_id)&&!memcmp(at+sizeof(n),"GNU",4)
                &&!memcmp(at+sizeof(n)+names,build_id,sizeof(build_id)))identity=true;
            size_t used=sizeof(n)+names+desc;at+=used;left-=used;
        }
    }
    struct tp_page_identity mapping;
    // The caller supplies the already validated fixed-libc owner. These two
    // immutable GOT targets close the successful mask-function callee set.
    return identity&&code&&data&&libc&&real_mask==libc+0x948e8
        &&tp_art_readable(libc+0xfb0d0,sizeof(uintptr_t))&&tp_art_readable(libc+0xfb140,sizeof(uintptr_t))
        &&*(const uintptr_t *)(libc+0xfb0d0)==libc+0x93a50&&*(const uintptr_t *)(libc+0xfb140)==libc+0x93be0
        &&tp_art_readable(base+0x10010,sizeof(uint64_t))&&atomic_load_explicit((_Atomic uint64_t *)(base+0x10010),memory_order_acquire)==UINT64_MAX
        &&tp_code_page_identity((void *)((base+POINTER_RVA)&~(uintptr_t)4095),"rw-p",&mapping)
        &&mapping.inode==0&&!memcmp((void *)(base+CALL_RVA),call_image,sizeof(call_image));
}
static bool libc_in_flight(uintptr_t base,uintptr_t pc){
    static const uintptr_t ranges[][2]={{0x948e8,0x94a20},{0x93a50,0x93aac},{0x93be0,0x93c3c},{0xe1900,0xe1918}};
    for(unsigned i=0;i<sizeof(ranges)/sizeof(ranges[0]);i++)if(pc>=base+ranges[i][0]&&pc<base+ranges[i][1])return true;
    return false;
}
enum tp_signal_delivery_status tp_signal_user_delivery_reserve(uintptr_t base,uintptr_t libc,uintptr_t real_mask){
    bool expected=false;if(!atomic_compare_exchange_strong(&attempted,&expected,true))return TP_SIGNAL_DELIVERY_STATE;
    enum tp_signal_delivery_status status=TP_SIGNAL_DELIVERY_IMAGE;
    if(!real_mask||!image(base,libc,real_mask))goto done;
    _Atomic uintptr_t *slot=(_Atomic uintptr_t *)(base+POINTER_RVA);
    status=TP_SIGNAL_DELIVERY_SLOT;if(atomic_load_explicit(slot,memory_order_acquire)!=real_mask)goto done;
    uint64_t mask=0;status=TP_SIGNAL_DELIVERY_MASK;
    if(tp_park_syscall(SYS_rt_sigprocmask,SIG_SETMASK,0,(long)&mask,8,0,0)||mask&(UINT64_C(1)<<(SIGSYS-1)))goto done;
    atomic_store_explicit(&owner,base,memory_order_release);atomic_store_explicit(&original,real_mask,memory_order_release);
    struct tp_park_snapshot pause;status=TP_SIGNAL_DELIVERY_PARK;
    if(tp_park_begin(&pause)!=TP_PARK_OK)goto done;
    status=TP_SIGNAL_DELIVERY_PREIMAGE;
    if(memcmp((void *)(base+CALL_RVA),call_image,sizeof(call_image))
        ||atomic_load_explicit((_Atomic uint64_t *)(base+0x10010),memory_order_acquire)!=UINT64_MAX)goto resumed;
    mask=0;status=TP_SIGNAL_DELIVERY_MASK;
    if(tp_park_syscall(SYS_rt_sigprocmask,SIG_SETMASK,0,(long)&mask,8,0,0)||mask&(UINT64_C(1)<<(SIGSYS-1)))goto resumed;
    for(unsigned i=0;i<pause.observed;i++){
        struct tp_park_context context;
        if(tp_park_context(pause.epoch,i,&context)!=TP_PARK_OK){status=TP_SIGNAL_DELIVERY_PARK;goto resumed;}
        if(context.signal_mask&(UINT64_C(1)<<(SIGSYS-1))){status=TP_SIGNAL_DELIVERY_MASK;goto resumed;}
        if((context.pc>=base+0x6fc8&&context.pc<base+RETURN_RVA)||context.lr==base+RETURN_RVA||libc_in_flight(libc,context.pc)){
            status=TP_SIGNAL_DELIVERY_IN_FLIGHT;goto resumed;
        }
    }
    uintptr_t preimage=real_mask;status=TP_SIGNAL_DELIVERY_SLOT;
    if(!atomic_compare_exchange_strong_explicit(slot,&preimage,(uintptr_t)user_mask,memory_order_acq_rel,memory_order_acquire))goto resumed;
    if(atomic_load_explicit(slot,memory_order_acquire)!=(uintptr_t)user_mask){
        (void)tp_park_syscall(SYS_exit_group,197,0,0,0,0,0);__builtin_trap();
    }
    atomic_store_explicit(&active,true,memory_order_release);status=TP_SIGNAL_DELIVERY_OK;
resumed:
    if(tp_park_end(pause.epoch)!=TP_PARK_OK){(void)tp_park_syscall(SYS_exit_group,198,0,0,0,0,0);__builtin_trap();}
done:atomic_store_explicit(&outcome,status,memory_order_release);return status;
}
void tp_signal_user_delivery_state(bool *ready,unsigned *status,uint64_t *roles,uint64_t *other){
    *ready=atomic_load_explicit(&active,memory_order_acquire);*status=atomic_load_explicit(&outcome,memory_order_acquire);
    *roles=atomic_load_explicit(&role_calls,memory_order_relaxed);*other=atomic_load_explicit(&other_calls,memory_order_relaxed);
}
