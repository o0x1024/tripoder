// Times the primitives a path-policy layer can use to copy a caller's string safely.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1e9+t.tv_nsec;}

static int copy_pipe_fresh(const char *source,char *target,size_t capacity){
    int d[2];if(syscall(SYS_pipe2,d,O_CLOEXEC|O_NONBLOCK)<0)return -1;
    size_t count=4096-((uintptr_t)source&4095u);if(count>capacity)count=capacity;
    ssize_t w=syscall(SYS_write,d[1],source,count),r=w==(ssize_t)count?syscall(SYS_read,d[0],target,count):-1;
    syscall(SYS_close,d[0]);syscall(SYS_close,d[1]);return r==(ssize_t)count&&memchr(target,0,count)?0:-1;
}
static int copy_pipe_kept(int *d,const char *source,char *target,size_t capacity){
    size_t count=4096-((uintptr_t)source&4095u);if(count>capacity)count=capacity;
    ssize_t w=syscall(SYS_write,d[1],source,count),r=w==(ssize_t)count?syscall(SYS_read,d[0],target,count):-1;
    return r==(ssize_t)count&&memchr(target,0,count)?0:-1;
}
static int copy_pvr(const char *source,char *target,size_t capacity){
    size_t count=4096-((uintptr_t)source&4095u);if(count>capacity)count=capacity;
    struct iovec local={target,count},remote={(void *)source,count};
    ssize_t r=syscall(SYS_process_vm_readv,getpid(),&local,1,&remote,1,0);
    return r==(ssize_t)count&&memchr(target,0,count)?0:-1;
}

JNIEXPORT jstring JNICALL Java_dev_tripoder_perffixture_NativeProbe_run(JNIEnv *env,jclass type,jstring java_path){
    (void)type;
    const char *path=(*env)->GetStringUTFChars(env,java_path,NULL);char text[1024],buffer[4096];
    char local[256];snprintf(local,sizeof local,"%s",path);
    const int N=100000;double a,b,c,d,e,f;int bad;
    for(int i=0;i<2000;i++)faccessat(AT_FDCWD,local,F_OK,0);
    a=now();for(int i=0;i<N;i++)faccessat(AT_FDCWD,local,F_OK,0);a=(now()-a)/N;
    b=now();bad=0;for(int i=0;i<N;i++)bad+=copy_pipe_fresh(local,buffer,sizeof buffer);b=(now()-b)/N;int bad_b=bad;
    int d2[2];syscall(SYS_pipe2,d2,O_CLOEXEC|O_NONBLOCK);
    c=now();bad=0;for(int i=0;i<N;i++)bad+=copy_pipe_kept(d2,local,buffer,sizeof buffer);c=(now()-c)/N;int bad_c=bad;
    d=now();bad=0;for(int i=0;i<N;i++)bad+=copy_pvr(local,buffer,sizeof buffer);d=(now()-d)/N;int bad_d=bad;
    errno=0;int sanity=copy_pvr((const char *)8,buffer,sizeof buffer);int sanity_errno=errno;
    errno=0;int sanity2=copy_pipe_kept(d2,(const char *)8,buffer,sizeof buffer);int sanity2_errno=errno;
    /* libc-level file operations (hooked in a guest): open+read+close, stat, rename pair, fsync */
    char dir[256];snprintf(dir,sizeof dir,"%s",local);char *slash=strrchr(dir,'/');if(slash)*slash=0;
    char other[300];snprintf(other,sizeof other,"%s/probe-rename",dir);
    char io[4096];int M=20000;double g,h,k,l;
    g=now();for(int i=0;i<M;i++){int fd=open(local,O_RDONLY|O_CLOEXEC);if(fd>=0){read(fd,io,sizeof io);close(fd);}}g=(now()-g)/M;
    struct stat st;h=now();for(int i=0;i<M;i++)stat(local,&st);h=(now()-h)/M;
    int f2=open(other,O_CREAT|O_WRONLY|O_CLOEXEC,0600);if(f2>=0)close(f2);
    char other2[310];snprintf(other2,sizeof other2,"%s2",other);
    k=now();for(int i=0;i<M/4;i++){rename(other,other2);rename(other2,other);}k=(now()-k)/(M/4*2);
    int f3=open(other,O_WRONLY|O_CLOEXEC);
    l=now();for(int i=0;i<M/10;i++){write(f3,io,16);fsync(f3);}l=(now()-l)/(M/10);if(f3>=0)close(f3);unlink(other);
    e=now();for(int i=0;i<N;i++)memchr(local,0,sizeof local);e=(now()-e)/N;
    f=now();for(int i=0;i<N;i++)syscall(SYS_getpid);f=(now()-f)/N;
    snprintf(text,sizeof text,"faccessat=%.0fns pipeFresh=%.0fns(bad=%d) pipeKept=%.0fns(bad=%d) processVmReadv=%.0fns(bad=%d) pvrInvalidPtr=%d/errno%d pipeInvalidPtr=%d/errno%d memchr=%.1fns getpid=%.0fns | openReadClose=%.0fns stat=%.0fns rename=%.0fns writeFsync=%.0fns",a,b,bad_b,c,bad_c,d,bad_d,sanity,sanity_errno,sanity2,sanity2_errno,e,f,g,h,k,l);
    (*env)->ReleaseStringUTFChars(env,java_path,path);
    return (*env)->NewStringUTF(env,text);
}
