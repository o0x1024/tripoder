#define _GNU_SOURCE
#include "tripoder_native_hook.h"
#include "modules/linker_notifier.h"
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
static bool callback(void *ctx,uint64_t token,const struct tp_scalar_value *args,unsigned count,int incoming,struct tp_scalar_result *out){
    (void)ctx;if(!tp_native_original(token,args,count,incoming,out))return false;
    if(out->value.type==TP_SCALAR_I32)out->value.bits=(uint32_t)(out->value.bits+100);return true;
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeRangeProbe_run(JNIEnv *env,jclass owner,jstring library){
    (void)owner;const char *path=(*env)->GetStringUTFChars(env,library,NULL);if(!path)return NULL;
    const char *names[]={"tp_range_tbz","tp_range_tbnz","tp_range_cbz","tp_range_b","tp_range_literal","tp_range_adr","tp_range_adrp"};
    const char *paths[]={path};void *module=NULL;unsigned handles[7],checks=0,failed=0;uintptr_t entries[7];uint64_t baseline[7][4];
    struct tp_module_snapshot snapshot;const char *stage="MODULE";
    if(!tp_linker_install(paths,1)||(module=dlopen(path,RTLD_NOW|RTLD_LOCAL))==NULL||!tp_linker_snapshot(0,&snapshot)||!snapshot.live){failed++;goto done;}
    stage="PREPARE";
    for(unsigned i=0;i<7;i++){
        entries[i]=(uintptr_t)dlsym(module,names[i]);if(!entries[i]){failed++;goto done;}
        for(unsigned n=0;n<4;n++)baseline[i][n]=i<5?(uint32_t)((int (*)(int))entries[i])((int)n):((uint64_t (*)(void))entries[i])();
        struct tp_scalar_signature signature={.count=i<5?1u:0u,.result=i<5?TP_SCALAR_I32:TP_SCALAR_U64,.arguments={TP_SCALAR_I32}};
        checks++;if(!tp_native_prepare(0,snapshot.generation,entries[i]-snapshot.base,path,&signature,callback,NULL,&handles[i])){failed++;goto done;}
    }
    stage="PUBLISH";checks++;if(!tp_native_publish(handles,7)){failed++;goto done;}
    stage="EXECUTE";
    for(unsigned i=0;i<7;i++){
        for(unsigned n=0;n<4;n++){
            uint64_t value=i<5?(uint32_t)((int (*)(int))entries[i])((int)n):((uint64_t (*)(void))entries[i])();
            checks++;if(value!=baseline[i][n]+(i<5?100:0))failed++;
        }
        struct tp_native_report report;checks++;if(!tp_native_report(handles[i],&report)||report.calls!=4||report.original_calls!=4||report.active_calls)failed++;
    }
    stage="RETIRE";checks++;if(!tp_native_retire(handles,7)){failed++;goto done;}
    stage="RESTORED";
    for(unsigned i=0;i<7;i++){checks++;uint64_t value=i<5?(uint32_t)((int (*)(int))entries[i])(1):((uint64_t (*)(void))entries[i])();if(value!=baseline[i][1])failed++;}
    stage="UNLOAD";checks++;if(dlclose(module)||!tp_linker_snapshot(0,&snapshot)||snapshot.live)failed++;
done:;
    struct tp_native_error error;tp_native_last_error(&error);char text[700];
    snprintf(text,sizeof(text),"{\"passed\":%s,\"checks\":%u,\"failures\":%u,\"stage\":\"%s\",\"nativeStatus\":%d,\"nativeStage\":\"%s\",\"systemError\":%d,\"plannerStatus\":%d,\"exports\":7}",failed?"false":"true",checks,failed,stage,error.status,error.stage?error.stage:"NONE",error.system_error,error.planner_status);
    (*env)->ReleaseStringUTFChars(env,library,path);return (*env)->NewStringUTF(env,text);
}
