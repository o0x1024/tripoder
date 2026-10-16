#ifndef TP_ART_JNI_ORIGINAL_H
#define TP_ART_JNI_ORIGINAL_H
#include "art_access.h"
/* Under OriginalMethodStorage.class serialization. The immutable adapter
 * belongs to the same declaring class's GC-rooted Native backup. */
uintptr_t tp_art_jni_original_prepare(JNIEnv *,unsigned,struct tp_art_method_view *target,struct tp_art_method_view *backup,jstring shorty);
#endif
