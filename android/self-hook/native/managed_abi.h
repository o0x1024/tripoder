#ifndef TP_MANAGED_ABI_H
#define TP_MANAGED_ABI_H
#include <jni.h>
#include <stdbool.h>
bool tp_managed_abi_bind(JNIEnv *env);
jobject tp_managed_abi_original(JNIEnv *env,jclass owner,jmethodID original,bool instance,const char *shorty,jobject receiver,jobjectArray incoming);
#endif
