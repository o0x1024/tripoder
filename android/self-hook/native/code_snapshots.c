#define _GNU_SOURCE
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>

/* Linux ARM64 process_vm_readv is deliberately used instead of dereferencing
 * speculative runtime pointers. Stale/unmapped addresses return failure, never
 * SIGSEGV. No global ART policy, code pages, or process suspension is changed. */
struct tp_snapshot_iovec {void *base;size_t size;};
static int read_self(uintptr_t address,void *destination,size_t size){
    address&=UINT64_C(0x00ffffffffffffff);
    if(address<4096||size>64u*1024u*1024u||address>UINTPTR_MAX-size)return 0;
    struct tp_snapshot_iovec local={destination,size},remote={(void *)address,size};
    long count=syscall(270,syscall(SYS_getpid),&local,1,&remote,1,0);
    return count>=0&&(size_t)count==size;
}
static uint32_t u32(const uint8_t *bytes){uint32_t value;memcpy(&value,bytes,4);return value;}
struct dex_view {uintptr_t begin;uint32_t size,version;uint8_t header[112];};
static int view(uintptr_t object,struct dex_view *out){
    uintptr_t words[16];if(!read_self(object,words,sizeof(words)))return 0;
    /* ART DexFile has a polymorphic prefix. Validate candidate begin fields
     * structurally rather than trusting an offset from another ART version. */
    for(unsigned i=1;i<16;i++){
        uint8_t header[112];if(!read_self(words[i],header,sizeof(header)))continue;
        if(memcmp(header,"dex\n",4)||header[7]||header[4]!='0'||header[5]<'3'||header[5]>'4'||header[6]<'0'||header[6]>'9')continue;
        uint32_t version=(uint32_t)(header[4]-'0')*100u+(uint32_t)(header[5]-'0')*10u+(uint32_t)(header[6]-'0');
        uint32_t size=u32(header+32);
        if(version<35||version>40||version==36||size<112||size>64u*1024u*1024u||u32(header+36)!=112||u32(header+40)!=0x12345678u)continue;
        int valid=1;const unsigned offsets[]={56,64,72,80,88,96};const unsigned widths[]={4,4,12,8,8,32};
        for(unsigned n=0;n<6;n++){uint32_t count=u32(header+offsets[n]),offset=u32(header+offsets[n]+4);if((uint64_t)offset+(uint64_t)count*widths[n]>size||(count&&offset<112))valid=0;}
        if(!valid)continue;
        out->begin=words[i];out->size=size;out->version=version;memcpy(out->header,header,112);return 1;
    }
    return 0;
}
JNIEXPORT jobjectArray JNICALL Java_dev_tripoder_selfhook_CodeSnapshots_dexFiles(JNIEnv *env,jclass unused,jobject loader){
    (void)unused;jclass base=(*env)->FindClass(env,"dalvik/system/BaseDexClassLoader");
    if(!base||!(*env)->IsInstanceOf(env,loader,base))return NULL;
    jfieldID field=(*env)->GetFieldID(env,base,"pathList","Ldalvik/system/DexPathList;");if(!field)return NULL;
    jobject path=(*env)->GetObjectField(env,loader,field);if(!path)return NULL;
    jclass type=(*env)->GetObjectClass(env,path);field=(*env)->GetFieldID(env,type,"dexElements","[Ldalvik/system/DexPathList$Element;");if(!field)return NULL;
    jobjectArray elements=(*env)->GetObjectField(env,path,field);if(!elements)return NULL;
    jsize count=(*env)->GetArrayLength(env,elements);if(count>256)return NULL;
    jobjectArray result=(*env)->NewObjectArray(env,count,(*env)->FindClass(env,"java/lang/Object"),NULL);if(!result)return NULL;
    for(jsize i=0;i<count;i++){
        jobject element=(*env)->GetObjectArrayElement(env,elements,i);if(!element)continue;
        jclass cls=(*env)->GetObjectClass(env,element);jfieldID dex=(*env)->GetFieldID(env,cls,"dexFile","Ldalvik/system/DexFile;");if(!dex)return NULL;
        jobject value=(*env)->GetObjectField(env,element,dex);(*env)->SetObjectArrayElement(env,result,i,value);
        if(value)(*env)->DeleteLocalRef(env,value);(*env)->DeleteLocalRef(env,cls);(*env)->DeleteLocalRef(env,element);
    }
    return result;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhook_CodeSnapshots_cookies(JNIEnv *env,jclass unused,jobject dex){
    (void)unused;if(!dex)return NULL;jclass type=(*env)->GetObjectClass(env,dex);jfieldID field=(*env)->GetFieldID(env,type,"mCookie","Ljava/lang/Object;");if(!field)return NULL;
    jobject value=(*env)->GetObjectField(env,dex,field);jclass longs=(*env)->FindClass(env,"[J");
    return value&&(*env)->IsInstanceOf(env,value,longs)?(jlongArray)value:NULL;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhook_CodeSnapshots_inspect(JNIEnv *env,jclass unused,jlong pointer){
    (void)unused;struct dex_view dex;if(!view((uintptr_t)pointer,&dex))return NULL;
    jlong values[]={(jlong)dex.begin,(jlong)dex.size,(jlong)dex.version};jlongArray result=(*env)->NewLongArray(env,3);if(result)(*env)->SetLongArrayRegion(env,result,0,3,values);return result;
}
JNIEXPORT jbyteArray JNICALL Java_dev_tripoder_selfhook_CodeSnapshots_copy(JNIEnv *env,jclass unused,jlong pointer,jint maximum){
    (void)unused;struct dex_view before,after;if(maximum<112||maximum>64*1024*1024||!view((uintptr_t)pointer,&before)||before.size>(uint32_t)maximum)return NULL;
    uint8_t *bytes=malloc(before.size);if(!bytes)return NULL;
    if(!read_self(before.begin,bytes,before.size)||!view((uintptr_t)pointer,&after)||before.begin!=after.begin||before.size!=after.size||memcmp(before.header,after.header,112)||memcmp(bytes,before.header,112)){free(bytes);return NULL;}
    jbyteArray result=(*env)->NewByteArray(env,(jsize)before.size);if(result)(*env)->SetByteArrayRegion(env,result,0,(jsize)before.size,(const jbyte *)bytes);free(bytes);return result;
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhook_CodeSnapshots_writeArtifact(JNIEnv *env,jclass unused,jint directory,jstring input,jbyteArray bytes){
    (void)unused;const char *name=(*env)->GetStringUTFChars(env,input,NULL);if(!name)return;
    size_t length=strlen(name);int valid=directory>=0&&length>5&&length<=85;const char *suffix=strrchr(name,'.');
    if(!suffix||(strcmp(suffix,".dex")&&strcmp(suffix,".json")))valid=0;
    if(valid)for(const char *c=name;c<suffix;c++)if(!((*c>='A'&&*c<='Z')||(*c>='a'&&*c<='z')||(*c>='0'&&*c<='9')||*c=='_'||*c=='-'))valid=0;
    jsize size=(*env)->GetArrayLength(env,bytes);if(size<1||size>64*1024*1024)valid=0;
    if(!valid){(*env)->ReleaseStringUTFChars(env,input,name);(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"ARTIFACT_WRITE_ARGUMENT");return;}
    char temporary[96];snprintf(temporary,sizeof(temporary),"%s.tmp",name);
    int fd=(int)syscall(SYS_openat,directory,temporary,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0600),ok=fd>=0;
    jbyte buffer[16384];
    for(jsize offset=0;ok&&offset<size;){jsize count=size-offset;if(count>(jsize)sizeof(buffer))count=(jsize)sizeof(buffer);
        (*env)->GetByteArrayRegion(env,bytes,offset,count,buffer);if((*env)->ExceptionCheck(env)){ok=0;break;}
        for(jsize done=0;done<count;){long wrote=syscall(SYS_write,fd,buffer+done,(size_t)(count-done));if(wrote<0&&errno==EINTR)continue;if(wrote<=0){ok=0;break;}done+=(jsize)wrote;}offset+=count;
    }
    if(ok&&syscall(SYS_fsync,fd)<0)ok=0;if(fd>=0)syscall(SYS_close,fd);
    if(ok&&syscall(SYS_renameat,directory,temporary,directory,name)<0)ok=0;
    if(ok&&syscall(SYS_fsync,directory)<0)ok=0;
    if(!ok)syscall(SYS_unlinkat,directory,temporary,0);
    (*env)->ReleaseStringUTFChars(env,input,name);
    if(!ok&&!(*env)->ExceptionCheck(env))(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"ARTIFACT_DESCRIPTOR_WRITE_FAILED");
}
