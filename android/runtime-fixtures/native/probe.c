#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>

JNIEXPORT jstring JNICALL Java_dev_tripoder_p0_shared_NativeProbe_writeRead(JNIEnv *env,jclass cls,jstring path,jstring text) {
    (void)cls;
    const char *p=(*env)->GetStringUTFChars(env,path,0),*t=(*env)->GetStringUTFChars(env,text,0);
    int fd=open(p,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC,0600);size_t len=strlen(t);int ok=fd>=0&&len<4096;
    if(ok)ok=write(fd,t,len)==(ssize_t)len&&fsync(fd)==0;
    if(fd>=0)close(fd);
    char buf[4096];ssize_t n=-1;if(ok){fd=open(p,O_RDONLY|O_CLOEXEC);if(fd>=0){n=read(fd,buf,sizeof(buf)-1);close(fd);}}
    (*env)->ReleaseStringUTFChars(env,path,p);(*env)->ReleaseStringUTFChars(env,text,t);
    if(n<0){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"JNI_FILE_FAILURE");return 0;}
    buf[n]=0;return (*env)->NewStringUTF(env,buf);
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_p0_shared_NativeProbe_canRead(JNIEnv *env,jclass cls,jstring path) {
    (void)cls;if(!path)return JNI_FALSE;const char *p=(*env)->GetStringUTFChars(env,path,0);int fd=open(p,O_RDONLY|O_CLOEXEC);(*env)->ReleaseStringUTFChars(env,path,p);if(fd<0)return JNI_FALSE;char c;int ok=read(fd,&c,1)==1;close(fd);return ok?JNI_TRUE:JNI_FALSE;
}
