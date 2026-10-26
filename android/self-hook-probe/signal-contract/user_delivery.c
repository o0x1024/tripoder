#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Only this fixture's own file and handler. Observe the physical mask without
 * routing through libsigchain's public requested-mask projection. */
static char input[PATH_MAX];
static volatile sig_atomic_t entered,blocked,blocked_pipe,blocked_winch,mask_read,returned,own_byte,nested_errno_kept,trapped_call;
static long direct_call(long number,long a,long b,long c,long d){
    register long x0 __asm__("x0")=a,x1 __asm__("x1")=b,x2 __asm__("x2")=c,x3 __asm__("x3")=d,x8 __asm__("x8")=number;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x8):"memory","cc");return x0;
}
static void user_handler(int number,siginfo_t *info,void *context){
    (void)number;(void)info;(void)context;int saved=errno;uint64_t mask=0;entered++;
    mask_read=direct_call(SYS_rt_sigprocmask,SIG_SETMASK,0,(long)&mask,8)==0;
    blocked=(mask&(UINT64_C(1)<<(SIGSYS-1)))!=0;
    blocked_pipe=(mask&(UINT64_C(1)<<(SIGPIPE-1)))!=0;blocked_winch=(mask&(UINT64_C(1)<<(SIGWINCH-1)))!=0;
    errno=EBUSY;
    long fd=trapped_call?direct_call(SYS_openat,AT_FDCWD,(long)input,O_RDONLY|O_CLOEXEC,0):direct_call(SYS_getpid,0,0,0,0);
    nested_errno_kept=errno==EBUSY;returned=1;
    if(trapped_call&&fd>=0){char byte=0;own_byte=read((int)fd,&byte,1)==1&&byte=='X';close((int)fd);}
    if(!trapped_call)own_byte=fd==getpid();errno=saved;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_signalcontract_Probe_delivery(JNIEnv *env,jclass type,jstring path,jboolean trapped){
    (void)type;const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return NULL;
    size_t length=strlen(name);if(!length||length>=sizeof(input)){(*env)->ReleaseStringUTFChars(env,path,name);return NULL;}
    memcpy(input,name,length+1);(*env)->ReleaseStringUTFChars(env,path,name);
    entered=blocked=blocked_pipe=blocked_winch=mask_read=returned=own_byte=nested_errno_kept=0;trapped_call=trapped;
    sigset_t before,after;sigprocmask(SIG_SETMASK,NULL,&before);
    struct sigaction action={0},previous={0},observed={0};action.sa_sigaction=user_handler;action.sa_flags=SA_SIGINFO;sigfillset(&action.sa_mask);
    int installed=sigaction(SIGSEGV,&action,&previous);int queried=installed?installed:sigaction(SIGSEGV,NULL,&observed);
    if(installed)return (*env)->NewStringUTF(env,"{\"passed\":false,\"setupFailure\":\"SIGACTION_INSTALL_FAILED\"}");
    int declared_mask_kept=!queried&&sigismember(&observed.sa_mask,SIGSYS)==1&&sigismember(&observed.sa_mask,SIGPIPE)==1;
    errno=EBUSY;int raised=installed?-1:raise(SIGSEGV),errno_kept=errno==EBUSY;
    int restored=sigaction(SIGSEGV,&previous,NULL);sigprocmask(SIG_SETMASK,NULL,&after);
    int mask_kept=!memcmp(&before,&after,sizeof(before));
    int passed=!queried&&declared_mask_kept&&!raised&&!restored&&entered==1&&mask_read&&(!trapped||!blocked)&&blocked_pipe&&blocked_winch&&returned&&own_byte&&nested_errno_kept&&mask_kept;
    char result[600];snprintf(result,sizeof(result),"{\"trapped\":%s,\"entered\":%d,\"maskRead\":%d,\"physicalSigsysBlocked\":%d,\"physicalPipeBlocked\":%d,\"physicalWinchBlocked\":%d,\"syscallReturned\":%d,\"ownResourceCorrect\":%d,\"declaredMaskKept\":%d,\"nestedSyscallErrnoKept\":%d,\"afterRaiseErrnoMatched\":%d,\"priorMaskRestored\":%d,\"passed\":%s}",trapped?"true":"false",entered,mask_read,blocked,blocked_pipe,blocked_winch,returned,own_byte,declared_mask_kept,nested_errno_kept,errno_kept,mask_kept,passed?"true":"false");
    return (*env)->NewStringUTF(env,result);
}
