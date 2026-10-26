#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "path_policy.h"
#include "syscall_namespace.h"
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

/* Self-owned stand-in for ART's special-handler/user-action separation. */
static bool (*special)(int,siginfo_t *,void *);
static struct sigaction user;
static volatile sig_atomic_t entered;
static int expected_syscall;
static void chain(int signal,siginfo_t *info,void *context){
    if(special&&special(signal,info,context))return;
    if(user.sa_flags&SA_SIGINFO&&user.sa_sigaction){user.sa_sigaction(signal,info,context);return;}
    _exit(91);
}
static int claim(int signal,bool (*handler)(int,siginfo_t *,void *)){
    special=handler;struct sigaction action={0};action.sa_sigaction=chain;action.sa_flags=SA_SIGINFO;return sigaction(signal,&action,NULL);
}
static int reserve(void){return 0;}
static int registration(int signal,const struct sigaction *action,struct sigaction *previous){
    if(signal!=SIGSYS){errno=EINVAL;return -1;}if(previous)*previous=user;if(action)user=*action;return 0;
}
static void guest(int signal,siginfo_t *info,void *raw){
    if(signal==SIGSYS&&info->si_syscall==expected_syscall){entered++;((ucontext_t *)raw)->uc_mcontext.regs[0]=77;}
}
static long direct(long nr,long a,long b,long c,long d){
    register long x0 __asm__("x0")=a,x1 __asm__("x1")=b,x2 __asm__("x2")=c,x3 __asm__("x3")=d,x8 __asm__("x8")=nr;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x8):"memory","cc");return x0;
}
static int scenario(const char *data,const char *artifacts,int number){
    pid_t child=fork();if(child<0)return 66;
    if(!child){
        expected_syscall=number;
        if(tp_configure(data,data,artifacts,artifacts,"dev.tripoder.ownedaction")||tp_syscall_namespace_install_with_signal_chain(claim,reserve,registration))_exit(67);
        struct {uintptr_t handler;unsigned long flags;uintptr_t restorer;uint64_t mask;} action={(uintptr_t)guest,SA_SIGINFO|SA_RESTART,0,UINT64_C(1)<<(SIGPIPE-1)},observed={0};
        if(direct(SYS_rt_sigaction,SIGSYS,(long)&action,0,8)||direct(SYS_rt_sigaction,SIGSYS,0,(long)&observed,8)||observed.handler!=action.handler||observed.mask!=action.mask)_exit(68);
        struct sock_filter filter[]={BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)number,0,3),BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0x5450,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(filter)/sizeof(filter[0]),filter};if(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program))_exit(69);
        long value=direct(number,0x5450,0,0,0);_exit(value==77&&entered==1?0:70);
    }
    int status;if(waitpid(child,&status,0)!=child)return 71;
    int code=WIFEXITED(status)?WEXITSTATUS(status):-1,signal=WIFSIGNALED(status)?WTERMSIG(status):0;bool passed=code==0&&signal==0;
    printf("{\"syscall\":%d,\"guestSeccompDelivery\":%s,\"childExit\":%d,\"childSignal\":%d,\"passed\":%s}",number,passed?"true":"false",code,signal,passed?"true":"false");return passed?0:1;
}
int main(int argc,char **argv){
    static const char prefix[]="/data/local/tmp/tripoder-owned-action-";
    if(argc!=2||strncmp(argv[1],prefix,sizeof(prefix)-1)||strlen(argv[1])>180)return 64;
    char data[256],artifacts[256];snprintf(data,sizeof(data),"%s/data",argv[1]);snprintf(artifacts,sizeof(artifacts),"%s/artifacts",argv[1]);
    if(mkdir(argv[1],0700)||mkdir(data,0700)||mkdir(artifacts,0700))return 65;
    printf("{\"directSigactionThroughClaimedChain\":true,\"scenarios\":[");
    int a=scenario(data,artifacts,SYS_getpgid);printf(",");int b=scenario(data,artifacts,SYS_openat);
    printf("],\"passed\":%s}\n",a||b?"false":"true");return a||b?1:0;
}
