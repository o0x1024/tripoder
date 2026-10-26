#include <jni.h>
#include <stdint.h>

#ifdef OWNED_NAMED
#define ID owned_jni_named_identity
#define COUNT owned_jni_named_count
#define READY owned_jni_named_ready
#define ENTRY Java_dev_tripoder_isolatedfdpayload_NativePayload_namedDriverState
#else
#define ID owned_jni_identity
#define COUNT owned_jni_count
#define READY owned_jni_ready
#define ENTRY Java_dev_tripoder_isolatedfdpayload_NativePayload_driverState
#endif
extern uintptr_t ID(void);
extern int COUNT(void);
extern int READY(void);
static JavaVM *vm;
static jclass defining_class;
static int loads;
/* Own JNI_OnLoad prevents dlsym from implicitly selecting the dependency's. */
JNIEXPORT jint JNI_OnLoad(JavaVM *owner,void *reserved){
    (void)reserved;JNIEnv *env=NULL;
    if((*owner)->GetEnv(owner,(void**)&env,JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
    jclass found=(*env)->FindClass(env,"dev/tripoder/isolatedfdpayload/NativePayload");
    if(!found)return JNI_ERR;defining_class=(*env)->NewGlobalRef(env,found);(*env)->DeleteLocalRef(env,found);
    if(!defining_class)return JNI_ERR;vm=owner;loads++;return JNI_VERSION_1_6;
}
JNIEXPORT jlongArray JNICALL ENTRY(JNIEnv *env,jclass type){
    jlong state[]={ID(),COUNT(),READY(),loads,defining_class&&(*env)->IsSameObject(env,type,defining_class),vm!=NULL};
    jlongArray result=(*env)->NewLongArray(env,6);if(result)(*env)->SetLongArrayRegion(env,result,0,6,state);return result;
}
