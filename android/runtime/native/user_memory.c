#define _GNU_SOURCE
#include "user_memory.h"
#include "kernel_syscall.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
#define syscall tp_kernel_syscall

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
int tp_user_string(const char *source,char *target,size_t capacity){
    if(!capacity){errno=ENAMETOOLONG;return -1;}if(!source){errno=EFAULT;return -1;}
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
