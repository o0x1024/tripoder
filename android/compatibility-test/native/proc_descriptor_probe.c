#define _GNU_SOURCE
#include "kernel_syscall.h"
#include "path_policy.h"
#include "io_adapter.h"
#include <fcntl.h>
#include <linux/magic.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <unistd.h>

struct observation {long filesystem,size;int mode;bool kernel_path;};
static struct observation observe(int fd){
    struct observation value={0};struct stat st;struct statfs fs;char name[64],path[256];
    if(fd<0)return value;
    if(tp_kernel_syscall(SYS_fstat,fd,&st)==0)value.size=st.st_size;
    if(tp_kernel_syscall(SYS_fstatfs,fd,&fs)==0)value.filesystem=fs.f_type;
    value.mode=(int)tp_kernel_syscall(SYS_fcntl,fd,F_GETFL,0)&O_ACCMODE;
    snprintf(name,sizeof(name),"/proc/self/fd/%d",fd);
    long n=tp_kernel_syscall(SYS_readlinkat,AT_FDCWD,name,path,sizeof(path)-1);
    if(n>0){path[n]=0;value.kernel_path=strncmp(path,"/proc/",6)==0&&strstr(path,"/maps")!=NULL;}
    return value;
}
int main(int argc,char **argv){
    if(argc!=2||strncmp(argv[1],"/data/local/tmp/tripoder-owned-proc-",34)||strlen(argv[1])>180)return 64;
    char data[256],artifacts[256];snprintf(data,sizeof(data),"%s/data",argv[1]);snprintf(artifacts,sizeof(artifacts),"%s/artifacts",argv[1]);
    if(mkdir(argv[1],0700)||mkdir(data,0700)||mkdir(artifacts,0700))return 65;
    if(tp_configure(data,data,artifacts,artifacts,"dev.tripoder.ownedproc"))return 66;
    int raw=(int)tp_kernel_syscall(SYS_openat,AT_FDCWD,"/proc/self/maps",O_RDONLY|O_CLOEXEC,0);
    int mediated=tp_openat(AT_FDCWD,"/proc/self/maps",O_RDONLY|O_CLOEXEC,0);
    struct observation a=observe(raw),b=observe(mediated);
    bool passed=raw>=0&&mediated>=0&&a.filesystem==PROC_SUPER_MAGIC&&a.size==0&&a.mode==O_RDONLY&&a.kernel_path&&
        a.filesystem==b.filesystem&&a.size==b.size&&a.mode==b.mode&&b.kernel_path;
    printf("{\"raw\":{\"filesystem\":%ld,\"size\":%ld,\"accessMode\":%d,\"kernelProcPath\":%s},\"mediated\":{\"filesystem\":%ld,\"size\":%ld,\"accessMode\":%d,\"kernelProcPath\":%s},\"passed\":%s}\n",a.filesystem,a.size,a.mode,a.kernel_path?"true":"false",b.filesystem,b.size,b.mode,b.kernel_path?"true":"false",passed?"true":"false");
    if(raw>=0)close(raw);if(mediated>=0)close(mediated);return passed?0:1;
}
