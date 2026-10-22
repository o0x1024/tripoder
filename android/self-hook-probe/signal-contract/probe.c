#define _GNU_SOURCE
#include "signal_chain.h"
#include <jni.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <ucontext.h>
#include <unistd.h>

static volatile sig_atomic_t pipe_hits,winch_hits,callbacks,pipe_blocked,winch_blocked;
static void user_signal(int signal){if(signal==SIGPIPE)pipe_hits++;if(signal==SIGWINCH)winch_hits++;}
static bool observe(int signal,siginfo_t *info,void *raw){
    if(signal!=SIGSYS||!info||info->si_code!=SYS_SECCOMP||info->si_syscall!=SYS_getppid)return false;
    sigset_t mask;sigprocmask(SIG_SETMASK,NULL,&mask);
    pipe_blocked=sigismember(&mask,SIGPIPE);winch_blocked=sigismember(&mask,SIGWINCH);callbacks++;
    ((ucontext_t *)raw)->uc_mcontext.regs[0]=1234;
    return true;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_signalcontract_Probe_check(JNIEnv *env,jclass type){
    (void)type;
    sigset_t before,mask,pending,after;
    sigprocmask(SIG_SETMASK,NULL,&before);mask=before;
    sigaddset(&mask,SIGPIPE);sigaddset(&mask,SIGWINCH);sigprocmask(SIG_SETMASK,&mask,NULL);
    struct sigaction handler={0},previous_pipe,previous_winch;
    handler.sa_handler=user_signal;sigemptyset(&handler.sa_mask);
    sigaction(SIGPIPE,&handler,&previous_pipe);sigaction(SIGWINCH,&handler,&previous_winch);
    int ready=tp_signal_chain_prepare(),claim=ready?tp_signal_chain_claim(SIGSYS,observe):-1;
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(filter[0]),filter};
    int installed=prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0);
    if(!installed)installed=(int)syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program);
    syscall(SYS_tgkill,getpid(),syscall(SYS_gettid),SIGPIPE);
    syscall(SYS_tgkill,getpid(),syscall(SYS_gettid),SIGWINCH);
    sigpending(&pending);
    int pending_before=sigismember(&pending,SIGPIPE)&&sigismember(&pending,SIGWINCH);
    long value=syscall(SYS_getppid);
    sigpending(&pending);sigprocmask(SIG_SETMASK,NULL,&after);
    int pending_pipe=sigismember(&pending,SIGPIPE),pending_winch=sigismember(&pending,SIGWINCH);
    int observed_pipe=pipe_hits,observed_winch=winch_hits;
    int restored=sigismember(&after,SIGPIPE)&&sigismember(&after,SIGWINCH);
    struct timespec zero={0};sigset_t drain;
    sigemptyset(&drain);sigaddset(&drain,SIGPIPE);sigaddset(&drain,SIGWINCH);
    while(sigtimedwait(&drain,NULL,&zero)>0){}
    /* The callback must defer pending signals, without disabling ordinary delivery. */
    sigdelset(&after,SIGWINCH);sigprocmask(SIG_SETMASK,&after,NULL);
    syscall(SYS_tgkill,getpid(),syscall(SYS_gettid),SIGWINCH);
    int normal_delivery=winch_hits-observed_winch;
    sigaction(SIGPIPE,&previous_pipe,NULL);sigaction(SIGWINCH,&previous_winch,NULL);
    sigprocmask(SIG_SETMASK,&before,NULL);
    int passed=ready&&claim==0&&installed==0&&pending_before&&pending_pipe&&pending_winch
        &&!observed_pipe&&!observed_winch&&callbacks==1&&pipe_blocked&&winch_blocked
        &&restored&&normal_delivery==1&&value==1234;
    char result[768];
    snprintf(result,sizeof(result),"{\"ready\":%d,\"claim\":%d,\"installed\":%d,"
        "\"pendingBefore\":%d,\"pendingPipeAfter\":%d,\"pendingWinchAfter\":%d,"
        "\"pipeHits\":%d,\"winchHits\":%d,\"callbacks\":%d,"
        "\"pipeBlockedInside\":%d,\"winchBlockedInside\":%d,\"maskRestored\":%d,"
        "\"normalDelivery\":%d,\"returnValue\":%ld,\"passed\":%s}",
        ready,claim,installed,pending_before,pending_pipe,pending_winch,observed_pipe,observed_winch,
        callbacks,pipe_blocked,winch_blocked,restored,normal_delivery,value,passed?"true":"false");
    return (*env)->NewStringUTF(env,result);
}
