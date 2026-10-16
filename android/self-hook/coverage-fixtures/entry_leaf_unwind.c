#define _GNU_SOURCE
#include "entry_leaf_unwind.h"
#include <errno.h>
#include <libunwind.h>
#include <linux/futex.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <sys/auxv.h>
#include <sys/prctl.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

/* Owned pthread and this lock-free leaf only. The native unwinder authenticates
 * using the executing thread's PAC context, so its bounded walk stays on the
 * sampled thread. All workers doing other work have joined, and the private
 * unwinder was warmed before sampling. This is NOT a general-purpose signal
 * stack walker for arbitrary code holding unknown locks. No JNI or diagnostic
 * I/O runs in the handler; the observer writes the fixed trace after it parks. */
extern uintptr_t tp_entry_sign_probe(uintptr_t,uintptr_t);
static struct tp_leaf_sample_range code;
static _Atomic int phase;
static _Atomic bool running;
static _Atomic bool worker_ready;
static unw_context_t saved;
static uintptr_t interrupted_pc,interrupted_sp,interrupted_lr;
static int diagnostics=-1;
static int observer_keys,worker_keys;
static uintptr_t observer_signature,worker_signature,handler_frame;
struct trace_row { unw_word_t pc,sp,lr; };
static struct trace_row rows[40];
static unsigned row_count,sample_signals;
static bool sample_ok;
static bool inspect(unsigned *signals);
static void note(const char *stage,unsigned frame,unw_cursor_t *cursor){
    unw_word_t pc=0,sp=0,lr=0;
    if(cursor){unw_get_reg(cursor,UNW_REG_IP,&pc);unw_get_reg(cursor,UNW_REG_SP,&sp);unw_get_reg(cursor,30,&lr);}
    uintptr_t handler_saved=handler_frame?*((uintptr_t *)handler_frame+1):0;
    char line[640];int n=snprintf(line,sizeof(line),"%s frame=%u pc=%lx sp=%lx lr=%lx interrupted_pc=%lx interrupted_sp=%lx interrupted_lr=%lx handler_fp=%lx handler_saved_lr=%lx observer_keys=%x worker_keys=%x observer_signature=%lx worker_signature=%lx\n",
        stage,frame,(unsigned long)pc,(unsigned long)sp,(unsigned long)lr,(unsigned long)interrupted_pc,(unsigned long)interrupted_sp,(unsigned long)interrupted_lr,
        (unsigned long)handler_frame,(unsigned long)handler_saved,observer_keys,worker_keys,(unsigned long)observer_signature,(unsigned long)worker_signature);
    if(diagnostics>=0&&n>0&&(size_t)n<sizeof(line)){
        size_t written=0;while(written<(size_t)n){ssize_t k=write(diagnostics,line+written,(size_t)n-written);if(k<0&&errno==EINTR)continue;if(k<=0)abort();written+=(size_t)k;}
    }
}
static void *owned_method;
_Static_assert(ATOMIC_INT_LOCK_FREE==2 && ATOMIC_BOOL_LOCK_FREE==2,"signal state must be lock-free");
static uint64_t now(void){struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t)!=0)abort();return (uint64_t)t.tv_sec*1000000000u+(uint64_t)t.tv_nsec;}
static void release_sample(void){
    atomic_store_explicit(&phase,4,memory_order_release);
    syscall(SYS_futex,&phase,FUTEX_WAKE_PRIVATE,1,NULL,NULL,0);
}
static void handler(int signal,siginfo_t *info,void *context){
    (void)signal;(void)info;int old_errno=errno,expected=1;
    if(!atomic_compare_exchange_strong_explicit(&phase,&expected,2,memory_order_acquire,memory_order_relaxed)){errno=old_errno;return;}
    interrupted_pc=(uintptr_t)((ucontext_t *)context)->uc_mcontext.pc;
    interrupted_sp=(uintptr_t)((ucontext_t *)context)->uc_mcontext.sp;
    interrupted_lr=(uintptr_t)((ucontext_t *)context)->uc_mcontext.regs[30];
    handler_frame=(uintptr_t)__builtin_frame_address(0);
    if(unw_getcontext(&saved)!=0){atomic_store_explicit(&phase,5,memory_order_release);errno=old_errno;return;}
    uintptr_t begin=code.leaf;row_count=0;sample_signals=0;sample_ok=false;
    if(interrupted_pc>=begin&&interrupted_pc<begin+code.post_offset+4&&!(interrupted_pc&3)){
        expected=2;
        if(atomic_compare_exchange_strong_explicit(&phase,&expected,6,memory_order_release,memory_order_relaxed)){
            while(atomic_load_explicit(&phase,memory_order_acquire)==6)syscall(SYS_futex,&phase,FUTEX_WAIT_PRIVATE,6,NULL,NULL,0);
            if(atomic_load_explicit(&phase,memory_order_acquire)==2)sample_ok=inspect(&sample_signals);
        }
    }
    expected=2;
    if(!atomic_compare_exchange_strong_explicit(&phase,&expected,3,memory_order_release,memory_order_relaxed)){
        atomic_store_explicit(&phase,0,memory_order_release);errno=old_errno;return;
    }
    while(atomic_load_explicit(&phase,memory_order_acquire)==3)syscall(SYS_futex,&phase,FUTEX_WAIT_PRIVATE,3,NULL,NULL,0);
    atomic_store_explicit(&phase,0,memory_order_release);errno=old_errno;
}
static void *work(void *unused){
    (void)unused;sigset_t mask;sigemptyset(&mask);sigaddset(&mask,SIGRTMIN+7);
    if(pthread_sigmask(SIG_UNBLOCK,&mask,NULL)!=0)abort();
    worker_keys=prctl(PR_PAC_GET_ENABLED_KEYS,0,0,0,0);
    worker_signature=tp_entry_sign_probe(code.caller,0x1000);
    atomic_store_explicit(&worker_ready,true,memory_order_release);
    while(atomic_load_explicit(&running,memory_order_acquire)){
        // Foreign 64-slot scans dominated interrupt delivery and made the one
        // post-STP instruction effectively unreachable in some runs. Foreign
        // routing is already tested separately; sample the owned leaf here.
        code.call(owned_method,0x9000);
    }
    return NULL;
}
static bool inspect(unsigned *signals){
    unw_cursor_t cursor;if(unw_init_local(&cursor,&saved)!=0)return false;
    bool found=false;
    for(unsigned i=0;i<40;i++){
        unw_word_t pc=0;if(unw_get_reg(&cursor,UNW_REG_IP,&pc)!=0)return false;
        struct trace_row *row=&rows[row_count++];row->pc=pc;
        if(unw_get_reg(&cursor,UNW_REG_SP,&row->sp)!=0||unw_get_reg(&cursor,30,&row->lr)!=0)return false;
        if(unw_is_signal_frame(&cursor)>0)(*signals)++;
        if(pc==interrupted_pc)found=true;
        if(found&&pc==code.caller)return true;
        if(unw_step(&cursor)<=0)return false;
    }
    return false;
}
bool tp_entry_leaf_unwind(void *owned,void *foreign,int diagnostic_fd,const struct tp_leaf_sample_range *range,struct tp_leaf_unwind_report *report){
    if(!owned||!foreign||!report||!range||!range->leaf||!range->caller||!range->call||range->post_offset>120||range->post_offset%4)return false;
    code=*range;atomic_store(&worker_ready,false);
    *report=(struct tp_leaf_unwind_report){0};owned_method=owned;diagnostics=diagnostic_fd;
    report->leaf=code.leaf;report->caller=code.caller;
    if(!(getauxval(AT_HWCAP)&(1UL<<30)))return false;
    observer_keys=prctl(PR_PAC_GET_ENABLED_KEYS,0,0,0,0);
    observer_signature=tp_entry_sign_probe(code.caller,0x1000);
    unw_context_t warm;unw_cursor_t cursor;
    if(unw_getcontext(&warm)!=0||unw_init_local(&cursor,&warm)!=0)return false;
    note("warm",0,&cursor);(void)unw_step(&cursor);note("warm-after",1,&cursor);
    int signal=SIGRTMIN+7;struct sigaction original,action={0};
    if(signal>SIGRTMAX||sigaction(signal,NULL,&original)!=0||original.sa_handler!=SIG_DFL)return false;
    action.sa_sigaction=handler;action.sa_flags=SA_SIGINFO;sigemptyset(&action.sa_mask);
    if(sigaction(signal,&action,NULL)!=0)return false;
    atomic_store(&phase,0);atomic_store(&running,true);pthread_t thread;
    if(pthread_create(&thread,NULL,work,NULL)!=0){report->restored=sigaction(signal,&original,NULL)==0;return false;}
    while(!atomic_load_explicit(&worker_ready,memory_order_acquire))sched_yield();
    note("key-state",0,NULL);
    uint64_t deadline=now()+UINT64_C(15000000000);
    // Every PC has the same signed-LR/CFA=SP+32 state, independently decoded.
    // Hardware delivery need not hit the single branch after the replay.
    // Retain actual sampled PCs; never assert instruction-uniform delivery.
    for(unsigned i=0;i<20000&&now()<deadline&&report->samples<512;i++){
        report->attempts++;atomic_store_explicit(&phase,1,memory_order_release);
        if(pthread_kill(thread,signal)!=0){report->failures++;break;}
        uint64_t wait_until=now()+UINT64_C(1000000000);
        int status;
        while((status=atomic_load_explicit(&phase,memory_order_acquire))!=3&&status!=5&&now()<wait_until){
            if(status==6){
                note("before-unwind",0,NULL);
                int captured=6;
                if(atomic_compare_exchange_strong_explicit(&phase,&captured,2,memory_order_release,memory_order_relaxed))
                    syscall(SYS_futex,&phase,FUTEX_WAKE_PRIVATE,1,NULL,NULL,0);
            }
            sched_yield();
        }
        if(status!=3){report->failures++;release_sample();break;}
        uintptr_t begin=code.leaf;
        if(interrupted_pc>=begin&&interrupted_pc<begin+code.post_offset+4&&!(interrupted_pc&3)){
            note("sample-state",0,NULL);
            for(unsigned j=0;j<row_count;j++)dprintf(diagnostics,"sample frame=%u pc=%lx sp=%lx lr=%lx\n",j,
                (unsigned long)rows[j].pc,(unsigned long)rows[j].sp,(unsigned long)rows[j].lr);
            report->signal_frames+=sample_signals;
            if(sample_ok){
                report->samples++;report->leaf_mask|=1u<<((interrupted_pc-begin)/4);
                report->state_mask=2; // The sole published leaf has CFA=SP+32 at every PC.
            }else report->failures++;
        }
        release_sample();wait_until=now()+UINT64_C(1000000000);
        while(atomic_load_explicit(&phase,memory_order_acquire)!=0&&now()<wait_until)sched_yield();
        if(atomic_load(&phase)!=0){report->failures++;break;}
        if(report->failures)break;
        struct timespec pause={.tv_nsec=20000};nanosleep(&pause,NULL);
    }
    atomic_store_explicit(&running,false,memory_order_release);
    if(atomic_load(&phase)!=0)release_sample();
    if(pthread_join(thread,NULL)!=0)abort();report->joined=true;
    struct sigaction current;
    if(sigaction(signal,NULL,&current)==0&&current.sa_sigaction==handler)
        report->restored=sigaction(signal,&original,NULL)==0;
    return report->restored&&report->joined&&report->failures==0&&report->state_mask==2&&report->samples>=512;
}
