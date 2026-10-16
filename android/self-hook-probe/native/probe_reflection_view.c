#include "art_reflection_view.h"
#include <stdio.h>
#include <inttypes.h>

JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ReflectionViewProbe_prepare(JNIEnv *env,jclass type,
        jobject field,jobject owner,jobject physical,jobject logical,jclass value_type) {
    (void)type;
    return tp_art_reflection_prepare(env,field,owner,physical,logical,value_type);
}
JNIEXPORT jboolean JNICALL Java_dev_tripoder_selfhookprobe_ReflectionViewProbe_toggle(JNIEnv *env,jclass type,jboolean enabled) {
    (void)type;
    return tp_art_reflection_toggle(env,enabled);
}
JNIEXPORT jstring JNICALL Java_dev_tripoder_selfhookprobe_ReflectionViewProbe_report(JNIEnv *env,jclass type) {
    (void)type;
    struct tp_art_reflection_report state;
    tp_art_reflection_report(&state);
    char text[2048];
    snprintf(text,sizeof(text),
        "{\"ready\":%s,\"enabled\":%s,\"transitions\":%u,\"retainedGlobalRefs\":%u,"
        "\"gets\":%" PRIu64 ",\"sets\":%" PRIu64 ",\"mappedGets\":%" PRIu64 ",\"mappedSets\":%" PRIu64 ","
        "\"denials\":%" PRIu64 ",\"originalExceptions\":%" PRIu64 ",\"processLifetimeSlot\":true}",
        state.ready?"true":"false",state.enabled?"true":"false",state.transitions,state.retained_roots,
        state.gets,state.sets,state.mapped_gets,state.mapped_sets,state.denials,state.original_exceptions);
    return (*env)->NewStringUTF(env,text);
}
