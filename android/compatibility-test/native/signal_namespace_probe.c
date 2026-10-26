#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "path_policy.h"
#include "syscall_namespace.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* Standalone, self-owned kernel oracle. No ART, APK, other PID or app data. */
static const char *owned_file;
static int witness;
struct observation {int entered,sys_blocked,returned,own_byte;};
static long inline_open(const char *path){
    register long x0 __asm__("x0")=AT_FDCWD,x1 __asm__("x1")=(long)path;
    register long x2 __asm__("x2")=O_RDONLY|O_CLOEXEC,x3 __asm__("x3")=0,x8 __asm__("x8")=SYS_openat;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x8):"memory","cc");return x0;
}
static void owned_handler(int number,siginfo_t *info,void *context){
    (void)number;(void)info;(void)context;
    int saved=errno;uint64_t mask=0;
    if(tp_kernel_syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&mask,8)!=0)_exit(71);
    struct observation event={1,(mask&(UINT64_C(1)<<(SIGSYS-1)))!=0,0,0};
    if(tp_kernel_syscall(SYS_write,witness,&event,sizeof(event))!=sizeof(event))_exit(72);
    long fd=inline_open(owned_file);event.returned=1;
    if(fd>=0){char byte=0;event.own_byte=tp_kernel_syscall(SYS_read,fd,&byte,1)==1&&byte=='X';tp_kernel_syscall(SYS_close,fd);}
    tp_kernel_syscall(SYS_write,witness,&event,sizeof(event));errno=saved;
}
static int scenario(bool namespaced,bool full_mask,const char *data,const char *artifacts){
    int pipefd[2];if(pipe(pipefd))return 1;
    pid_t child=fork();if(child<0)return 2;
    if(!child){
        close(pipefd[0]);witness=pipefd[1];
        if(namespaced&&(tp_configure(data,data,artifacts,artifacts,"dev.tripoder.ownedsignal")||tp_syscall_namespace_install()))_exit(73);
        struct sigaction action={0};action.sa_sigaction=owned_handler;action.sa_flags=SA_SIGINFO;
        if(full_mask)sigfillset(&action.sa_mask);else sigemptyset(&action.sa_mask);
        if(sigaction(SIGSEGV,&action,NULL))_exit(74);
        sigset_t before,after;sigprocmask(SIG_SETMASK,NULL,&before);errno=EBUSY;
        if(raise(SIGSEGV))_exit(75);
        int preserved=errno==EBUSY;sigprocmask(SIG_SETMASK,NULL,&after);
        if(!preserved||memcmp(&before,&after,sizeof(before)))_exit(76);
        _exit(0);
    }
    close(pipefd[1]);struct observation events[2]={{0}};size_t bytes=0;ssize_t count;
    while(bytes<sizeof(events)&&(count=read(pipefd[0],(char *)events+bytes,sizeof(events)-bytes))>0)bytes+=(size_t)count;
    close(pipefd[0]);int status;if(waitpid(child,&status,0)!=child)return 3;
    int signal=WIFSIGNALED(status)?WTERMSIG(status):0,code=WIFEXITED(status)?WEXITSTATUS(status):-1;
    bool expected=namespaced&&full_mask;
    bool passed=events[0].entered&&events[0].sys_blocked==full_mask&&
        (expected?(signal==SIGSYS&&bytes==sizeof(struct observation)):
                  (signal==0&&code==0&&bytes==sizeof(events)&&events[1].returned&&events[1].own_byte));
    printf("{\"namespace\":%s,\"fullHandlerMask\":%s,\"entered\":%d,\"sigsysBlocked\":%d,\"syscallReturned\":%s,\"exitSignal\":%d,\"exitCode\":%d,\"passed\":%s}",
        namespaced?"true":"false",full_mask?"true":"false",events[0].entered,events[0].sys_blocked,
        bytes==sizeof(events)&&events[1].returned?"true":"false",signal,code,passed?"true":"false");
    return passed?0:4;
}
int main(int argc,char **argv){
    if(argc!=2||strncmp(argv[1],"/data/local/tmp/tripoder-owned-signal-",sizeof("/data/local/tmp/tripoder-owned-signal-")-1)||strlen(argv[1])>180)return 64;
    char data[256],artifacts[256],path[256];
    snprintf(data,sizeof(data),"%s/data",argv[1]);snprintf(artifacts,sizeof(artifacts),"%s/artifacts",argv[1]);snprintf(path,sizeof(path),"%s/owned.txt",data);owned_file=path;
    if(mkdir(argv[1],0700)||mkdir(data,0700)||mkdir(artifacts,0700))return 65;
    int file=open(path,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);if(file<0||write(file,"X",1)!=1)return 66;close(file);
    printf("{\"scenarios\":[");int result=scenario(false,true,data,artifacts);printf(",");result|=scenario(true,false,data,artifacts);printf(",");result|=scenario(true,true,data,artifacts);
    printf("],\"mechanismReproduced\":%s,\"productionContractPassed\":false,\"artIntegrationVerified\":false,\"accountDataRead\":false,\"passed\":%s}\n",result?"false":"true",result?"false":"true");return result;
}
