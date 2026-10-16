#include "linker_notifier.h"
#include <jni.h>
#include <stdlib.h>
#include <string.h>
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhook_NativeModules_initialize0(JNIEnv *env,jclass type,jobjectArray input){
    (void)type;if(!input)return false;jsize count=(*env)->GetArrayLength(env,input);if(count<0||count>(jsize)TP_MODULE_LIMIT)return false;
    char (*storage)[TP_MODULE_PATH_LIMIT]=count?calloc((size_t)count,TP_MODULE_PATH_LIMIT):NULL;const char **paths=count?calloc((size_t)count,sizeof(*paths)):NULL;
    bool ok=false;if(count&&(!storage||!paths))goto done;
    for(jsize i=0;i<count;i++){
        jbyteArray path=(*env)->GetObjectArrayElement(env,input,i);if(!path)goto done;
        jsize length=(*env)->GetArrayLength(env,path);
        if(length<=0||length>=(jsize)TP_MODULE_PATH_LIMIT){(*env)->DeleteLocalRef(env,path);goto done;}
        (*env)->GetByteArrayRegion(env,path,0,length,(jbyte *)storage[i]);(*env)->DeleteLocalRef(env,path);
        if((*env)->ExceptionCheck(env)||memchr(storage[i],0,(size_t)length))goto done;paths[i]=storage[i];
    }
    ok=tp_linker_install(paths,(unsigned)count);
done:
    free(paths);free(storage);return ok;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhook_NativeModules_snapshot0(JNIEnv *env,jclass type,jint index){
    (void)type;struct tp_module_snapshot state;if(index<0||!tp_linker_snapshot((unsigned)index,&state))return NULL;
    jlong values[5]={(jlong)state.generation,(jlong)state.base,(jlong)state.references,state.live?1:0,state.ambiguous?1:0};
    jlongArray result=(*env)->NewLongArray(env,5);if(result)(*env)->SetLongArrayRegion(env,result,0,5,values);return result;
}
JNIEXPORT jlongArray JNICALL Java_dev_tripoder_selfhook_NativeModules_events0(JNIEnv *env,jclass type){
    (void)type;struct tp_module_event events[TP_MODULE_EVENT_LIMIT];unsigned count;
    if(!tp_linker_events(events,&count))return NULL;
    jlong values[TP_MODULE_EVENT_LIMIT*5];for(unsigned i=0;i<count;i++){
        values[i*5]=(jlong)events[i].sequence;values[i*5+1]=events[i].index;values[i*5+2]=events[i].generation;
        values[i*5+3]=(jlong)events[i].base;values[i*5+4]=events[i].kind;
    }
    jlongArray output=(*env)->NewLongArray(env,(jsize)(count*5));if(output)(*env)->SetLongArrayRegion(env,output,0,(jsize)(count*5),values);return output;
}
