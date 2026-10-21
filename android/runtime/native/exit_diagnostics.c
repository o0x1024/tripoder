#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "syscall_namespace.h"
#include "user_memory.h"
#include "signal_chain.h"
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stdatomic.h>
#include <sys/mman.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#define syscall tp_kernel_syscall

/* Opt-in diagnostic build only. Observe the kernel exit boundary, including callers
 * that bypass libc. Resume the same exit_group with the unchanged status. */
static int evidence=-1,pc_bytes=-1,lr_bytes=-1,image=-1;
static char map_snapshot[1048576];
extern char tp_exit_resume_pc[];
__attribute__((naked,noreturn)) static void resume_exit(int status __attribute__((unused))){
    __asm__ volatile("mov x8, #94\nsvc #0\n.global tp_exit_resume_pc\n.hidden tp_exit_resume_pc\ntp_exit_resume_pc:\nbrk #0\n");
}
static void capture(const char *kind,void *raw,int status){
    ucontext_t *context=raw;
    uintptr_t pc=context->uc_mcontext.pc,lr=context->uc_mcontext.regs[30];
    char line[2048];int n=snprintf(line,sizeof(line),"%s status=%d pid=%ld tid=%ld pc=%lx lr=%lx sp=%lx\n",
        kind,status,syscall(SYS_getpid),syscall(SYS_gettid),(unsigned long)pc,(unsigned long)lr,(unsigned long)context->uc_mcontext.sp);
    syscall(SYS_write,evidence,line,(size_t)n);
    for(unsigned i=0;i<31;i++){n=snprintf(line,sizeof(line),"x%u=%lx\n",i,(unsigned long)context->uc_mcontext.regs[i]);syscall(SYS_write,evidence,line,(size_t)n);}
    int maps=(int)syscall(SYS_openat,AT_FDCWD,"/proc/self/maps",O_RDONLY|O_CLOEXEC,0);
    if(maps>=0){ssize_t count;size_t total=0;while(total<sizeof(map_snapshot)-1&&(count=syscall(SYS_read,maps,map_snapshot+total,sizeof(map_snapshot)-1-total))>0)total+=(size_t)count;syscall(SYS_close,maps);map_snapshot[total]=0;syscall(SYS_write,evidence,map_snapshot,total);
        for(char *at=map_snapshot;at&&*at;){char *end=strchr(at,'\n');if(end)*end=0;unsigned long first=0,last=0;
            if(sscanf(at,"%lx-%lx",&first,&last)==2&&first<=pc&&pc<last&&last-first<=16*1024*1024){syscall(SYS_write,image,(void *)first,last-first);break;}at=end?end+1:NULL;}}
    syscall(SYS_write,pc_bytes,(void *)(pc&~(uintptr_t)4095),4096);
    syscall(SYS_write,lr_bytes,(void *)(lr&~(uintptr_t)4095),4096);
    syscall(SYS_write,lr_bytes,(void *)context->uc_mcontext.sp,4096);
    tp_syscall_namespace_dump(evidence);
}
static _Atomic unsigned captured_faults;
static long fault_capture(int signal,siginfo_t *info,void *context){
    char line[160];int n=snprintf(line,sizeof(line),"FATAL signal=%d code=%d address=%p\n",signal,info?info->si_code:0,info?info->si_addr:NULL);syscall(SYS_write,evidence,line,(size_t)n);capture("fatal",context,signal);return 0;
}
static bool observe_fault(int signal,siginfo_t *info,void *context){
    if(atomic_fetch_add_explicit(&captured_faults,1,memory_order_relaxed)<8)tp_native_stack_call((void *)fault_capture,signal,(long)info,(long)context);
    return false;
}
static bool observe_exit(int sig,siginfo_t *info,void *raw){
    if(sig!=SIGSYS||!info||info->si_code!=SYS_SECCOMP)return false;
    if(info->si_syscall==SYS_exit_group){int status=(int)((ucontext_t *)raw)->uc_mcontext.regs[0];capture("exit_group",raw,status);resume_exit(status);}
    if(info->si_syscall!=SYS_kill&&info->si_syscall!=SYS_tgkill&&info->si_syscall!=SYS_tkill){capture("foreign_seccomp",raw,info->si_syscall);return false;}
    ucontext_t *context=raw;int saved=errno;const __u64 *a=context->uc_mcontext.regs;if(a[info->si_syscall==SYS_tgkill?2:1])capture("signal_send",raw,info->si_syscall);long result=syscall(info->si_syscall,a[0],a[1],a[2]);context->uc_mcontext.regs[0]=(uint64_t)(result<0?-errno:result);errno=saved;return true;
}
JNIEXPORT void JNICALL Java_dev_tripoder_runtime_RuntimeSelfHookStorage_traceExitNative(JNIEnv *env,jclass type,jstring path){
    tp_syscall_namespace_enable_audit();
    (void)type;const char *name=(*env)->GetStringUTFChars(env,path,NULL);if(!name)return;
    char output[4096];snprintf(output,sizeof(output),"%s.txt",name);evidence=(int)syscall(SYS_openat,AT_FDCWD,output,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);
    snprintf(output,sizeof(output),"%s-pc.bin",name);pc_bytes=(int)syscall(SYS_openat,AT_FDCWD,output,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);
    snprintf(output,sizeof(output),"%s-lr.bin",name);lr_bytes=(int)syscall(SYS_openat,AT_FDCWD,output,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);
    snprintf(output,sizeof(output),"%s-image.bin",name);image=(int)syscall(SYS_openat,AT_FDCWD,output,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);
    (*env)->ReleaseStringUTFChars(env,path,name);
    uintptr_t resume=(uintptr_t)tp_exit_resume_pc,gateway=(uintptr_t)tp_kernel_syscall_resume;
    struct sock_filter filter[24];unsigned length=0;
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4);
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(gateway>>32),0,2);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer));
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)gateway,9,0);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4);
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(resume>>32),0,2);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer));
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)resume,5,0);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
    int numbers[]={SYS_exit_group,SYS_kill,SYS_tgkill,SYS_tkill};
    for(unsigned i=0;i<4;i++)filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,numbers[i],(uint8_t)(4-i),0);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP);
    struct sock_fprog program={.len=(unsigned short)length,.filter=filter};
    int fatal_signals[]={SIGSEGV,SIGBUS,SIGABRT};
    int result=0;for(unsigned i=0;i<sizeof(fatal_signals)/sizeof(fatal_signals[0]);i++)if(tp_signal_chain_claim(fatal_signals[i],observe_fault)!=0)result=-1;
    if(evidence<0||pc_bytes<0||lr_bytes<0||tp_signal_chain_claim(SIGSYS,observe_exit)!=0||prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)!=0)result=-1;
    if(result==0)result=(int)syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program);
    if(result!=0){char message[80];int n=snprintf(message,sizeof(message),"EXIT_DIAGNOSTICS_INSTALL_FAILED result=%d errno=%d\n",result,errno);if(evidence>=0)syscall(SYS_write,evidence,message,(size_t)n);}
}
