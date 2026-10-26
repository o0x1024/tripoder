#include <jni.h>
#include <stdint.h>

static JavaVM *vm;
JNIEXPORT jint JNI_OnLoad(JavaVM *owner,void *reserved){(void)reserved;vm=owner;return JNI_VERSION_1_6;}
uintptr_t owned_dependency_identity(void){return (uintptr_t)&vm;}
int owned_dependency_initialized(void){return vm!=NULL;}
JNIEXPORT jlong JNICALL Java_dev_tripoder_nativelibrarycontract_Main_dependencyIdentity(JNIEnv *env,jclass type){(void)env;(void)type;return (jlong)owned_dependency_identity();}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_nativelibrarycontract_Main_dependencyInitialized(JNIEnv *env,jclass type){(void)env;(void)type;return owned_dependency_initialized();}
