#ifndef TRIPODER_FIXED_LIBC_H
#define TRIPODER_FIXED_LIBC_H
#include <stdbool.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/types.h>
#include "tripoder_thread_park.h"

#define TP_FIXED_LIBC_ENTRIES 11

/* Exact measured signatures. Replacements remain alive for process lifetime. */
struct tp_fixed_libc_replacements {
    int (*openat)(int,const char *,int,unsigned);
    int (*fstatat)(int,const char *,struct stat *,int);
    int (*unlinkat)(int,const char *,int);
    int (*mkdirat)(int,const char *,unsigned);
    int (*renameat2)(int,const char *,int,const char *,unsigned);
    int (*faccessat)(int,const char *,int);
    ssize_t (*readlinkat)(int,const char *,char *,size_t);
    int (*chdir)(const char *);
    int (*symlinkat)(const char *,int,const char *);
    int (*linkat)(int,const char *,int,const char *,int);
    int (*fchmodat)(int,const char *,unsigned);
};
enum tp_fixed_libc_status {
    TP_FIXED_LIBC_OK, TP_FIXED_LIBC_STATE, TP_FIXED_LIBC_IDENTITY,
    TP_FIXED_LIBC_DETOUR, TP_FIXED_LIBC_PAGE, TP_FIXED_LIBC_PAUSE, TP_FIXED_LIBC_IN_FLIGHT
};
/* Last transition admission outcome. Failure leaves both live pages untouched. */
enum tp_fixed_libc_admission {
    TP_FIXED_LIBC_ADMISSION_NONE, TP_FIXED_LIBC_ADMISSION_COMMITTED,
    TP_FIXED_LIBC_ADMISSION_PREIMAGE, TP_FIXED_LIBC_ADMISSION_PAUSE,
    TP_FIXED_LIBC_ADMISSION_ATTEMPTS, TP_FIXED_LIBC_ADMISSION_DEADLINE,
    TP_FIXED_LIBC_ADMISSION_CLOCK, TP_FIXED_LIBC_ADMISSION_CONTEXT
};
struct tp_fixed_libc_report {
    uintptr_t base, pages[2], targets[TP_FIXED_LIBC_ENTRIES];
    unsigned validated_entries, absolute_entries, changed_words, publications, restorations, rejected_batches;
    unsigned quiescence_rejections;
    unsigned admission_attempts, admission_outcome;
    int context_status, context_index; /* non-OK context status and its snapshot index; -1 if none */
    uint64_t admission_elapsed_ns;
    /* Counts every unsafe frame, not just the first thread in a snapshot.
     * Columns are exact entry-relative PCs +8, +12 and +16 respectively. */
    unsigned unsafe_pc_counts[TP_FIXED_LIBC_ENTRIES][3];
    int unsafe_tid;
    uintptr_t unsafe_pc;
    bool admission_enable;
    int pause_status;
    struct tp_park_snapshot pause;
    bool ready, enabled, source_bti, lease_held;
};
/* Run only after the fixed-platform whole-file identity gate, once per process.
 * This is the sole fixed libc storage-entry path, not a generic hook API. */
enum tp_fixed_libc_status tp_fixed_libc_prepare(const struct tp_fixed_libc_replacements *replacements);
enum tp_fixed_libc_status tp_fixed_libc_activate(void);
enum tp_fixed_libc_status tp_fixed_libc_deactivate(void);
void tp_fixed_libc_report(struct tp_fixed_libc_report *report);
#ifdef TP_SELF_HOOK_TESTING
void tp_fixed_libc_test_reject_next_batch(void);
#endif
#endif
