#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

static void *guard;
static char own_path[4096];
static volatile sig_atomic_t faults,correct_context,own_file,sigsys_blocked;
static void recover(int signal,siginfo_t *info,void *raw){
    int saved=errno;ucontext_t *context=raw;uint64_t mask=0;
    if(signal!=SIGSEGV||!info||info->si_code!=SEGV_ACCERR||info->si_addr!=guard)_exit(71);
    faults++;correct_context=context&&context->uc_mcontext.pc!=0;
    syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&mask,sizeof(mask));
    sigsys_blocked=(mask&(UINT64_C(1)<<(SIGSYS-1)))!=0;
    int fd=open(own_path,O_RDONLY|O_CLOEXEC);char byte=0;
    own_file=fd>=0&&read(fd,&byte,1)==1&&byte=='X';if(fd>=0)close(fd);
    if(mprotect(guard,4096,PROT_READ)!=0)_exit(72);
    errno=saved; // Return to the identical faulting instruction through ucontext.
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_ingressguest_Main_nativeCheck(JNIEnv *env,jclass type,jstring path){
    (void)type;const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return NULL;
    snprintf(own_path,sizeof(own_path),"%s",name);(*env)->ReleaseStringUTFChars(env,path,name);
    int fd=open(own_path,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);int written=fd>=0&&write(fd,"X",1)==1;if(fd>=0)close(fd);
    guard=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if(guard==MAP_FAILED)return (*env)->NewStringUTF(env,"{\"passed\":false,\"reason\":\"mmap\"}");
    *(volatile int *)guard=42;mprotect(guard,4096,PROT_NONE);
    struct sigaction action={0},previous={0},observed={0};action.sa_sigaction=recover;action.sa_flags=SA_SIGINFO;sigfillset(&action.sa_mask);
    sigset_t before,after;sigprocmask(SIG_SETMASK,NULL,&before);
    int installed=sigaction(SIGSEGV,&action,&previous);int value=installed?-1:*(volatile int *)guard;
    sigaction(SIGSEGV,NULL,&observed);int restored=installed?-1:sigaction(SIGSEGV,&previous,NULL);
    sigprocmask(SIG_SETMASK,NULL,&after);munmap(guard,4096);guard=NULL;
    pid_t child=fork();int status=0;
    if(child==0){execl("/system/bin/sh","sh","-c","exit 23",(char *)NULL);_exit(74);}
    int waited=child>0&&waitpid(child,&status,0)==child;
    int exec_ok=waited&&WIFEXITED(status)&&WEXITSTATUS(status)==23;
    int mask_kept=!memcmp(&before,&after,sizeof(before))&&sigismember(&observed.sa_mask,SIGSYS)==1;
    int passed=written&&!installed&&!restored&&faults==1&&correct_context&&own_file&&sigsys_blocked&&value==42&&mask_kept&&exec_ok;
    char result[400];snprintf(result,sizeof(result),"{\"passed\":%s,\"guardPageRecovered\":%s,\"faults\":%d,\"ucontextPresent\":%d,\"handlerLibcFileRead\":%d,\"sigsysMaskPreserved\":%d,\"priorMaskRestored\":%d,\"execExit23\":%d}",passed?"true":"false",value==42?"true":"false",faults,correct_context,own_file,sigsys_blocked,mask_kept,exec_ok);
    return (*env)->NewStringUTF(env,result);
}
