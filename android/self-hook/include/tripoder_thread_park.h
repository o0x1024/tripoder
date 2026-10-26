#ifndef TRIPODER_THREAD_PARK_H
#define TRIPODER_THREAD_PARK_H
#include <stdint.h>

#define TP_PARK_SIGNAL 60
#define TP_PARK_MAX_THREADS 256

enum tp_park_status {
    TP_PARK_OK, TP_PARK_NOT_READY, TP_PARK_BUSY, TP_PARK_SIGNAL_OWNED,
    TP_PARK_SYSTEM, TP_PARK_LIMIT, TP_PARK_TIMEOUT, TP_PARK_INVENTORY,
    TP_PARK_STALE, TP_PARK_WRONG_OWNER
};
struct tp_park_snapshot {
    unsigned epoch, observed, parked, vanished, passes;
    int system_error;
    int pending_tid, pending_mask_valid;
    uint64_t pending_signal_mask;
};
struct tp_park_context {
    int tid;
    uintptr_t pc, sp, lr;
    uint64_t signal_mask; /* interrupted mask, before the park handler's mask */
};

/* One explicit signal reservation for process lifetime. No signal/backend fallback.
 * Begin/end are serialized; successful begin returns with every other observed
 * live thread in this process parked. Until end, the owner must use only atomic
 * operations and audited direct syscalls. No JNI, allocation, stdio or callbacks.
 * This is NOT a code publication/reclamation or ART frame migration protocol. */
enum tp_park_status tp_park_init(void);
/* Shared process preparation: reuse only this core's existing reservation and
 * revalidate its real handler. Does not acquire an active park interval.
 * tp_park_init remains a one-shot API; BUSY is never treated as ownership. */
enum tp_park_status tp_park_prepare_process(void);
enum tp_park_status tp_park_begin(struct tp_park_snapshot *snapshot);
enum tp_park_status tp_park_end(unsigned epoch);
enum tp_park_status tp_park_context(unsigned epoch, unsigned index, struct tp_park_context *context);
unsigned tp_park_late_signals(unsigned epoch);
#endif
