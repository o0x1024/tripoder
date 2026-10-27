#define _GNU_SOURCE
#include "ptrace_namespace.h"
#include "kernel_syscall.h"
#include "syscall_namespace.h"
#include "user_memory.h"
#include <errno.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* A ptrace signal-delivery stop precedes every userspace signal chain. Runtime
 * syscall traps therefore require the same ownership boundary here as in ART.
 * Never consume a user signal, a guest filter's trap, or a ptrace event. */
enum { TRACE_CAPACITY=256 };
static struct resume_state {_Atomic uint64_t key;_Atomic long request;} resumes[TRACE_CAPACITY];
static uint64_t trace_key(long target){return ((uint64_t)(uint32_t)tp_kernel_syscall(SYS_gettid)<<32)|(uint32_t)target;}
static struct resume_state *state(long target,bool create,bool *fresh){
    uint64_t key=trace_key(target);*fresh=false;
    for(unsigned i=0;i<TRACE_CAPACITY;i++)if(atomic_load_explicit(&resumes[i].key,memory_order_acquire)==key)return &resumes[i];
    if(create)for(unsigned i=0;i<TRACE_CAPACITY;i++){
        uint64_t empty=0;if(atomic_compare_exchange_strong_explicit(&resumes[i].key,&empty,key,memory_order_acq_rel,memory_order_acquire)){*fresh=true;return &resumes[i];}
    }
    return NULL;
}
long tp_namespace_ptrace(long request,long target,long address,long data){
    bool fresh=false;struct resume_state *entry=NULL;long before=0;
    if(request==PTRACE_CONT||request==PTRACE_SYSCALL||request==PTRACE_SINGLESTEP){
        entry=state(target,true,&fresh);if(!entry){errno=ENOSPC;return -1;}
        before=atomic_exchange_explicit(&entry->request,request,memory_order_acq_rel);
    }
    long result=tp_kernel_syscall(SYS_ptrace,request,target,address,data);
    if(result<0&&entry){atomic_store_explicit(&entry->request,before,memory_order_release);if(fresh)atomic_store_explicit(&entry->key,0,memory_order_release);}
    if(result==0&&request==PTRACE_DETACH){entry=state(target,false,&fresh);if(entry)atomic_store_explicit(&entry->key,0,memory_order_release);}
    return result;
}
static int runtime_stop(long target,int status){
    if(!WIFSTOPPED(status)||WSTOPSIG(status)!=SIGSYS||(unsigned)status>>16)return 0;
    siginfo_t info={0};
    if(tp_kernel_syscall(SYS_ptrace,PTRACE_GETSIGINFO,target,0,&info)<0)return 0;
    if(info.si_signo!=SIGSYS||info.si_code!=SYS_SECCOMP||info.si_errno!=TP_NAMESPACE_TRAP_DATA)return 0;
    bool fresh;struct resume_state *entry=state(target,false,&fresh);
    /* CONT is a running tracee, with no instruction/syscall stop pending.
     * Other trace modes require an execution boundary this backend cannot
     * reconstruct. Report that limit instead of silently losing the mode. */
    if(!entry||atomic_load_explicit(&entry->request,memory_order_acquire)!=PTRACE_CONT){errno=EOPNOTSUPP;return -1;}
    if(tp_kernel_syscall(SYS_ptrace,PTRACE_CONT,target,0,SIGSYS)<0)return -1;
    int fd=tp_syscall_namespace_get_audit_fd();
    if(fd>=0){char line[160];int n=snprintf(line,sizeof(line),"RUNTIME_TRACE_STOP tracer=%ld tracee=%ld nr=%d tag=%x\n",tp_kernel_syscall(SYS_gettid),target,info.si_syscall,info.si_errno);if(n>0&&(size_t)n<sizeof(line))tp_kernel_syscall(SYS_write,fd,line,(size_t)n);}
    return 1;
}
long tp_namespace_wait4(long target,void *status,long options,void *usage){
    for(;;){
        int observed=0;struct rusage measured;
        long result=tp_kernel_syscall(SYS_wait4,target,&observed,options,usage?&measured:NULL);
        if(result<=0)return result;
        int internal=runtime_stop(result,observed);if(internal<0)return -1;if(internal)continue;
        if(status&&tp_user_write(status,&observed,sizeof(observed))<0)return -1;
        if(usage&&tp_user_write(usage,&measured,sizeof(measured))<0)return -1;
        if(WIFEXITED(observed)||WIFSIGNALED(observed)){bool fresh;struct resume_state *entry=state(result,false,&fresh);if(entry)atomic_store_explicit(&entry->key,0,memory_order_release);}
        return result;
    }
}
long tp_namespace_waitid(long kind,long target,void *information,long options,void *usage){
    for(;;){
        siginfo_t observed={0};struct rusage measured;
        long result=tp_kernel_syscall(SYS_waitid,kind,target,&observed,options,usage?&measured:NULL);
        if(result<0)return result;
        if(observed.si_pid>0&&observed.si_code==CLD_TRAPPED&&observed.si_status==SIGSYS){
            int internal=runtime_stop(observed.si_pid,(SIGSYS<<8)|0x7f);if(internal<0)return -1;if(internal)continue;
        }
        if(information&&tp_user_write(information,&observed,sizeof(observed))<0)return -1;
        if(usage&&observed.si_pid>0&&tp_user_write(usage,&measured,sizeof(measured))<0)return -1;
        if(observed.si_pid>0&&(observed.si_code==CLD_EXITED||observed.si_code==CLD_KILLED||observed.si_code==CLD_DUMPED)&&!(options&WNOWAIT)){
            bool fresh;struct resume_state *entry=state(observed.si_pid,false,&fresh);if(entry)atomic_store_explicit(&entry->key,0,memory_order_release);
        }
        return result;
    }
}
