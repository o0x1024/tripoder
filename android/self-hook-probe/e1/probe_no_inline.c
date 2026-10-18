#include "art_access.h"
#include "art_interpreter_contract.h"
#include <jni.h>

/* Read-only research observer. No process interpreter claim and no method writes. */
JNIEXPORT jint JNICALL Java_dev_tripoder_selfhookprobe_NoInlineProbeChecks_instrumentationLevel(JNIEnv *env,jclass unused){
    (void)unused;struct tp_art_deopt_access art;
    if(!tp_art_deopt_bind(&art)||!art.managed.access.current()||
       !tp_art_validate_code(&art.managed.access,TP_GLOBAL_LEVEL_RVA,tp_global_level_bytes,sizeof(tp_global_level_bytes))){
        jclass error=(*env)->FindClass(env,"java/lang/IllegalStateException");
        if(error)(*env)->ThrowNew(env,error,"E1_LEVEL_ART_CONTRACT");return -1;
    }
    int (*level)(void *)=(int (*)(void *))(art.managed.access.base+TP_GLOBAL_LEVEL_RVA);
    char jit=0,mutators=0;
    art.managed.jit_suspend(&jit);
    art.managed.access.suspend(&mutators,"tripoder-e1-level-observation",false);
    int value=level(art.instrumentation);
    art.managed.access.resume(&mutators);
    art.managed.jit_resume(&jit);
    return value;
}
