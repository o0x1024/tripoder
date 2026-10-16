#include "scalar_capture.h"
#include <jni.h>
#include <stdio.h>
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_NativeScalarProbe_run(JNIEnv *env,jclass owner){
    (void)owner;struct tp_scalar_fixture_report report={0};int status=tp_scalar_fixture_run(&report);char json[768];
    snprintf(json,sizeof(json),"{\"schema\":1,\"scope\":\"owned-native-scalar-call-capture-only\",\"status\":%d,\"passed\":%s,\"checks\":%u,\"failures\":%u,\"cases\":%u,\"concurrentCalls\":%llu,\"concurrentErrors\":%u,\"hookInstalls\":%u,\"targetDiscovery\":false,\"fullNativeHook\":false}",status,report.passed?"true":"false",report.checks,report.failures,report.cases,(unsigned long long)report.concurrent_calls,report.concurrent_errors,report.hook_installs);
    return (*env)->NewStringUTF(env,json);
}
