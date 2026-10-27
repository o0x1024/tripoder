#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>
#include <ucontext.h>
#include <unistd.h>

/* Own inaccessible page and own instruction. This validates delivery/recovery
 * of a real SEGV_ACCERR without touching another application's protection. */
static void *guard;
static volatile sig_atomic_t faults,access_error,address_correct,instruction_correct;
static void protection_handler(int signal,siginfo_t *info,void *raw){
    int saved=errno;ucontext_t *context=raw;
    if(signal!=SIGSEGV||!info||info->si_addr!=guard||info->si_code!=SEGV_ACCERR)_exit(71);
    faults++;access_error=info->si_code==SEGV_ACCERR;address_correct=info->si_addr==guard;
    instruction_correct=*(const uint32_t *)context->uc_mcontext.pc==UINT32_C(0xb9400109);
    if(!instruction_correct)_exit(72);
    context->uc_mcontext.pc+=4;errno=saved;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_signalcontract_Probe_protection(JNIEnv *env,jclass type){
    (void)type;faults=access_error=address_correct=instruction_correct=0;guard=mmap(NULL,4096,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    if(guard==MAP_FAILED)return (*env)->NewStringUTF(env,"{\"passed\":false,\"setupFailure\":\"OWNED_PAGE_UNAVAILABLE\"}");
    struct sigaction action={0},previous={0};action.sa_sigaction=protection_handler;action.sa_flags=SA_SIGINFO;sigfillset(&action.sa_mask);
    int installed=sigaction(SIGSEGV,&action,&previous);unsigned value=0;
    if(!installed){errno=EBUSY;__asm__ volatile("mov x8,%1\nmov w9,#123\nldr w9,[x8]\nmov %w0,w9":"=r"(value):"r"(guard):"x8","x9","memory");}
    int errno_kept=errno==EBUSY,restored=installed?-1:sigaction(SIGSEGV,&previous,NULL);
    int unmapped=munmap(guard,4096);guard=NULL;
    /* errnoKept is an observation for the same-APK system comparator, not a
     * stronger guarantee than this platform's ART signal delivery provides. */
    int passed=!installed&&!restored&&!unmapped&&faults==1&&access_error&&address_correct&&instruction_correct&&value==123;
    char result[400];snprintf(result,sizeof(result),"{\"signal\":11,\"code\":2,\"faults\":%d,\"addressCorrect\":%d,\"instructionCorrect\":%d,\"resumedAfterInstruction\":%s,\"errnoKept\":%d,\"handlerRestored\":%s,\"passed\":%s}",faults,address_correct,instruction_correct,value==123?"true":"false",errno_kept,!restored?"true":"false",passed?"true":"false");
    return (*env)->NewStringUTF(env,result);
}
