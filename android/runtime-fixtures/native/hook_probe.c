#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <link.h>
#include <string.h>

static void *module;
static int mapped_library(struct dl_phdr_info *info,size_t size,void *data) {
    (void)size;const char *name=strrchr(info->dlpi_name,'/');
    if(strcmp(name?name+1:info->dlpi_name,"libtripoderownedhooks.so")==0)(*(int *)data)++;
    return 0;
}
JNIEXPORT jint JNICALL Java_dev_tripoder_p2_NativeHooks_mapped(JNIEnv *env,jclass type) {
    (void)env;(void)type;int count=0;dl_iterate_phdr(mapped_library,&count);return count;
}
static void *symbol(JNIEnv *env,const char *name) { void *value=module?dlsym(module,name):NULL;if(!value)(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"OWNED_NATIVE_SYMBOL_MISSING");return value; }
JNIEXPORT void JNICALL Java_dev_tripoder_p2_NativeHooks_open(JNIEnv *env,jclass type,jstring library,jstring audit) {
    (void)type;if(module){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"OWNED_MODULE_ALREADY_LOADED");return;}
    const char *path=(*env)->GetStringUTFChars(env,library,NULL);module=dlopen(path,RTLD_NOW|RTLD_LOCAL);(*env)->ReleaseStringUTFChars(env,library,path);
    if(!module){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),dlerror());return;}
    void (*set_path)(const char *)=symbol(env,"tripoder_hook_audit_path");if(!set_path)return;
    const char *file=(*env)->GetStringUTFChars(env,audit,NULL);set_path(file);(*env)->ReleaseStringUTFChars(env,audit,file);
}
JNIEXPORT void JNICALL Java_dev_tripoder_p2_NativeHooks_close(JNIEnv *env,jclass type) { (void)type;if(!module||dlclose(module)){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"OWNED_DLCLOSE_FAILED");return;}module=NULL; }
JNIEXPORT void JNICALL Java_dev_tripoder_p2_NativeHooks_reset(JNIEnv *env,jclass type) { (void)type;void (*fn)(void)=symbol(env,"tripoder_hook_reset");if(fn)fn(); }
JNIEXPORT jint JNICALL Java_dev_tripoder_p2_NativeHooks_count(JNIEnv *env,jclass type,jint index) { (void)type;int32_t (*fn)(int32_t)=symbol(env,"tripoder_hook_count");return fn?fn(index):0; }
JNIEXPORT jint JNICALL Java_dev_tripoder_p2_NativeHooks_add(JNIEnv *env,jclass type,jint a,jint b) { (void)type;int32_t (*fn)(int32_t,int32_t)=symbol(env,"tripoder_hook_add");return fn?fn(a,b):0; }
JNIEXPORT jlong JNICALL Java_dev_tripoder_p2_NativeHooks_exact(JNIEnv *env,jclass type,jlong value) { (void)type;uint64_t (*fn)(uint64_t)=symbol(env,"tripoder_hook_exact");return fn?(jlong)fn((uint64_t)value):0; }
JNIEXPORT jdouble JNICALL Java_dev_tripoder_p2_NativeHooks_fraction(JNIEnv *env,jclass type,jdouble value) { (void)type;double (*fn)(double)=symbol(env,"tripoder_hook_fraction");return fn?fn(value):0; }
JNIEXPORT jint JNICALL Java_dev_tripoder_p2_NativeHooks_error(JNIEnv *env,jclass type,jint value) { (void)type;int32_t (*fn)(int32_t)=symbol(env,"tripoder_hook_error");return fn?fn(value):0; }
