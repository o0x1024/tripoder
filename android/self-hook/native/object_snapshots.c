#include <jni.h>

/* Public framework accessors invoked nonvirtually; never call a guest override. */
#define SNAPSHOT(Name,Type,Class,Signature,Call,Result) \
JNIEXPORT Result JNICALL Java_dev_tripoder_runtime_script_JavaObjectSnapshots_##Name(JNIEnv *env,jclass ignored,jobject value){ \
    (void)ignored;jclass owner=(*env)->FindClass(env,Class);if(!owner)return (Result)0; \
    jmethodID method=(*env)->GetMethodID(env,owner,Type,Signature);if(!method)return (Result)0; \
    return (Result)(*env)->CallNonvirtual##Call##Method(env,value,owner,method); \
}
SNAPSHOT(message,"getMessage","java/lang/Throwable","()Ljava/lang/String;",Object,jstring)
SNAPSHOT(cause,"getCause","java/lang/Throwable","()Ljava/lang/Throwable;",Object,jobject)
SNAPSHOT(trace,"getStackTrace","java/lang/Throwable","()[Ljava/lang/StackTraceElement;",Object,jobjectArray)
SNAPSHOT(suppressed,"getSuppressed","java/lang/Throwable","()[Ljava/lang/Throwable;",Object,jobjectArray)
SNAPSHOT(threadName,"getName","java/lang/Thread","()Ljava/lang/String;",Object,jstring)
SNAPSHOT(threadId,"getId","java/lang/Thread","()J",Long,jlong)
SNAPSHOT(threadState,"getState","java/lang/Thread","()Ljava/lang/Thread$State;",Object,jobject)
