#include <jni.h>
static jboolean read_value(JNIEnv *env,jclass cls,jstring value){(void)env;(void)cls;return value!=NULL;}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ManagedAdmissionChecks_bind(JNIEnv *env,jclass cls,jclass owner){
    (void)cls;JNINativeMethod method={"read","(Ljava/lang/String;)Z",(void *)read_value};
    return (*env)->RegisterNatives(env,owner,&method,1)==JNI_OK;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ManagedAdmissionNamedTarget_read(JNIEnv *env,jclass cls,jstring value){
    return read_value(env,cls,value);
}
