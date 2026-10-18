#include "art_access.h"
#include "art_framework_contract.h"
#include <jni.h>
#include <stdint.h>
/* Diagnostic only: the Activity verifies the complete pinned libart digest. */
static struct tp_art_managed_access art;
static void *linker;
static bool bind(void){
    static const uint32_t visible[]={0xd503233f,0xd10283ff,0xa9057bfd,0xa90667fa,0xa9075ff8,0xa90857f6,0xa9094ff4,0x910143fd};
    static const uint32_t boot[]={0xd503233f,0xd10143ff,0xa9027bfd,0xf9001bf5,0xa9044ff4,0x910083fd,0xd53bd055,0xaa0003f3};
    static const uint32_t resolution[]={0xd503245f,0xd0ffd2c8,0x91174108,0xeb08003f,0x540000a0,0xf940a408,0xeb01011f,0x1a9f17e0};
    if(!tp_art_managed_bind(&art)||!tp_art_validate_code(&art.access,0x46f84c,visible,sizeof(visible))
        ||!tp_art_validate_code(&art.access,0x3af89c,boot,sizeof(boot))||!tp_art_validate_code(&art.access,0x862e80,resolution,sizeof(resolution)))return false;
    uintptr_t runtime=*(uintptr_t *)(art.access.base+TP_FRAMEWORK_RUNTIME_RVA);
    if(!runtime||!tp_art_readable(runtime+0x270,8))return false;linker=*(void **)(runtime+0x270);return linker!=NULL;
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ManagedVisibilityProbeActivity_flushVisibility(JNIEnv *env,jclass cls){
    (void)env;(void)cls;if(!bind())return JNI_FALSE;
    /* Ordinary JNI is Native and holds no mutator lock; wait=true requires it. */
    void (*flush)(void *,void *,bool)=(void (*)(void *,void *,bool))(art.access.base+0x46f84c);
    flush(linker,art.access.current(),true);return JNI_TRUE;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_ManagedVisibilityProbeActivity_observe(JNIEnv *env,jclass cls,jobject reflected){
    (void)cls;if(!bind())return NULL;uintptr_t method;struct tp_art_method_view view;
    if(!tp_art_method_observe(env,reflected,&method,&view))return NULL;
    uintptr_t (*boot)(void *,void *)=(uintptr_t (*)(void *,void *))(art.access.base+0x3af89c);
    bool (*resolution)(void *,const void *)=(bool (*)(void *,const void *))(art.access.base+0x862e80);
    char scope=0;art.access.suspend(&scope,"owned-native-entry-observation",false);
    jlong values[]={(jlong)view.quick,(jlong)art.jni_bridge,(jlong)boot(linker,(void *)method),resolution(linker,(void *)view.quick),(jlong)view.flags};
    art.access.resume(&scope);jlongArray out=(*env)->NewLongArray(env,5);if(out)(*env)->SetLongArrayRegion(env,out,0,5,values);return out;
}
