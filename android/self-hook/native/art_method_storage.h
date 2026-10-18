#ifndef TP_ART_METHOD_STORAGE_H
#define TP_ART_METHOD_STORAGE_H
#include "art_access.h"
/* Returned storage has a process-lifetime class/loader lease. It is outside
 * every declared method array and rooted by the owner's ClassExt native roots. */
struct tp_art_method_view *tp_art_backup_lookup(const struct tp_art_method_view *target);
struct tp_art_method_view *tp_art_backup_lookup_slot(unsigned index,const struct tp_art_method_view *target);
int tp_art_backup_snapshot(const struct tp_art_method_view *target, struct tp_art_method_view *backup);
/* Caller already owns SuspendAll. No nested suspension, allocation or JNI. */
bool tp_art_backup_rooted_locked(const struct tp_art_method_view *target,const struct tp_art_method_view *backup);
bool tp_art_backup_rooted_slot_locked(unsigned index,const struct tp_art_method_view *target,const struct tp_art_method_view *backup);
/* Fixed ART root read barrier; caller holds shared/exclusive mutator access. */
uintptr_t tp_art_storage_read_root(const uint32_t *root);
#endif
