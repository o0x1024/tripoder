#define _GNU_SOURCE
#include "park_syscall.h"
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
static void *module;
static int32_t (*add)(int32_t,int32_t),(*count_target)(int32_t),(*error_target)(int32_t);
static uint64_t (*exact)(uint64_t);static double (*fraction)(double);
static _Atomic unsigned calls,errors;
static uint64_t fpcr(void){uint64_t value;__asm__ volatile("mrs %0,fpcr":"=r"(value));return value;}
static uint64_t fpsr(void){uint64_t value;__asm__ volatile("mrs %0,fpsr":"=r"(value));return value;}
static void fp_set(uint64_t control,uint64_t status){__asm__ volatile("msr fpcr,%0\n\tmsr fpsr,%1\n\tisb"::"r"(control),"r"(status):"memory");}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_open(JNIEnv *env,jclass type,jstring library,jstring audit){
    (void)type;if(module)return false;const char *path=(*env)->GetStringUTFChars(env,library,NULL);if(!path)return false;
    module=dlopen(path,RTLD_NOW|RTLD_LOCAL);(*env)->ReleaseStringUTFChars(env,library,path);if(!module)return false;
    void (*set_path)(const char *)=dlsym(module,"tripoder_hook_audit_path"),(*reset)(void)=dlsym(module,"tripoder_hook_reset");
    add=dlsym(module,"tripoder_hook_add");count_target=dlsym(module,"tripoder_hook_count");error_target=dlsym(module,"tripoder_hook_error");
    exact=dlsym(module,"tripoder_hook_exact");fraction=dlsym(module,"tripoder_hook_fraction");
    if(!set_path||!reset||!add||!count_target||!error_target||!exact||!fraction)return false;
    const char *file=(*env)->GetStringUTFChars(env,audit,NULL);if(!file)return false;set_path(file);(*env)->ReleaseStringUTFChars(env,audit,file);reset();return true;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_rvas(JNIEnv *env,jclass type){
    (void)type;Dl_info info;if(!module||!dladdr((void *)add,&info))return NULL;uintptr_t base=(uintptr_t)info.dli_fbase;
    jlong values[4]={(jlong)((uintptr_t)add-base),(jlong)((uintptr_t)exact-base),(jlong)((uintptr_t)fraction-base),(jlong)((uintptr_t)error_target-base)};
    jlongArray output=(*env)->NewLongArray(env,4);if(output)(*env)->SetLongArrayRegion(env,output,0,4,values);return output;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_add(JNIEnv *env,jclass type,jint a,jint b){(void)env;(void)type;errno=17;return add(a,b);}
JNIEXPORT jlong JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_exact(JNIEnv *env,jclass type,jlong value){(void)env;(void)type;errno=17;return (jlong)exact((uint64_t)value);}
JNIEXPORT jdouble JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_fraction(JNIEnv *env,jclass type,jdouble value){(void)env;(void)type;errno=17;return fraction(value);}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_error(JNIEnv *env,jclass type,jint value){(void)env;(void)type;errno=17;return error_target(value);}
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_count(JNIEnv *env,jclass type,jint index){(void)env;(void)type;return count_target(index);}
static void *worker(void *unused){
    (void)unused;for(unsigned i=0;i<100;i++){
        uint64_t control=fpcr(),status=fpsr(),native_control=(control&~UINT64_C(0xc00000))|UINT64_C(0x400000);
        fp_set(native_control,0x10);errno=17;int32_t value=add(3,4);int after=errno;
        if(value!=107||after!=17||fpcr()!=native_control||fpsr()!=0x10)atomic_fetch_add(&errors,1);atomic_fetch_add(&calls,1);fp_set(control,status);
    }return NULL;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_floatingEnvironment(JNIEnv *env,jclass type){
    (void)type;uint64_t control=fpcr(),status=fpsr(),native_control=(control&~UINT64_C(0xc00000))|UINT64_C(0x400000);
    fp_set(native_control,0);double value=fraction(4503599627370496.0);uint64_t bits;__builtin_memcpy(&bits,&value,8);
    jlong values[4]={(jlong)bits,(jlong)native_control,(jlong)fpcr(),(jlong)fpsr()};fp_set(control,status);
    jlongArray result=(*env)->NewLongArray(env,4);if(result)(*env)->SetLongArrayRegion(env,result,0,4,values);return result;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_concurrent(JNIEnv *env,jclass type){
    (void)type;pthread_t threads[4];unsigned made=0;for(;made<4;made++)if(pthread_create(&threads[made],NULL,worker,NULL))break;
    for(unsigned i=0;i<made;i++)if(pthread_join(threads[i],NULL)){(void)tp_park_syscall(SYS_exit_group,225,0,0,0,0,0);__builtin_trap();}
    jlong values[3]={made,atomic_load(&calls),atomic_load(&errors)};jlongArray result=(*env)->NewLongArray(env,3);
    if(result)(*env)->SetLongArrayRegion(env,result,0,3,values);return result;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_NativeJniCaller_close(JNIEnv *env,jclass type){(void)env;(void)type;if(!module||dlclose(module))return false;module=NULL;return true;}
