#ifndef TP_NATIVE_JNI_THREAD_H
#define TP_NATIVE_JNI_THREAD_H
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
struct tp_jni_thread_stats {uint64_t leases,attachments,detachments;};
bool tp_jni_thread_initialize(void);
bool tp_jni_thread_env(JavaVM *vm,JNIEnv **env);
void tp_jni_thread_resources(struct tp_jni_thread_stats *out);
#endif
