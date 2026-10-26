#include <jni.h>
#include <stdint.h>

extern uintptr_t owned_dependency_identity(void);
extern int owned_dependency_initialized(void);
/* Loading this JNI library must not initialize its dependency a second time. */
JNIEXPORT jint JNI_OnLoad(JavaVM *owner,void *reserved){(void)owner;(void)reserved;return JNI_VERSION_1_6;}
JNIEXPORT jlong JNICALL Java_dev_tripoder_nativelibrarycontract_Main_driverDependencyIdentity(JNIEnv *env,jclass type){(void)env;(void)type;return (jlong)owned_dependency_identity();}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_nativelibrarycontract_Main_driverDependencyInitialized(JNIEnv *env,jclass type){(void)env;(void)type;return owned_dependency_initialized();}
