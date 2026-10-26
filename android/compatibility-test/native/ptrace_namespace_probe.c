#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "path_policy.h"
#include "syscall_namespace.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stddef.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

/* Both tracer and tracee belong to this fixture. Never attaches to another app. */
static void guest_handler(int signal,siginfo_t *info,void *raw){
    if(signal==SIGSYS&&info->si_code==SYS_SECCOMP&&info->si_syscall==SYS_getpgid)((ucontext_t *)raw)->uc_mcontext.regs[0]=77;
}
int main(int argc,char **argv){
    static const char prefix[]="/data/local/tmp/tripoder-owned-ptrace-";
    if(argc!=2||strncmp(argv[1],prefix,sizeof(prefix)-1)||strlen(argv[1])>180)return 64;
    char data[256],artifacts[256],file[256];snprintf(data,sizeof(data),"%s/data",argv[1]);snprintf(artifacts,sizeof(artifacts),"%s/artifacts",argv[1]);snprintf(file,sizeof(file),"%s/data/owned",argv[1]);
    if(mkdir(argv[1],0700)||mkdir(data,0700)||mkdir(artifacts,0700))return 65;
    int fd=open(file,O_WRONLY|O_CREAT|O_EXCL,0600);if(fd<0||write(fd,"X",1)!=1)return 66;close(fd);
    if(tp_configure(data,data,artifacts,artifacts,"dev.tripoder.ownedptrace"))return 67;
    tp_syscall_namespace_trace_debugger(true);
    if(tp_syscall_namespace_install())return 68;
    pid_t child=fork();if(child<0)return 69;
    if(!child){
        if(ptrace(PTRACE_TRACEME,0,NULL,NULL)<0)_exit(70);raise(SIGSTOP);
        fd=open(file,O_RDONLY|O_CLOEXEC);char byte=0;bool valid=fd>=0&&read(fd,&byte,1)==1&&byte=='X';if(fd>=0)close(fd);
        struct sigaction action={0};action.sa_sigaction=guest_handler;action.sa_flags=SA_SIGINFO;if(sigaction(SIGSYS,&action,NULL))_exit(75);
        struct sock_filter filter[]={BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpgid,0,3),BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0xface,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(filter)/sizeof(filter[0]),filter};if(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program))_exit(76);
        valid&=syscall(SYS_getpgid,0xface)==77;
        raise(SIGUSR1);_exit(valid?0:71);
    }
    int status,internal=0,guest=0,guest_trap=0,unexpected=0,code=-1;
    if(waitpid(child,&status,0)!=child||!WIFSTOPPED(status)||WSTOPSIG(status)!=SIGSTOP)return 72;
    if(ptrace(PTRACE_CONT,child,NULL,NULL)<0)return 73;
    for(;;){
        siginfo_t peek={0};if(waitid(P_PID,child,&peek,WEXITED|WSTOPPED|WNOWAIT)!=0)return 77;
        if(waitpid(child,&status,0)!=child)return 78;
        if(WIFSTOPPED(status)&&(peek.si_code!=CLD_TRAPPED||peek.si_status!=WSTOPSIG(status)))return 79;
        if(WIFEXITED(status)){code=WEXITSTATUS(status);break;}if(WIFSIGNALED(status)){code=128+WTERMSIG(status);break;}
        if(!WIFSTOPPED(status)){unexpected++;break;}
        int signal=WSTOPSIG(status),deliver=signal;
        if(signal==SIGSYS){siginfo_t info={0};if(ptrace(PTRACE_GETSIGINFO,child,NULL,&info)==0&&info.si_code==SYS_SECCOMP){if(info.si_errno==0&&info.si_syscall==SYS_getpgid)guest_trap++;else internal++;}else unexpected++;}
        else if(signal==SIGUSR1){guest++;deliver=0;}else unexpected++;
        if(ptrace(PTRACE_CONT,child,NULL,(void *)(long)deliver)<0)return 74;
    }
    bool passed=code==0&&internal==0&&guest==1&&guest_trap==1&&unexpected==0;
    printf("{\"ownTraceeExit\":%d,\"wait4AndWaitidAgree\":true,\"runtimeSigsysStopsExposed\":%d,\"guestSignalStops\":%d,\"guestSeccompStops\":%d,\"unexpectedStops\":%d,\"passed\":%s}\n",code,internal,guest,guest_trap,unexpected,passed?"true":"false");return passed?0:1;
}
