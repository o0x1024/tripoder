#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>

/* Owned single-worker barrier. No ART locks or borrowed Java references. */
static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed=PTHREAD_COND_INITIALIZER;
static _Atomic bool entered;
static bool released;

JNIEXPORT void JNICALL Java_dev_tripoder_selfhookprobe_InFlightCaller_barrierNative(JNIEnv *env,jclass cls){
    (void)env;(void)cls;
    pthread_mutex_lock(&mutex);
    atomic_store_explicit(&entered,true,memory_order_release);
    while(!released)pthread_cond_wait(&changed,&mutex);
    pthread_mutex_unlock(&mutex);
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_InFlightCaller_enteredNative(JNIEnv *env,jclass cls){
    (void)env;(void)cls;return atomic_load_explicit(&entered,memory_order_acquire)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT void JNICALL Java_dev_tripoder_selfhookprobe_InFlightCaller_releaseNative(JNIEnv *env,jclass cls){
    (void)env;(void)cls;pthread_mutex_lock(&mutex);released=true;pthread_cond_broadcast(&changed);pthread_mutex_unlock(&mutex);
}
