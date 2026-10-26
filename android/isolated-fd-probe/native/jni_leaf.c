#include <jni.h>
#include <stdint.h>

#ifdef OWNED_NAMED
#define ID owned_jni_named_identity
#define COUNT owned_jni_named_count
#define READY owned_jni_named_ready
#define ENTRY Java_dev_tripoder_isolatedfdpayload_NativePayload_namedLeafState
#else
#define ID owned_jni_identity
#define COUNT owned_jni_count
#define READY owned_jni_ready
#define ENTRY Java_dev_tripoder_isolatedfdpayload_NativePayload_leafState
#endif
static JavaVM *vm;
static jclass defining_class;
static int loads;
JNIEXPORT jint JNI_OnLoad(JavaVM *owner,void *reserved){
    (void)reserved;JNIEnv *env=NULL;
    if((*owner)->GetEnv(owner,(void**)&env,JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
    jclass found=(*env)->FindClass(env,"dev/tripoder/isolatedfdpayload/NativePayload");
    if(!found)return JNI_ERR;defining_class=(*env)->NewGlobalRef(env,found);(*env)->DeleteLocalRef(env,found);
    if(!defining_class)return JNI_ERR;vm=owner;loads++;return JNI_VERSION_1_6;
}
uintptr_t ID(void){return (uintptr_t)&vm;}
int COUNT(void){return loads;}
int READY(void){return vm!=NULL;}
JNIEXPORT jlongArray JNICALL ENTRY(JNIEnv *env,jclass type){
    jlong state[]={ID(),loads,vm!=NULL,defining_class&&(*env)->IsSameObject(env,type,defining_class)};
    jlongArray result=(*env)->NewLongArray(env,4);if(result)(*env)->SetLongArrayRegion(env,result,0,4,state);return result;
}
