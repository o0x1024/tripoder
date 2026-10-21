#define _GNU_SOURCE
#include "io_adapter.h"
#include "path_policy.h"
#include "syscall_namespace.h"
#include "kernel_syscall.h"
#include "user_memory.h"
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stdio.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#define syscall tp_kernel_syscall

/* Linker and protected libraries can issue SVC directly. Use the same path policy
 * as libc, once, before guest code. Only the owned internal syscall gateway bypasses
 * this dispatcher, so internal policy syscalls do not recurse through SIGSYS. */
static struct sigaction previous;
static const int numbers[]={SYS_openat,SYS_newfstatat,SYS_readlinkat,SYS_faccessat,SYS_unlinkat,SYS_mkdirat,SYS_renameat2,SYS_chdir,SYS_symlinkat,SYS_linkat,SYS_statfs,SYS_inotify_add_watch};
static _Atomic uint64_t observed[12];
struct audit_row { _Atomic bool busy;unsigned sequence;int number;long result;uintptr_t pc;char path[256]; };
static struct audit_row audit[64];static _Atomic unsigned audit_sequence;
static _Atomic bool auditing;
static void record(int number,long result,uintptr_t pc,const char *path){
    if(!atomic_load_explicit(&auditing,memory_order_relaxed))return;
    unsigned sequence=atomic_fetch_add_explicit(&audit_sequence,1,memory_order_relaxed)+1;struct audit_row *row=&audit[sequence%64];
    bool expected=false;if(!atomic_compare_exchange_strong_explicit(&row->busy,&expected,true,memory_order_acquire,memory_order_relaxed))return;
    row->number=number;row->result=result;row->pc=pc;
    row->path[0]=0;if(path){if(tp_user_string(path,row->path,sizeof(row->path))<0)row->path[sizeof(row->path)-1]=0;}
    row->sequence=sequence;atomic_store_explicit(&row->busy,false,memory_order_release);
}
static long metadata_path(int number,int descriptor,const char *name,void *output,unsigned mask){
    char safe_name[PATH_MAX];if(tp_user_string(name,safe_name,sizeof(safe_name))<0)return -1;name=safe_name;
    struct tp_path path;if(tp_resolve(AT_FDCWD,name,TP_METADATA,&path)<0)return -1;
    int fd=(int)syscall(SYS_openat,path.parent,path.leaf,O_PATH|O_CLOEXEC|(path.owned?O_NOFOLLOW:0),0);int opened_errno=errno;tp_release(&path);errno=opened_errno;if(fd<0)return -1;
    long result;if(number==SYS_statfs)result=syscall(SYS_fstatfs,fd,output);
    else{char alias[64];snprintf(alias,sizeof(alias),"/proc/self/fd/%d",fd);result=syscall(SYS_inotify_add_watch,descriptor,alias,mask);}
    int saved=errno;syscall(SYS_close,fd);errno=saved;return result;
}
static _Atomic bool installed;
static bool owned_request(int signal,const siginfo_t *info){
    if(signal!=SIGSYS||!info||info->si_code!=SYS_SECCOMP)return false;
    for(unsigned i=0;i<12;i++)if(info->si_syscall==numbers[i])return true;return false;
}
static void forward_signal(int signal,siginfo_t *info,void *raw){
        if((previous.sa_flags&SA_SIGINFO)&&previous.sa_sigaction)previous.sa_sigaction(signal,info,raw);
        else if(previous.sa_handler==SIG_IGN)return;
        else if(previous.sa_handler!=SIG_DFL&&previous.sa_handler)previous.sa_handler(signal);
        else{sigaction(SIGSYS,&previous,NULL);syscall(SYS_tgkill,syscall(SYS_getpid),syscall(SYS_gettid),SIGSYS);}
}
static long dispatch(int signal,siginfo_t *info,void *raw){
    (void)signal;
    ucontext_t *context=raw;const __u64 *args=context->uc_mcontext.regs;int saved=errno;long result;
    const char *name=(const char *)args[1];for(unsigned i=0;i<12;i++)if(info->si_syscall==numbers[i])atomic_fetch_add_explicit(&observed[i],1,memory_order_relaxed);
    switch(info->si_syscall){
        case SYS_openat:result=tp_openat((int)args[0],name,(int)args[2],(unsigned)args[3]);break;
        case SYS_newfstatat:result=tp_fstatat((int)args[0],name,(struct stat *)args[2],(int)args[3]);break;
        case SYS_readlinkat:result=tp_readlinkat((int)args[0],name,(char *)args[2],(size_t)args[3]);break;
        case SYS_faccessat:result=tp_faccessat((int)args[0],name,(int)args[2]);break;
        case SYS_unlinkat:result=tp_unlinkat((int)args[0],name,(int)args[2]);break;
        case SYS_mkdirat:result=tp_mkdirat((int)args[0],name,(unsigned)args[2]);break;
        case SYS_renameat2:result=tp_renameat2((int)args[0],name,(int)args[2],(const char *)args[3],(unsigned)args[4]);break;
        case SYS_chdir:name=(const char *)args[0];result=tp_chdir(name);break;
        case SYS_symlinkat:name=(const char *)args[2];result=tp_symlinkat((const char *)args[0],(int)args[1],name);break;
        case SYS_linkat:result=tp_linkat((int)args[0],name,(int)args[2],(const char *)args[3],(int)args[4]);break;
        case SYS_statfs:name=(const char *)args[0];result=metadata_path(SYS_statfs,0,name,(void *)args[1],0);break;
        default:result=metadata_path(SYS_inotify_add_watch,(int)args[0],name,NULL,(unsigned)args[2]);break;
    }
    int result_errno=errno;record(info->si_syscall,result<0?-result_errno:result,context->uc_mcontext.pc,name);errno=result_errno;
    context->uc_mcontext.regs[0]=(uint64_t)(result<0?-errno:result);errno=saved;
    return 0;
}
static void dispatch_entry(int signal,siginfo_t *info,void *raw){
    if(!owned_request(signal,info)){forward_signal(signal,info,raw);return;}
    int saved=errno;
    if(tp_native_stack_call((void *)dispatch,signal,(long)info,(long)raw)<0)((ucontext_t *)raw)->uc_mcontext.regs[0]=(uint64_t)-ENOMEM;
    errno=saved;
}
static bool dispatch_special(int signal,siginfo_t *info,void *raw){
    if(!owned_request(signal,info))return false;dispatch_entry(signal,info,raw);return true;
}
int tp_syscall_namespace_install_with_signal_chain(int (*claim)(int,bool (*)(int,siginfo_t *,void *))){
    if(atomic_load_explicit(&installed,memory_order_acquire))return 0;
    uintptr_t resume=(uintptr_t)tp_kernel_syscall_resume;
    struct sock_filter filter[32];unsigned length=0;
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4);
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(resume>>32),0,2);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer));
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)resume,13,0);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
    for(unsigned i=0;i<12;i++)filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)numbers[i],(uint8_t)(12-i),0);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP);
    struct sock_fprog program={.len=(unsigned short)length,.filter=filter};
    struct sigaction handler;memset(&handler,0,sizeof(handler));handler.sa_sigaction=dispatch_entry;handler.sa_flags=SA_SIGINFO;sigemptyset(&handler.sa_mask);
    if(claim?claim(SIGSYS,dispatch_special)!=0:sigaction(SIGSYS,&handler,&previous)!=0)return -1;
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)!=0||syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program)!=0){int saved=errno;if(!claim)sigaction(SIGSYS,&previous,NULL);errno=saved;return -1;}
    atomic_store_explicit(&installed,true,memory_order_release);return 0;
}
int tp_syscall_namespace_install(void){return tp_syscall_namespace_install_with_signal_chain(NULL);}
void tp_syscall_namespace_counts(uint64_t counts[4]){for(unsigned i=0;i<4;i++)counts[i]=atomic_load_explicit(&observed[i],memory_order_relaxed);}
void tp_syscall_namespace_enable_audit(void){atomic_store_explicit(&auditing,true,memory_order_release);}

void tp_syscall_namespace_dump(int fd){
    unsigned end=atomic_load_explicit(&audit_sequence,memory_order_acquire),begin=end>64?end-63:1;
    for(unsigned sequence=begin;sequence<=end;sequence++){struct audit_row *row=&audit[sequence%64];bool expected=false;if(!atomic_compare_exchange_strong_explicit(&row->busy,&expected,true,memory_order_acquire,memory_order_relaxed))continue;
        if(row->sequence!=sequence){atomic_store_explicit(&row->busy,false,memory_order_release);continue;}
        char line[400];int size=snprintf(line,sizeof(line),"syscall seq=%u nr=%d result=%ld pc=%lx path=%s\n",sequence,row->number,row->result,(unsigned long)row->pc,row->path);
        if(size>0&&(size_t)size<sizeof(line))syscall(SYS_write,fd,line,(size_t)size);atomic_store_explicit(&row->busy,false,memory_order_release);
    }
}
