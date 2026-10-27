#ifndef TRIPODER_ART_REFLECTION_VIEW_H
#define TRIPODER_ART_REFLECTION_VIEW_H
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
struct tp_art_reflection_report {
    unsigned transitions, retained_roots;
    bool ready, enabled;
    uint64_t gets, sets, mapped_gets, mapped_sets, denials, original_exceptions, metadata_decodes;
};
/* Process-lifetime instance-field identity view. Call the whole-file ART platform gate
 * first. Original native Field.get/set run in their existing managed frames.
 * Pending construction retains eight strong roots; one promotion adds the
 * ninth. No published logical object is replaced or released. */
bool tp_art_reflection_prepare(JNIEnv *env,jobject field,jobject owner,jobject physical,
                                jobject logical,jclass value_type);
bool tp_art_reflection_prepare_pending(JNIEnv *env,jobject field,jobject owner,jobject physical,jclass value_type);
bool tp_art_reflection_promote(JNIEnv *env,jobject logical);
bool tp_art_reflection_complete(JNIEnv *env);
/** derived: the adapter already proved the Application is built around this guest's own base Context. */
bool tp_art_reflection_adopt(JNIEnv *env,jobject application,bool derived);
jobject tp_art_reflection_value(JNIEnv *env);
bool tp_art_reflection_toggle(JNIEnv *env,bool enabled);
void tp_art_reflection_report(struct tp_art_reflection_report *report);
#endif
