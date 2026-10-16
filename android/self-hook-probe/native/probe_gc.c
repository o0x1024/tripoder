#include "art_access.h"
#include "art_framework_contract.h"
#include <stdint.h>

JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhookprobe_GcProbe_snapshot(JNIEnv *env,jclass cls,jobjectArray values){
    (void)cls;struct tp_art_access art;
    if(!values||!tp_art_access_bind(&art)||!tp_art_validate_code(&art,TP_FRAMEWORK_DECODE_OBJECT_RVA,
        tp_framework_decode_object_bytes,sizeof(tp_framework_decode_object_bytes)))return NULL;
    jsize count=(*env)->GetArrayLength(env,values);if(count<1||count>32)return NULL;
    jobject roots[32]={0};jlong addresses[32]={0};
    for(jsize i=0;i<count;i++){roots[i]=(*env)->GetObjectArrayElement(env,values,i);if((*env)->ExceptionCheck(env))return NULL;}
    uintptr_t (*decode)(void *,jobject)=(void *)(art.base+TP_FRAMEWORK_DECODE_OBJECT_RVA);
    void *thread=art.current();char scope=0;if(!thread)return NULL;
    art.suspend(&scope,"tripoder-owned-moving-reference-snapshot",false);
    for(jsize i=0;i<count;i++)addresses[i]=roots[i]?(jlong)decode(thread,roots[i]):0;
    art.resume(&scope);
    jlongArray result=(*env)->NewLongArray(env,count);
    if(result)(*env)->SetLongArrayRegion(env,result,0,count,addresses);
    for(jsize i=0;i<count;i++)if(roots[i])(*env)->DeleteLocalRef(env,roots[i]);
    return result;
}
