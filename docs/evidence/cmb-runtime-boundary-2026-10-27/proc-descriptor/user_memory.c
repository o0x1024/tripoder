#define _GNU_SOURCE
#include "user_memory.h"
#include "kernel_syscall.h"
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>
#define syscall tp_kernel_syscall

/* Temporary execution stacks for path/proc policy. Policy needs tens of KiB while a caller may be on the smallest pthread or
 * signal stack, and may not use malloc or TLS. Mapping and unmapping a stack per call cost two system calls, page faults for
 * every touched page and a cross-CPU TLB shootdown, so released stacks are kept in a fixed lock-free pool. A full pool or an
 * empty one falls back to the same map/unmap as before. Only atomics and kernel calls are used: signal handlers may enter. */
#define STACK_BYTES 0x40000UL
#define STACK_POOL 32
static _Atomic uintptr_t stack_pool[STACK_POOL];
uintptr_t tp_stack_acquire(void){
    /* Start at a slot derived from this thread's stack position so concurrent callers rarely contend on one entry. */
    unsigned start=(unsigned)(((uintptr_t)__builtin_frame_address(0)>>12)%STACK_POOL);
    for(unsigned i=0;i<STACK_POOL;i++){
        _Atomic uintptr_t *slot=&stack_pool[(start+i)%STACK_POOL];
        uintptr_t value=atomic_load_explicit(slot,memory_order_relaxed);
        if(value&&atomic_compare_exchange_strong_explicit(slot,&value,0,memory_order_acquire,memory_order_relaxed))return value;
    }
    long mapped=syscall(SYS_mmap,0,STACK_BYTES,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    return mapped==-1?0:(uintptr_t)mapped;
}
void tp_stack_release(uintptr_t base){
    unsigned start=(unsigned)(((uintptr_t)__builtin_frame_address(0)>>12)%STACK_POOL);
    for(unsigned i=0;i<STACK_POOL;i++){
        _Atomic uintptr_t *slot=&stack_pool[(start+i)%STACK_POOL];uintptr_t empty=0;
        if(atomic_compare_exchange_strong_explicit(slot,&empty,base,memory_order_release,memory_order_relaxed))return;
    }
    syscall(SYS_munmap,base,STACK_BYTES);
}

int tp_user_read(const void *source,void *target,size_t size){
    if(!size)return 0;int descriptors[2];if(syscall(SYS_pipe2,descriptors,O_CLOEXEC|O_NONBLOCK)<0)return -1;
    int result=0,saved=0;size_t used=0;
    while(used<size){size_t count=size-used;if(count>4096)count=4096;
        ssize_t written;do{written=syscall(SYS_write,descriptors[1],(const char *)source+used,count);}while(written<0&&errno==EINTR);
        if(written!=(ssize_t)count){saved=written<0?errno:EFAULT;result=-1;break;}
        ssize_t copied;do{copied=syscall(SYS_read,descriptors[0],(char *)target+used,count);}while(copied<0&&errno==EINTR);
        if(copied!=(ssize_t)count){saved=copied<0?errno:EFAULT;result=-1;break;}used+=count;
    }
    syscall(SYS_close,descriptors[0]);syscall(SYS_close,descriptors[1]);if(result<0)errno=saved;return result;
}

/* Kernel user-copy validates caller pointers without installing a SIGSEGV
 * handler or reading past a readable page. Pipes are private and always closed. */
static int user_string_pipe(const char *source,char *target,size_t capacity);
int tp_user_string(const char *source,char *target,size_t capacity){
    if(!capacity){errno=ENAMETOOLONG;return -1;}if(!source){errno=EFAULT;return -1;}
    /* One kernel copy per page instead of pipe2+write+read+close+close. The kernel validates the source exactly as the pipe
     * write does: an unmapped byte fails with EFAULT, never a signal. Where the call is not permitted the pipe path remains. */
    size_t used=0;
    while(used<capacity){
        uintptr_t address=(uintptr_t)source+used;size_t count=4096-(address&4095u);if(count>capacity-used)count=capacity-used;
        struct iovec local={target+used,count},remote={(void *)address,count};
        long copied=syscall(SYS_process_vm_readv,syscall(SYS_getpid),&local,1,&remote,1,0);
        if(copied<0&&(errno==ENOSYS||errno==EPERM||errno==EACCES))return user_string_pipe(source,target,capacity);
        if(copied!=(long)count){int saved=copied<0?errno:EFAULT;if(used<capacity)target[capacity-1]=0;errno=saved;return -1;}
        if(memchr(target+used,0,count))return 0;used+=count;
    }
    target[capacity-1]=0;errno=ENAMETOOLONG;return -1;
}
static int user_string_pipe(const char *source,char *target,size_t capacity){
    int descriptors[2];if(syscall(SYS_pipe2,descriptors,O_CLOEXEC|O_NONBLOCK)<0)return -1;
    int result=-1,saved=ENAMETOOLONG;size_t used=0;
    while(used<capacity){
        uintptr_t address=(uintptr_t)source+used;size_t count=4096-(address&4095u);if(count>capacity-used)count=capacity-used;
        ssize_t written;do{written=syscall(SYS_write,descriptors[1],(const void *)address,count);}while(written<0&&errno==EINTR);
        if(written!=(ssize_t)count){saved=written<0?errno:EFAULT;break;}
        ssize_t copied;do{copied=syscall(SYS_read,descriptors[0],target+used,count);}while(copied<0&&errno==EINTR);
        if(copied!=(ssize_t)count){saved=copied<0?errno:EFAULT;break;}
        if(memchr(target+used,0,count)){result=0;break;}used+=count;
    }
    if(result<0)target[capacity-1]=0;
    syscall(SYS_close,descriptors[0]);syscall(SYS_close,descriptors[1]);if(result<0)errno=saved;return result;
}
int tp_user_write(void *target,const void *source,size_t size){
    if(!size)return 0;int descriptors[2];if(syscall(SYS_pipe2,descriptors,O_CLOEXEC|O_NONBLOCK)<0)return -1;
    int result=0,saved=0;size_t used=0;
    while(used<size){size_t count=size-used;if(count>4096)count=4096;
        ssize_t written;do{written=syscall(SYS_write,descriptors[1],(const char *)source+used,count);}while(written<0&&errno==EINTR);
        if(written!=(ssize_t)count){saved=written<0?errno:EFAULT;result=-1;break;}
        ssize_t copied;do{copied=syscall(SYS_read,descriptors[0],(char *)target+used,count);}while(copied<0&&errno==EINTR);
        if(copied!=(ssize_t)count){saved=copied<0?errno:EFAULT;result=-1;break;}used+=count;
    }
    syscall(SYS_close,descriptors[0]);syscall(SYS_close,descriptors[1]);if(result<0)errno=saved;return result;
}
