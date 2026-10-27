#define _GNU_SOURCE
#include "io_adapter.h"
#include "path_policy.h"
#include "syscall_namespace.h"
#include "kernel_syscall.h"
#include "user_memory.h"
#include "ptrace_namespace.h"
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stdio.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <ucontext.h>
#include <unistd.h>
#define syscall tp_kernel_syscall

/* Linker and protected libraries can issue SVC directly. Use the same path policy
 * as libc, once, before guest code. Only the owned internal syscall gateway bypasses
 * this dispatcher, so internal policy syscalls do not recurse through SIGSYS. */
static struct sigaction previous;
struct kernel_sigaction_a64 {
    uintptr_t handler;
    unsigned long flags;
    uintptr_t restorer;
    uint64_t mask;
};
static struct kernel_sigaction_a64 guest_sigsys;
static int (*chain_user_action)(int,const struct sigaction *,struct sigaction *);
static const int numbers[]={SYS_openat,SYS_newfstatat,SYS_readlinkat,SYS_faccessat,SYS_unlinkat,SYS_mkdirat,SYS_renameat2,SYS_chdir,SYS_symlinkat,SYS_linkat,SYS_statfs,SYS_inotify_add_watch,SYS_fchmodat,SYS_ptrace,SYS_wait4,SYS_waitid,SYS_execve,SYS_execveat,SYS_kill,SYS_tkill,SYS_tgkill,SYS_connect};
enum { CALL_COUNT=sizeof(numbers)/sizeof(numbers[0]) };
static const unsigned call_count=CALL_COUNT;
static _Atomic bool installed;
static bool debugger_auditing;
static _Atomic bool policy_block_execve=false;
static _Atomic bool policy_block_kill=false;
static _Atomic bool policy_network_sinkhole=false;
static _Atomic uint64_t policy_counts[6]; /* 0:execve_attempts, 1:execve_blocked, 2:kill_attempts, 3:kill_blocked, 4:connect_attempts, 5:connect_sinkholed */
void tp_syscall_namespace_trace_debugger(bool enabled){if(!atomic_load_explicit(&installed,memory_order_acquire))debugger_auditing=enabled;}
void tp_syscall_namespace_set_policy(bool block_execve,bool block_kill,bool network_sinkhole){
    atomic_store_explicit(&policy_block_execve,block_execve,memory_order_release);
    atomic_store_explicit(&policy_block_kill,block_kill,memory_order_release);
    atomic_store_explicit(&policy_network_sinkhole,network_sinkhole,memory_order_release);
}
void tp_syscall_namespace_get_policy(bool *block_execve,bool *block_kill,bool *network_sinkhole,uint64_t counts[6]){
    if(block_execve)*block_execve=atomic_load_explicit(&policy_block_execve,memory_order_acquire);
    if(block_kill)*block_kill=atomic_load_explicit(&policy_block_kill,memory_order_acquire);
    if(network_sinkhole)*network_sinkhole=atomic_load_explicit(&policy_network_sinkhole,memory_order_acquire);
    if(counts){for(unsigned i=0;i<6;i++)counts[i]=atomic_load_explicit(&policy_counts[i],memory_order_relaxed);}
}
_Static_assert(CALL_COUNT+10<=32,"syscall filter budget");
static _Atomic uint64_t observed[CALL_COUNT];
struct audit_row { _Atomic bool busy;unsigned sequence;int number;long result;uintptr_t pc;char path[256]; };
static struct audit_row audit[64];static _Atomic unsigned audit_sequence;
static _Atomic bool auditing;
static _Atomic int audit_fd=-1;
void tp_syscall_namespace_set_audit_fd(int fd){atomic_store_explicit(&audit_fd,fd,memory_order_release);}
int tp_syscall_namespace_get_audit_fd(void){return atomic_load_explicit(&audit_fd,memory_order_acquire);}
static void record(int number,long result,uintptr_t pc,const char *path){
    if(!atomic_load_explicit(&auditing,memory_order_relaxed))return;
    char safe_path[256];safe_path[0]=0;
    if(path){if(tp_user_string(path,safe_path,sizeof(safe_path))<0)safe_path[sizeof(safe_path)-1]=0;}
    int fd=atomic_load_explicit(&audit_fd,memory_order_relaxed);
    if(fd>=0){
        char line[512];
        int n=snprintf(line,sizeof(line),"seccomp pid=%ld tid=%ld nr=%d res=%ld pc=%lx path=%s\n",
            syscall(SYS_getpid),syscall(SYS_gettid),number,result,(unsigned long)pc,safe_path);
        if(n>0&&(size_t)n<sizeof(line))syscall(SYS_write,fd,line,(size_t)n);
    }
    unsigned sequence=atomic_fetch_add_explicit(&audit_sequence,1,memory_order_relaxed)+1;struct audit_row *row=&audit[sequence%64];
    bool expected=false;if(!atomic_compare_exchange_strong_explicit(&row->busy,&expected,true,memory_order_acquire,memory_order_relaxed))return;
    row->number=number;row->result=result;row->pc=pc;
    memcpy(row->path,safe_path,sizeof(row->path));
    row->sequence=sequence;atomic_store_explicit(&row->busy,false,memory_order_release);
}
static long metadata_path(int number,int descriptor,const char *name,void *output,unsigned mask){
    char safe_name[PATH_MAX];if(tp_user_string(name,safe_name,sizeof(safe_name))<0)return -1;name=safe_name;
    struct tp_path path;if(tp_resolve(AT_FDCWD,name,TP_METADATA,&path)<0)return -1;
    int fd=(int)syscall(SYS_openat,path.parent,path.leaf,O_PATH|O_CLOEXEC|(path.owned?O_NOFOLLOW:0),0);int opened_errno=errno;tp_release(&path);errno=opened_errno;if(fd<0)return -1;
    long result;if(number==SYS_statfs)result=syscall(SYS_fstatfs,fd,output);
    else{char alias[64];snprintf(alias,sizeof(alias),"/proc/self/fd/%d",fd);result=syscall(SYS_inotify_add_watch,descriptor,alias,mask);}
    int saved=errno;syscall(SYS_close,fd);errno=saved;return result;
}
static bool owned_request(int signal,const siginfo_t *info){
    if(signal!=SIGSYS||!info||info->si_code!=SYS_SECCOMP||info->si_errno!=TP_NAMESPACE_TRAP_DATA)return false;
    if(info->si_syscall==SYS_rt_sigaction)return true;
    for(unsigned i=0;i<call_count;i++)if(info->si_syscall==numbers[i])return true;
    return false;
}
static long sigsys_action(int sig,const void *act,void *oldact,size_t sigsetsize){
    if(sig!=SIGSYS||sigsetsize!=sizeof(uint64_t)){errno=EINVAL;return -1;}
    struct kernel_sigaction_a64 next;
    if(act&&tp_user_read(act,&next,sizeof(next))<0)return -1;
    if(chain_user_action){
        struct sigaction requested={0},previous_action={0};
        if(act){requested.sa_sigaction=(void (*)(int,siginfo_t *,void *))next.handler;requested.sa_flags=(int)next.flags;requested.sa_restorer=(void (*)(void))next.restorer;memcpy(&requested.sa_mask,&next.mask,sizeof(next.mask));}
        if(chain_user_action(sig,act?&requested:NULL,oldact?&previous_action:NULL)<0)return -1;
        if(oldact){
            struct kernel_sigaction_a64 old={.handler=(uintptr_t)previous_action.sa_sigaction,.flags=(unsigned long)previous_action.sa_flags,.restorer=(uintptr_t)previous_action.sa_restorer};
            memcpy(&old.mask,&previous_action.sa_mask,sizeof(old.mask));
            if(tp_user_write(oldact,&old,sizeof(old))<0)return -1;
        }
        return 0;
    }
    if(oldact&&tp_user_write(oldact,&guest_sigsys,sizeof(guest_sigsys))<0)return -1;
    if(act){
        guest_sigsys=next;
        memset(&previous,0,sizeof(previous));
        previous.sa_sigaction=(void (*)(int,siginfo_t *,void *))next.handler;
        previous.sa_flags=(int)next.flags;
        memcpy(&previous.sa_mask,&next.mask,sizeof(next.mask)<sizeof(previous.sa_mask)?sizeof(next.mask):sizeof(previous.sa_mask));
    }
    return 0;
}
static void forward_signal(int signal,siginfo_t *info,void *raw){
        if((previous.sa_flags&SA_SIGINFO)&&previous.sa_sigaction)previous.sa_sigaction(signal,info,raw);
        else if(previous.sa_handler==SIG_IGN)return;
        else if(previous.sa_handler!=SIG_DFL&&previous.sa_handler)previous.sa_handler(signal);
        else{syscall(SYS_rt_sigaction,SIGSYS,&guest_sigsys,NULL,sizeof(uint64_t));syscall(SYS_tgkill,syscall(SYS_getpid),syscall(SYS_gettid),SIGSYS);}
}
static long dispatch(int signal,siginfo_t *info,void *raw){
    (void)signal;
    ucontext_t *context=raw;const __u64 *args=context->uc_mcontext.regs;int saved=errno;long result;
    const char *name=(const char *)args[1];for(unsigned i=0;i<CALL_COUNT;i++)if(info->si_syscall==numbers[i])atomic_fetch_add_explicit(&observed[i],1,memory_order_relaxed);
    switch(info->si_syscall){
        case SYS_openat:result=tp_openat((int)args[0],name,(int)args[2],(unsigned)args[3]);break;
        case SYS_newfstatat:result=tp_fstatat((int)args[0],name,(struct stat *)args[2],(int)args[3]);break;
        case SYS_readlinkat:result=tp_readlinkat((int)args[0],name,(char *)args[2],(size_t)args[3]);break;
        case SYS_faccessat:result=tp_faccessat((int)args[0],name,(int)args[2]);break;
        case SYS_fchmodat:result=tp_fchmodat((int)args[0],name,(unsigned)args[2]);break;
        case SYS_unlinkat:result=tp_unlinkat((int)args[0],name,(int)args[2]);break;
        case SYS_mkdirat:result=tp_mkdirat((int)args[0],name,(unsigned)args[2]);break;
        case SYS_renameat2:result=tp_renameat2((int)args[0],name,(int)args[2],(const char *)args[3],(unsigned)args[4]);break;
        case SYS_chdir:name=(const char *)args[0];result=tp_chdir(name);break;
        case SYS_symlinkat:name=(const char *)args[2];result=tp_symlinkat((const char *)args[0],(int)args[1],name);break;
        case SYS_linkat:result=tp_linkat((int)args[0],name,(int)args[2],(const char *)args[3],(int)args[4]);break;
        case SYS_statfs:name=(const char *)args[0];result=metadata_path(SYS_statfs,0,name,(void *)args[1],0);break;
        case SYS_rt_sigaction:name=NULL;result=sigsys_action((int)args[0],(const void *)args[1],(void *)args[2],(size_t)args[3]);break;
        case SYS_ptrace:case SYS_wait4:case SYS_waitid:{
            name=NULL;
            if(info->si_syscall==SYS_ptrace)result=tp_namespace_ptrace(args[0],args[1],args[2],args[3]);
            else if(info->si_syscall==SYS_wait4)result=tp_namespace_wait4(args[0],(void *)args[1],args[2],(void *)args[3]);
            else result=tp_namespace_waitid(args[0],args[1],(void *)args[2],args[3],(void *)args[4]);
            int call_errno=errno;
            int fd=atomic_load_explicit(&audit_fd,memory_order_relaxed),status=0;
            if(info->si_syscall==SYS_wait4&&result>0&&args[1])tp_user_read((void *)args[1],&status,sizeof(status));
            if(debugger_auditing&&fd>=0){char line[256];int n=snprintf(line,sizeof(line),"DEBUGGER pid=%ld tid=%ld nr=%d a0=%lx a1=%lx a2=%lx a3=%lx result=%ld errno=%d status=%x\n",syscall(SYS_getpid),syscall(SYS_gettid),info->si_syscall,(unsigned long)args[0],(unsigned long)args[1],(unsigned long)args[2],(unsigned long)args[3],result,result<0?call_errno:0,status);if(n>0&&(size_t)n<sizeof(line))syscall(SYS_write,fd,line,(size_t)n);}
            errno=call_errno;break;
        }
        case SYS_execve:case SYS_execveat:{
            name=(const char *)(info->si_syscall==SYS_execve?args[0]:args[1]);
            atomic_fetch_add_explicit(&policy_counts[0],1,memory_order_relaxed);
            /* The seccomp trap filter survives execve but the SIGSYS handler does not: an exec'd image dies with SIGSYS at its first
             * namespaced syscall (the dynamic linker's openat). A failed exec is the only outcome the caller can handle. */
            if(true||atomic_load_explicit(&policy_block_execve,memory_order_acquire)){
                atomic_fetch_add_explicit(&policy_counts[1],1,memory_order_relaxed);
                errno=EACCES;result=-1;
            }else if(info->si_syscall==SYS_execve){
                result=syscall(SYS_execve,args[0],args[1],args[2]);
            }else{
                result=syscall(SYS_execveat,args[0],args[1],args[2],args[3],args[4]);
            }
            break;
        }
        case SYS_kill:case SYS_tkill:case SYS_tgkill:{
            name=NULL;
            pid_t self_pid=(pid_t)syscall(SYS_getpid);
            bool external=false;
            if(info->si_syscall==SYS_kill){
                pid_t target_pid=(pid_t)args[0];
                external=(target_pid!=self_pid&&target_pid!=0);
            }else if(info->si_syscall==SYS_tgkill){
                pid_t tgid=(pid_t)args[0];
                external=(tgid!=self_pid&&tgid!=-1);
            }
            if(external){
                atomic_fetch_add_explicit(&policy_counts[2],1,memory_order_relaxed);
                if(atomic_load_explicit(&policy_block_kill,memory_order_acquire)){
                    atomic_fetch_add_explicit(&policy_counts[3],1,memory_order_relaxed);
                    errno=EPERM;result=-1;break;
                }
            }
            if(info->si_syscall==SYS_kill)result=syscall(SYS_kill,args[0],args[1]);
            else if(info->si_syscall==SYS_tkill)result=syscall(SYS_tkill,args[0],args[1]);
            else result=syscall(SYS_tgkill,args[0],args[1],args[2]);
            break;
        }
        case SYS_connect:{
            name=NULL;
            bool external_inet=false;
            sa_family_t family=0;
            size_t addrlen=(size_t)args[2];
            if(args[1]&&addrlen>=sizeof(sa_family_t)&&tp_user_read((const void *)args[1],&family,sizeof(family))==0){
                if(family==AF_INET&&addrlen>=sizeof(struct sockaddr_in)){
                    struct sockaddr_in sin;
                    if(tp_user_read((const void *)args[1],&sin,sizeof(sin))==0){
                        uint32_t host_ip=ntohl(sin.sin_addr.s_addr);
                        external_inet=(host_ip!=0&&(host_ip>>24)!=127);
                    }
                }else if(family==AF_INET6&&addrlen>=sizeof(struct sockaddr_in6)){
                    struct sockaddr_in6 sin6;
                    if(tp_user_read((const void *)args[1],&sin6,sizeof(sin6))==0){
                        bool is_loopback=IN6_IS_ADDR_LOOPBACK(&sin6.sin6_addr)||IN6_IS_ADDR_UNSPECIFIED(&sin6.sin6_addr);
                        if(!is_loopback&&IN6_IS_ADDR_V4MAPPED(&sin6.sin6_addr)){
                            is_loopback=(sin6.sin6_addr.s6_addr[12]==127);
                        }
                        external_inet=!is_loopback;
                    }
                }
            }
            if(external_inet){
                atomic_fetch_add_explicit(&policy_counts[4],1,memory_order_relaxed);
                if(atomic_load_explicit(&policy_network_sinkhole,memory_order_acquire)){
                    atomic_fetch_add_explicit(&policy_counts[5],1,memory_order_relaxed);
                    errno=ECONNREFUSED;result=-1;break;
                }
            }
            result=syscall(SYS_connect,args[0],args[1],args[2]);
            break;
        }
        default:result=metadata_path(SYS_inotify_add_watch,(int)args[0],name,NULL,(unsigned)args[2]);break;
    }
    int result_errno=errno;record(info->si_syscall,result<0?-result_errno:result,context->uc_mcontext.pc,name);errno=result_errno;
    context->uc_mcontext.regs[0]=(uint64_t)(result<0?-errno:result);errno=saved;
    return 0;
}
static void dispatch_entry(int signal,siginfo_t *info,void *raw){
    if(!owned_request(signal,info)){forward_signal(signal,info,raw);return;}
    int saved=errno;
    if(tp_native_stack_call((void *)dispatch,signal,(long)info,(long)raw)<0)((ucontext_t *)raw)->uc_mcontext.regs[0]=(uint64_t)-ENOMEM;
    errno=saved;
}
static bool dispatch_special(int signal,siginfo_t *info,void *raw){
    if(!owned_request(signal,info))return false;dispatch_entry(signal,info,raw);return true;
}
int tp_syscall_namespace_install_with_signal_chain(int (*claim)(int,bool (*)(int,siginfo_t *,void *)),int (*reserve_delivery)(void),int (*user_action)(int,const struct sigaction *,struct sigaction *)){
    if(atomic_load_explicit(&installed,memory_order_acquire))return 0;
    if(claim&&!user_action){errno=EINVAL;return -1;}
    chain_user_action=user_action;
    uintptr_t resume=(uintptr_t)tp_kernel_syscall_resume;
    struct sock_filter filter[32];unsigned length=0;
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4);
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(resume>>32),0,2);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer));
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)resume,call_count+4,0);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
    for(unsigned i=0;i<call_count;i++)filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)numbers[i],(uint8_t)(call_count+3-i),0);
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_rt_sigaction,0,2);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0]));
    filter[length++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SIGSYS,1,0);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    filter[length++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP|TP_NAMESPACE_TRAP_DATA);
    struct sock_fprog program={.len=(unsigned short)length,.filter=filter};
    struct sigaction handler;memset(&handler,0,sizeof(handler));handler.sa_sigaction=dispatch_entry;handler.sa_flags=SA_SIGINFO;sigemptyset(&handler.sa_mask);
    if(claim?claim(SIGSYS,dispatch_special)!=0:sigaction(SIGSYS,&handler,&previous)!=0)return -1;
    if(!claim){
        guest_sigsys.handler=(uintptr_t)previous.sa_sigaction;
        guest_sigsys.flags=(unsigned long)previous.sa_flags;
        memcpy(&guest_sigsys.mask,&previous.sa_mask,sizeof(guest_sigsys.mask)<sizeof(previous.sa_mask)?sizeof(guest_sigsys.mask):sizeof(previous.sa_mask));
    }
    if(claim&&(!reserve_delivery||reserve_delivery()!=0))return -1;
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)!=0||syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program)!=0){int saved=errno;if(!claim)sigaction(SIGSYS,&previous,NULL);errno=saved;return -1;}
    atomic_store_explicit(&installed,true,memory_order_release);return 0;
}
int tp_syscall_namespace_install(void){return tp_syscall_namespace_install_with_signal_chain(NULL,NULL,NULL);}
void tp_syscall_namespace_counts(uint64_t counts[4]){for(unsigned i=0;i<4;i++)counts[i]=atomic_load_explicit(&observed[i],memory_order_relaxed);}
void tp_syscall_namespace_enable_audit(void){atomic_store_explicit(&auditing,true,memory_order_release);}

void tp_syscall_namespace_dump(int fd){
    unsigned end=atomic_load_explicit(&audit_sequence,memory_order_acquire),begin=end>64?end-63:1;
    for(unsigned sequence=begin;sequence<=end;sequence++){struct audit_row *row=&audit[sequence%64];bool expected=false;if(!atomic_compare_exchange_strong_explicit(&row->busy,&expected,true,memory_order_acquire,memory_order_relaxed))continue;
        if(row->sequence!=sequence){atomic_store_explicit(&row->busy,false,memory_order_release);continue;}
        char line[400];int size=snprintf(line,sizeof(line),"syscall seq=%u nr=%d result=%ld pc=%lx path=%s\n",sequence,row->number,row->result,(unsigned long)row->pc,row->path);
        if(size>0&&(size_t)size<sizeof(line))syscall(SYS_write,fd,line,(size_t)size);atomic_store_explicit(&row->busy,false,memory_order_release);
    }
}
