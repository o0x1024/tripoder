#define _GNU_SOURCE
#include "path_policy.h"
#include "syscall_namespace.h"
#include "io_adapter.h"
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
static int failures,checks;static volatile sig_atomic_t prior_signal;
static void check(const char *name,int ok){printf("{\"name\":\"%s\",\"passed\":%s}\n",name,ok?"true":"false");fflush(stdout);checks++;if(!ok)failures++;}
static long direct(long number,long a,long b,long c,long d){
    register long x0 __asm__("x0")=a,x1 __asm__("x1")=b,x2 __asm__("x2")=c,x3 __asm__("x3")=d,x8 __asm__("x8")=number;
    __asm__ volatile("svc #0":"+r"(x0):"r"(x1),"r"(x2),"r"(x3),"r"(x8):"memory","cc");return x0;
}
static void old_signal(int signal){if(signal==SIGSYS)prior_signal++;}
static volatile long masked_handler_open=-2;
static void masked_handler(int signal){(void)signal;masked_handler_open=direct(SYS_openat,AT_FDCWD,(long)"/data/app/~~code/dev.tripoder.syscallfixture-code/base.apk",O_RDONLY,0);if(masked_handler_open>=0)close((int)masked_handler_open);}
static void *worker(void *unused){(void)unused;long fd=direct(SYS_openat,AT_FDCWD,(long)"/data/app/~~code/dev.tripoder.syscallfixture-code/base.apk",O_RDONLY,0);if(fd>=0)close((int)fd);return (void *)(intptr_t)(fd>=0?1:0);}
static void *small_stack(void *unused){(void)unused;long fd=direct(SYS_openat,AT_FDCWD,(long)"/proc/self/maps",O_RDONLY,0);char bytes[16];long count=fd>=0?read((int)fd,bytes,sizeof(bytes)):-1;if(fd>=0)close((int)fd);return (void *)(intptr_t)(count>0);}
static void *small_adapter(void *unused){(void)unused;int fd=tp_openat(AT_FDCWD,"/proc/self/maps",O_RDONLY,0);char bytes[16];long count=fd>=0?read(fd,bytes,sizeof(bytes)):-1;if(fd>=0)close(fd);return (void *)(intptr_t)(count>0);}
int main(int argc,char **argv){
    if(argc!=2||strncmp(argv[1],"/data/local/tmp/tripoder-syscall-",31))return 2;
    char data[4096],code[4096],file[4096];snprintf(data,sizeof(data),"%s/data",argv[1]);snprintf(code,sizeof(code),"%s/code",argv[1]);
    if(mkdir(argv[1],0700)||mkdir(data,0700)||mkdir(code,0700))return 2;
    snprintf(file,sizeof(file),"%s/code/base.apk",argv[1]);int fd=open(file,O_WRONLY|O_CREAT|O_EXCL,0600);if(fd<0||write(fd,"verified",8)!=8||close(fd))return 2;
    snprintf(file,sizeof(file),"%s/code/lib",argv[1]);if(mkdir(file,0700))return 2;
    struct sigaction action={0};action.sa_handler=old_signal;sigemptyset(&action.sa_mask);if(sigaction(SIGSYS,&action,NULL))return 2;
    if(tp_configure(data,data,code,code,"dev.tripoder.syscallfixture"))return 2;
    check("direct-syscall-namespace-installs",tp_syscall_namespace_install()==0);if(failures)return 1;
    const char *logical="/data/app/~~code/dev.tripoder.syscallfixture-code/base.apk";
    long opened=direct(SYS_openat,AT_FDCWD,(long)logical,O_RDONLY,0);char bytes[9]={0};
    check("direct-openat-reads-verified-code-alias",opened>=0&&read((int)opened,bytes,8)==8&&!strcmp(bytes,"verified"));
    struct stat st;check("direct-fstatat-has-real-inode-and-size",direct(SYS_newfstatat,AT_FDCWD,(long)logical,(long)&st,0)==0&&st.st_size==8);
    check("abi-native-directory-resolves-without-trailing-slash",direct(SYS_newfstatat,AT_FDCWD,(long)"/data/app/~~code/dev.tripoder.syscallfixture-code/lib/arm64",(long)&st,0)==0&&S_ISDIR(st.st_mode));
    struct statfs volume;check("root-volume-metadata-keeps-platform-access",direct(SYS_statfs,(long)"/",(long)&volume,0,0)==0&&volume.f_bsize>0);
    check("direct-faccessat-resolves-code-alias",direct(SYS_faccessat,AT_FDCWD,(long)logical,R_OK,0)==0);
    char link[80],target[4096]={0};snprintf(link,sizeof(link),"/proc/self/fd/%ld",opened);long length=direct(SYS_readlinkat,AT_FDCWD,(long)link,(long)target,sizeof(target)-1);
    check("direct-readlinkat-retains-logical-code-identity",length>0&&!strcmp(target,logical));if(opened>=0)close((int)opened);
    errno=EBUSY;long missing=direct(SYS_openat,AT_FDCWD,(long)"/data/app/~~code/dev.tripoder.syscallfixture-code/missing",O_RDONLY,0);
    check("raw-kernel-error-and-thread-errno-preserved",missing==-ENOENT&&errno==EBUSY);
    pthread_t thread;void *result=NULL;int created=pthread_create(&thread,NULL,worker,NULL);if(!created)pthread_join(thread,&result);
    check("namespace-inherited-by-new-thread",!created&&result==(void *)1);
    pid_t child=fork();int child_status=0;
    if(child==0){pthread_attr_t attr;pthread_attr_init(&attr);pthread_attr_setstacksize(&attr,32768);pthread_t small;void *value=NULL;int error=pthread_create(&small,&attr,small_stack,NULL);pthread_attr_destroy(&attr);if(!error)pthread_join(small,&value);_exit(!error&&value==(void *)1?0:1);}
    if(child>0)waitpid(child,&child_status,0);
    check("direct-proc-read-on-small-native-stack",child>0&&WIFEXITED(child_status)&&WEXITSTATUS(child_status)==0);
    child=fork();child_status=0;
    if(child==0){pthread_attr_t attr;pthread_attr_init(&attr);pthread_attr_setstacksize(&attr,32768);pthread_t small;void *value=NULL;int error=pthread_create(&small,&attr,small_adapter,NULL);pthread_attr_destroy(&attr);if(!error)pthread_join(small,&value);_exit(!error&&value==(void *)1?0:1);}
    if(child>0)waitpid(child,&child_status,0);
    check("libc-adapter-proc-read-on-small-native-stack",child>0&&WIFEXITED(child_status)&&WEXITSTATUS(child_status)==0);
    check("invalid-path-pointer-returns-kernel-efault",direct(SYS_openat,AT_FDCWD,1,O_RDONLY,0)==-EFAULT);
    check("invalid-readlink-output-returns-kernel-efault",direct(SYS_readlinkat,AT_FDCWD,(long)"/proc/self/exe",1,4096)==-EFAULT);
    raise(SIGSYS);check("unrelated-sigsys-reaches-previous-handler",prior_signal==1);
    struct sigaction masked={0};masked.sa_handler=masked_handler;sigfillset(&masked.sa_mask);struct sigaction readback={0};
    check("handler-registered-with-full-mask-succeeds",sigaction(SIGUSR1,&masked,NULL)==0&&sigaction(SIGUSR1,NULL,&readback)==0);
    check("registered-handler-mask-never-blocks-sigsys",!sigismember(&readback.sa_mask,SIGSYS)&&sigismember(&readback.sa_mask,SIGUSR2));
    raise(SIGUSR1);check("namespaced-syscall-inside-full-mask-handler-survives",masked_handler_open>=0);
    uint64_t counts[4];tp_syscall_namespace_counts(counts);check("all-four-direct-entry-families-observed",counts[0]>=3&&counts[1]>=1&&counts[2]>=1&&counts[3]>=1);
    printf("{\"summary\":true,\"passed\":%s,\"checks\":%d,\"failures\":%d}\n",failures?"false":"true",checks,failures);return failures?1:0;
}
