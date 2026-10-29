#define _GNU_SOURCE
#include "signal_chain.h"
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <setjmp.h>
#include <ucontext.h>

/* A guest loader has its own native namespace. Its versioned libc signal
 * imports must still participate in ART's process-wide implicit-fault chain. */
static struct entry {const char *name,*target;uintptr_t original,replacement;} entries[]={
    {"sigaction","sigaction",0,0},{"sigaction64","sigaction64",0,0},
    {"signal","signal",0,0},
    {"sigprocmask","sigprocmask",0,0},{"sigprocmask64","sigprocmask64",0,0}};
struct special_action {bool (*handler)(int,siginfo_t *,void *);uint64_t mask;unsigned long flags;};
_Static_assert(sizeof(struct special_action)==24,"ART sigchain special-action ABI");
static void (*add_special)(int,const struct special_action *);
static uintptr_t platform_base,platform_libc;
static _Atomic uint64_t claims;
static _Atomic bool ready;static _Atomic uint64_t patched,checked;
static bool range(size_t offset,size_t bytes,size_t size){return offset<=size&&bytes<=size-offset;}
static uintptr_t base_of(const char *path){
    FILE *maps=fopen("/proc/self/maps","r");if(!maps)return 0;char line[4096],name[1024];uintptr_t result=0;
    while(fgets(line,sizeof(line),maps)){unsigned long first,last,offset;char mode[5];name[0]=0;
        if(sscanf(line,"%lx-%lx %4s %lx %*s %*s %1023s",&first,&last,mode,&offset,name)==5&&!offset&&!strcmp(name,path)){result=first;break;}}
    fclose(maps);return result;
}
static uintptr_t export_at(const char *path,uintptr_t base,const char *name){
    int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return 0;struct stat stat;if(fstat(fd,&stat)||stat.st_size<(off_t)sizeof(Elf64_Ehdr)||stat.st_size>8*1024*1024){close(fd);return 0;}
    size_t size=(size_t)stat.st_size;const unsigned char *bytes=mmap(NULL,size,PROT_READ,MAP_PRIVATE,fd,0);close(fd);if(bytes==MAP_FAILED)return 0;
    uintptr_t result=0;const Elf64_Ehdr *header=(const void *)bytes;
    if(memcmp(header->e_ident,ELFMAG,SELFMAG)||header->e_ident[EI_CLASS]!=ELFCLASS64||header->e_machine!=EM_AARCH64||header->e_shentsize!=sizeof(Elf64_Shdr)||header->e_shnum>4096||!range(header->e_shoff,(size_t)header->e_shnum*sizeof(Elf64_Shdr),size))goto done;
    const Elf64_Shdr *sections=(const void *)(bytes+header->e_shoff);size_t length=strlen(name);
    for(unsigned i=0;i<header->e_shnum&&!result;i++)if(sections[i].sh_type==SHT_DYNSYM&&sections[i].sh_entsize==sizeof(Elf64_Sym)&&sections[i].sh_link<header->e_shnum){
        const Elf64_Shdr *strings=&sections[sections[i].sh_link];if(!range(sections[i].sh_offset,sections[i].sh_size,size)||!range(strings->sh_offset,strings->sh_size,size))goto done;
        const Elf64_Sym *symbols=(const void *)(bytes+sections[i].sh_offset);const char *names=(const void *)(bytes+strings->sh_offset);
        for(size_t j=0;j<sections[i].sh_size/sizeof(Elf64_Sym);j++)if(symbols[j].st_shndx!=SHN_UNDEF&&ELF64_ST_TYPE(symbols[j].st_info)==STT_FUNC&&symbols[j].st_value&&symbols[j].st_name<strings->sh_size&&length<strings->sh_size-symbols[j].st_name&&!memcmp(names+symbols[j].st_name,name,length)&&!names[symbols[j].st_name+length]){result=base+symbols[j].st_value;break;}
    }
 done:munmap((void *)bytes,size);return result;
}
static bool safe_probe_recover(int signal,siginfo_t *info,void *context){
    if((signal!=SIGSEGV&&signal!=SIGBUS)||!info||!context)return false;
    ucontext_t *uc=(ucontext_t *)context;
    uintptr_t pc=(uintptr_t)uc->uc_mcontext.pc,sp=(uintptr_t)uc->uc_mcontext.sp;
    uintptr_t lr=(uintptr_t)uc->uc_mcontext.regs[30];
    /* ByteHook ARM64 safe GOT write probe:
     *   pc - 8: 0x910003e1 (mov x1, sp)
     *   pc - 4: 0x2a1903e0 (mov w0, w25)
     *   pc:     0xc89ffe97 (stlr x23, [x20])
     * ByteHook arms this probe with sigsetjmp(sp + 8, 1). On modern kernels (e.g. mseal GNU_RELRO),
     * write fails with SEGV_ACCERR. Without our special handler recovery, Sentry intercepts the
     * signal and aborts the process instead of letting ByteHook recover cleanly via siglongjmp. */
    if((pc&3)==0&&pc>=0x10000&&sp>=0x10000){
        const uint32_t *p=(const uint32_t *)pc;
        if(p[0]==0xc89ffe97&&p[-1]==0x2a1903e0&&p[-2]==0x910003e1){
            siglongjmp((void *)(sp+8),1);
        }
    }
    static int (*log_print)(int, const char*, const char*, ...) = NULL;
    if(!log_print){
        void *h = dlopen("liblog.so", RTLD_NOW);
        if(h) log_print = dlsym(h, "__android_log_print");
    }
    if(log_print){
        Dl_info pc_info = {0}, lr_info = {0};
        const char *pc_mod = dladdr((void *)pc, &pc_info) && pc_info.dli_fname ? pc_info.dli_fname : "unknown";
        uintptr_t pc_off = pc_info.dli_fbase ? (pc - (uintptr_t)pc_info.dli_fbase) : pc;
        const char *lr_mod = dladdr((void *)lr, &lr_info) && lr_info.dli_fname ? lr_info.dli_fname : "unknown";
        uintptr_t lr_off = lr_info.dli_fbase ? (lr - (uintptr_t)lr_info.dli_fbase) : lr;
        log_print(6, "TripoderCrash", "UNHANDLED SIGNAL %d (code %d) at addr=%p pc=%lx [%s+0x%lx] lr=%lx [%s+0x%lx] sp=%lx",
                  signal, info->si_code, info->si_addr, pc, pc_mod, pc_off, lr, lr_mod, lr_off, sp);
    }
    return false;
}
bool tp_signal_chain_prepare(void){
    if(atomic_load_explicit(&ready,memory_order_acquire))return true;
    const char *chain="/apex/com.android.art/lib64/libsigchain.so",*libc="/apex/com.android.runtime/lib64/bionic/libc.so";
    uintptr_t chain_base=base_of(chain),libc_base=base_of(libc);if(!chain_base||!libc_base)return false;
    for(unsigned i=0;i<sizeof(entries)/sizeof(entries[0]);i++){entries[i].original=export_at(libc,libc_base,entries[i].name);entries[i].replacement=export_at(chain,chain_base,entries[i].target);if(!entries[i].original||!entries[i].replacement)return false;}
    add_special=(void *)export_at(chain,chain_base,"AddSpecialSignalHandlerFn");if(!add_special)return false;
    platform_base=chain_base;platform_libc=libc_base;atomic_store_explicit(&ready,true,memory_order_release);
    tp_signal_chain_claim(SIGSEGV,safe_probe_recover);
    tp_signal_chain_claim(SIGBUS,safe_probe_recover);
    return true;
}
bool tp_signal_chain_platform(uintptr_t *base,uintptr_t *libc,uintptr_t *real_mask){
    if(!base||!libc||!real_mask||!atomic_load_explicit(&ready,memory_order_acquire))return false;
    *base=platform_base;*libc=platform_libc;*real_mask=entries[4].original;return *base&&*libc&&*real_mask;
}
int tp_signal_chain_user_action(int signal,const struct sigaction *action,struct sigaction *previous){
    if(!atomic_load_explicit(&ready,memory_order_acquire)){errno=ENOSYS;return -1;}
    int (*registration)(int,const struct sigaction *,struct sigaction *)=(void *)entries[0].replacement;
    return registration(signal,action,previous);
}
static long protect(uintptr_t page,int mode){
    register long x0 __asm__("x0")=(long)page,x1 __asm__("x1")=4096,x2 __asm__("x2")=mode,x8 __asm__("x8")=SYS_mprotect;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x8):"memory","cc");return x0;
}
static __attribute__((noinline)) uintptr_t signal_slot(uintptr_t base,const Elf64_Phdr *headers,unsigned count,const char *name){
    const Elf64_Dyn *dynamic=NULL;size_t limit=0;
    for(unsigned i=0;i<count;i++)if(headers[i].p_type==PT_DYNAMIC){dynamic=(const void *)(base+headers[i].p_vaddr);limit=headers[i].p_memsz/sizeof(*dynamic);break;}
    if(!dynamic||limit>65536)return 0;
    const Elf64_Sym *symbols=NULL;const volatile char *strings=NULL;size_t string_size=0,bytes[2]={0};const Elf64_Rela *tables[2]={0};
    for(size_t i=0;i<limit&&dynamic[i].d_tag!=DT_NULL;i++){
        uintptr_t value=dynamic[i].d_un.d_ptr;uintptr_t pointer=value>=base?value:base+value;
        switch(dynamic[i].d_tag){
            case DT_SYMTAB:symbols=(const void *)pointer;break;
            case DT_STRTAB:strings=(const void *)pointer;break;
            case DT_STRSZ:string_size=value;break;
            case DT_JMPREL:tables[0]=(const void *)pointer;break;
            case DT_PLTRELSZ:bytes[0]=value;break;
            case DT_RELA:tables[1]=(const void *)pointer;break;
            case DT_RELASZ:bytes[1]=value;break;
            default:break;
        }
    }
    if(!symbols||!strings||!string_size)return 0;
    for(unsigned table=0;table<2;table++)if(tables[table]&&bytes[table]<64*1024*1024){
        for(size_t i=0;i<bytes[table]/sizeof(Elf64_Rela);i++){
            const Elf64_Rela *r=&tables[table][i];unsigned type=ELF64_R_TYPE(r->r_info);if(type!=R_AARCH64_JUMP_SLOT&&type!=R_AARCH64_GLOB_DAT)continue;
            size_t offset=symbols[ELF64_R_SYM(r->r_info)].st_name;if(offset>=string_size)continue;
            size_t j=0;while(offset+j<string_size&&name[j]&&strings[offset+j]==name[j])j++;
            if(offset+j<string_size&&!name[j]&&!strings[offset+j])return base+r->r_offset;
        }
    }
    return 0;
}
bool tp_signal_chain_module_added(uintptr_t base){
    if(!atomic_load_explicit(&ready,memory_order_acquire))return true;
    const Elf64_Ehdr *header=(const void *)base;if(!base||memcmp(header->e_ident,ELFMAG,SELFMAG)||header->e_phentsize!=sizeof(Elf64_Phdr)||!header->e_phnum||header->e_phnum>1024||header->e_phoff>65536)return false;
    const Elf64_Phdr *headers=(const void *)(base+header->e_phoff);atomic_fetch_add_explicit(&checked,1,memory_order_relaxed);
    for(unsigned i=0;i<sizeof(entries)/sizeof(entries[0]);i++){
        uintptr_t slot=signal_slot(base,headers,header->e_phnum,entries[i].name);if(!slot)continue;
        _Atomic uintptr_t *target=(_Atomic uintptr_t *)slot;uintptr_t current=atomic_load_explicit(target,memory_order_acquire);if(current!=entries[i].original&&current!=entries[i].replacement)continue;
        uintptr_t replacement=entries[i].replacement;if(current==replacement)continue;
        uintptr_t page=slot&~(uintptr_t)4095;bool relro=false,writable=false;
        for(unsigned j=0;j<header->e_phnum;j++){const Elf64_Phdr *p=&headers[j];uintptr_t first=(base+p->p_vaddr)&~(uintptr_t)4095,last=(base+p->p_vaddr+p->p_memsz+4095)&~(uintptr_t)4095;
            if(page>=first&&page<last){if(p->p_type==PT_GNU_RELRO)relro=true;if(p->p_type==PT_LOAD&&(p->p_flags&PF_W))writable=true;}}
        if(!writable)return false;if(relro&&protect(page,PROT_READ|PROT_WRITE))return false;
        uintptr_t expected=current;bool changed=atomic_compare_exchange_strong_explicit(target,&expected,replacement,memory_order_acq_rel,memory_order_acquire);
        if(relro&&protect(page,PROT_READ))return false;if(changed)atomic_fetch_add_explicit(&patched,1,memory_order_relaxed);
    }return true;
}
void tp_signal_chain_state(bool *available,uint64_t *changes,uint64_t *modules){*available=atomic_load_explicit(&ready,memory_order_acquire);*changes=atomic_load_explicit(&patched,memory_order_relaxed);*modules=atomic_load_explicit(&checked,memory_order_relaxed);}

int tp_signal_chain_claim(int signal,bool (*handler)(int,siginfo_t *,void *)){
    if(!atomic_load_explicit(&ready,memory_order_acquire)||signal<=0||signal>=NSIG||signal==SIGKILL||signal==SIGSTOP||!handler)return -1;
    /* ART installs this mask before calling the special handler. An empty mask
     * delivers signals the interrupted thread deliberately blocked (for example
     * Android's pending SIGPIPE) before our callback even starts. Keep signals
     * blocked during native dispatch; sigchain restores the interrupted mask.
     * Internal syscalls use the owned gateway and need no recursive SIGSYS. */
    struct special_action action={handler,UINT64_MAX,0};
    add_special(signal,&action);atomic_fetch_add_explicit(&claims,1,memory_order_relaxed);return 0;
}
uint64_t tp_signal_chain_claims(void){return atomic_load_explicit(&claims,memory_order_relaxed);}
