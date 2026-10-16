#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

struct report { char json[8192]; size_t used; int count; };
static void check(struct report *r,const char *name,int passed){int n=snprintf(r->json+r->used,sizeof(r->json)-r->used,"%s{\"name\":\"%s\",\"passed\":%s,\"observed\":%d}",r->count++?",":"",name,passed?"true":"false",errno);if(n<=0||(size_t)n>=sizeof(r->json)-r->used)_exit(80);r->used+=(size_t)n;}
JNIEXPORT jstring JNICALL Java_dev_tripoder_p2_NativeStorage_run(JNIEnv *env,jclass cls,jstring package,jstring code,jstring token){
    (void)cls;const char *pkg=(*env)->GetStringUTFChars(env,package,0),*apk=(*env)->GetStringUTFChars(env,code,0),*value=(*env)->GetStringUTFChars(env,token,0);
    char root[PATH_MAX],path[PATH_MAX],alias[PATH_MAX],escape[PATH_MAX],buffer[256]={0};struct report report={.json="[",.used=1,.count=0};size_t length=strlen(value);
    snprintf(root,sizeof(root),"/data/data/%s/files/native",pkg);int created=mkdir(root,0700);check(&report,"jni-constructs-logical-directory",created==0||errno==EEXIST);
    snprintf(path,sizeof(path),"/data/data/%s/files/native/bytes",pkg);int fd=open(path,O_RDWR|O_CREAT|O_TRUNC|O_CLOEXEC,0600);check(&report,"jni-logical-open-write",fd>=0&&length<sizeof(buffer)&&write(fd,value,length)==(ssize_t)length&&fsync(fd)==0);
    snprintf(alias,sizeof(alias),"/data/user/0/%s/files/native/bytes",pkg);int readfd=open(alias,O_RDONLY|O_CLOEXEC);check(&report,"jni-alias-same-bytes",readfd>=0&&read(readfd,buffer,sizeof(buffer))==(ssize_t)length&&!memcmp(buffer,value,length));if(readfd>=0)close(readfd);
    int dir=open(root,O_PATH|O_DIRECTORY|O_CLOEXEC);readfd=openat(dir,"bytes",O_RDONLY);memset(buffer,0,sizeof(buffer));check(&report,"jni-openat-real-dirfd",dir>=0&&readfd>=0&&read(readfd,buffer,sizeof(buffer))==(ssize_t)length&&!memcmp(buffer,value,length));if(readfd>=0)close(readfd);
    int unknown=open("/data/data/tripoder.other.private/unknown",O_RDONLY);check(&report,"jni-unknown-private-path-denied",unknown==-1&&errno==EACCES);if(unknown>=0)close(unknown);
    snprintf(escape,sizeof(escape),"/data/data/%s/../escape",pkg);unknown=open(escape,O_CREAT|O_WRONLY,0600);check(&report,"jni-logical-root-escape-denied",unknown==-1&&errno==EACCES);if(unknown>=0)close(unknown);
    unknown=open(apk,O_WRONLY|O_TRUNC);check(&report,"jni-code-is-readonly",unknown==-1&&errno==EROFS);if(unknown>=0)close(unknown);
    int initial=openat(dir,"fdvalue",O_RDWR|O_CREAT|O_TRUNC,0600);int dupped=initial<0?-1:dup(initial);if(initial>=0)close(initial);
    check(&report,"jni-dup-after-close",dupped>=0&&write(dupped,value,length)==(ssize_t)length);
    check(&report,"jni-rename-unlink",renameat(dir,"fdvalue",dir,"renamed")==0&&unlinkat(dir,"renamed",0)==0);
    memset(buffer,0,sizeof(buffer));check(&report,"jni-open-fd-survives-unlink",dupped>=0&&lseek(dupped,0,SEEK_SET)==0&&read(dupped,buffer,sizeof(buffer))==(ssize_t)length&&!memcmp(buffer,value,length));if(dupped>=0)close(dupped);
    int mapped=openat(dir,"mapped",O_RDWR|O_CREAT|O_TRUNC,0600);int sized=mapped>=0&&ftruncate(mapped,4096)==0;void *view=sized?mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED,mapped,0):MAP_FAILED;if(mapped>=0)close(mapped);
    check(&report,"jni-mmap-after-close",view!=MAP_FAILED);if(view!=MAP_FAILED){memcpy(view,"mapped",6);check(&report,"jni-msync-real-mapping",msync(view,4096,MS_SYNC)==0);munmap(view,4096);}
    readfd=openat(dir,"mapped",O_RDONLY);memset(buffer,0,sizeof(buffer));check(&report,"jni-shared-mmap-reopen",readfd>=0&&read(readfd,buffer,6)==6&&!memcmp(buffer,"mapped",6));if(readfd>=0)close(readfd);
    if(fd>=0)close(fd);if(dir>=0)close(dir);report.json[report.used++]=']';report.json[report.used]='\0';
    (*env)->ReleaseStringUTFChars(env,package,pkg);(*env)->ReleaseStringUTFChars(env,code,apk);(*env)->ReleaseStringUTFChars(env,token,value);return (*env)->NewStringUTF(env,report.json);
}
/* Controlled fault injection: prove the wrapper still refuses an existing link.
 * Direct syscalls are intentionally possible under the same UID; not a runtime backend. */
JNIEXPORT void JNICALL Java_dev_tripoder_p2_NativeStorage_rawSymlink(JNIEnv *env,jclass cls,jstring target,jstring path){
    (void)cls;const char *t=(*env)->GetStringUTFChars(env,target,0),*p=(*env)->GetStringUTFChars(env,path,0);int result=(int)syscall(SYS_symlinkat,t,AT_FDCWD,p);(*env)->ReleaseStringUTFChars(env,target,t);(*env)->ReleaseStringUTFChars(env,path,p);if(result<0)(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"OWNED_SYMLINK_INJECTION_FAILED");
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_p0_shared_NativeProbe_canReadRaw(JNIEnv *env,jclass cls,jstring path){
    (void)cls;if(!path)return JNI_FALSE;const char *p=(*env)->GetStringUTFChars(env,path,0);int fd=(int)syscall(SYS_openat,AT_FDCWD,p,O_RDONLY|O_CLOEXEC,0);(*env)->ReleaseStringUTFChars(env,path,p);if(fd<0)return JNI_FALSE;char c;int ok=read(fd,&c,1)==1;close(fd);return ok?JNI_TRUE:JNI_FALSE;
}
