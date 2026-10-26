#include <jni.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <sys/syscall.h>

/* A separate explicit test case, never a retry after a private-file denial. */
static int owned_executable_copy(int source){
    struct stat metadata;if(fstat(source,&metadata)||metadata.st_size<=0||metadata.st_size>4*1024*1024){errno=EINVAL;return -1;}
    int result=(int)syscall(SYS_memfd_create,"owned_isolated_library",MFD_CLOEXEC|MFD_ALLOW_SEALING|MFD_EXEC);if(result<0)return -1;
    char buffer[16384];off_t at=0;
    while(at<metadata.st_size){
        size_t size=(size_t)(metadata.st_size-at);if(size>sizeof(buffer))size=sizeof(buffer);
        ssize_t count=pread(source,buffer,size,at);if(count<0&&errno==EINTR)continue;if(count<=0){if(!count)errno=EIO;goto fail;}
        ssize_t written=0;while(written<count){ssize_t n=write(result,buffer+written,(size_t)(count-written));if(n<0&&errno==EINTR)continue;if(n<=0){if(!n)errno=EIO;goto fail;}written+=n;}at+=count;
    }
    int required=F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_EXEC|F_SEAL_SEAL;
    if(fcntl(result,F_ADD_SEALS,required)<0)goto fail;
    int observed=fcntl(result,F_GET_SEALS);if(observed<0||(observed&required)!=required){errno=EPERM;goto fail;}return result;
fail:{int saved=errno;close(result);errno=saved;return -1;}
}

static void *load_fd(int fd,const char *name){android_dlextinfo info={0};info.flags=ANDROID_DLEXT_USE_LIBRARY_FD;info.library_fd=fd;return android_dlopen_ext(name,RTLD_NOW|RTLD_LOCAL,&info);}
JNIEXPORT jint JNICALL Java_dev_tripoder_isolatedfdprobe_NativeChecks_executableCopy(JNIEnv *env,jclass type,jint source){
    (void)type;int fd=owned_executable_copy(source);
    if(fd<0){char message[96];snprintf(message,sizeof(message),"OWNED_SEALED_EXECUTABLE_COPY:errno=%d",errno);jclass error=(*env)->FindClass(env,"java/io/IOException");if(error)(*env)->ThrowNew(env,error,message);}
    return fd;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_isolatedfdprobe_NativeChecks_run(JNIEnv *env,jclass type,jstring command,jint fd,jint dependency){
    (void)type;const char *kind=(*env)->GetStringUTFChars(env,command,NULL);char result[640];int copied=-1,copied_dependency=-1;bool memory=!strncmp(kind,"MEMFD_",6);
    if(memory){
        copied=owned_executable_copy(fd);if(copied<0){snprintf(result,sizeof(result),"FAIL:isolated-sealed-memfd;errno=%d",errno);goto finish;}fd=copied;
        if(dependency>=0){copied_dependency=owned_executable_copy(dependency);if(copied_dependency<0){snprintf(result,sizeof(result),"FAIL:isolated-sealed-dependency;errno=%d",errno);goto finish;}dependency=copied_dependency;}
    }
    if(!strcmp(kind,"READ")){
        char bytes[4];errno=0;ssize_t count=pread(fd,bytes,sizeof(bytes),0);int saved=errno;
        errno=0;void *mapped=mmap(NULL,4096,PROT_READ,MAP_PRIVATE,fd,0);int mapped_errno=errno;
        snprintf(result,sizeof(result),"%s:pread=%zd;errno=%d;mmapErrno=%d",count==4&&mapped!=MAP_FAILED?"PASS":"FAIL",count,saved,mapped==MAP_FAILED?mapped_errno:0);if(mapped!=MAP_FAILED)munmap(mapped,4096);
    }else if(!strcmp(kind,"EXEC_MAP")||!strcmp(kind,"MEMFD_EXEC_MAP")){
        errno=0;void *mapped=mmap(NULL,4096,PROT_READ|PROT_EXEC,MAP_PRIVATE,fd,0);int saved=errno;
        snprintf(result,sizeof(result),"%s:RX;backing=%s;errno=%d;seals=%d",mapped!=MAP_FAILED?"PASS":"FAIL",memory?"isolated-sealed-memfd":"private-file",mapped==MAP_FAILED?saved:0,memory?fcntl(fd,F_GET_SEALS):0);if(mapped!=MAP_FAILED)munmap(mapped,4096);
    }else if(!strcmp(kind,"DLOPEN")||!strcmp(kind,"DLOPEN_DEPENDENCY")||!strcmp(kind,"MEMFD_DLOPEN")||!strcmp(kind,"MEMFD_DEPENDENCY")){
        void *leaf=NULL,*library=NULL;int value=-1;
        if(dependency>=0)leaf=load_fd(dependency,"libowned_fd_payload.so");
        if(dependency<0||leaf)library=load_fd(fd,dependency<0?"libowned_fd_payload.so":"libowned_fd_dependent.so");
        if(library){int(*answer)(void)=(int(*)(void))dlsym(library,dependency<0?"owned_fd_answer":"owned_fd_dependent_answer");if(answer)value=answer();}
        const char *error=dlerror();snprintf(result,sizeof(result),"%s:answer=%d;linker=%.*s",value==37?"PASS":"FAIL",value,440,error?error:"none");
        if(library)dlclose(library);if(leaf)dlclose(leaf);
    }else snprintf(result,sizeof(result),"FAIL:UNKNOWN_NATIVE_CASE");
finish:
    if(copied_dependency>=0)close(copied_dependency);if(copied>=0)close(copied);
    (*env)->ReleaseStringUTFChars(env,command,kind);return (*env)->NewStringUTF(env,result);
}
