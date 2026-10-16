#ifndef TRIPODER_OWNED_HOOK_H
#define TRIPODER_OWNED_HOOK_H
#include <stdbool.h>
#include <stdint.h>

/* H2a research boundary: one audited fixture module, one u64(u64) target and
 * one dedicated ELF trampoline page. ALL calls must use this execution domain.
 * Not an API for arbitrary guest methods, signals, exceptions or raw pointers. */
enum tp_owned_status {
    TP_OWNED_OK, TP_OWNED_ARGUMENT, TP_OWNED_BUSY, TP_OWNED_STALE,
    TP_OWNED_STATE, TP_OWNED_IDENTITY, TP_OWNED_PLATFORM, TP_OWNED_PLAN,
    TP_OWNED_PERMISSION, TP_OWNED_REENTRANT, TP_OWNED_ORIGINAL_TWICE
};
typedef uint64_t (*tp_owned_callback)(uint64_t generation, uint64_t argument);
struct tp_owned_result { uint64_t value, epoch; unsigned original_calls; bool hooked; };
struct tp_owned_info {
    uint64_t generation, epoch, target, trampoline;
    unsigned mutations, active_calls;
    bool enabled;
};
struct tp_owned_page_report {
    unsigned staged_rw, staged_rx, published, restored;
    unsigned live_staging_pages, peak_staging_pages, live_anonymous_pages;
    unsigned injected_prepare_rejections;
    int prepare_errno;
};
/* Cumulative observations for the owned cooperative fixture only. */
void tp_owned_pages(struct tp_owned_page_report *result);
#ifdef TP_SELF_HOOK_TESTING
void tp_owned_test_reject_next_prepare(void);
#endif
/* Explicit dlopen lease from open to close. No NODELETE or hidden lifetime pin.
 * Library must have the exact audited fixture pages; no arbitrary symbol API.
 * Generation handles survive storage retirement but cannot address a new load. */
enum tp_owned_status tp_owned_open(const char *absolute_path, tp_owned_callback callback, uint64_t *generation);
enum tp_owned_status tp_owned_enable(uint64_t generation, bool enabled);
enum tp_owned_status tp_owned_call(uint64_t generation, uint64_t argument, struct tp_owned_result *result);
enum tp_owned_status tp_owned_original(uint64_t generation, uint64_t argument, uint64_t *result);
enum tp_owned_status tp_owned_info(uint64_t generation, struct tp_owned_info *result);
/* Rejects ACTIVE and reentrant close. Drains admitted calls before dlclose.
 * A bounded 100 ms drain timeout returns BUSY without touching code. */
enum tp_owned_status tp_owned_close(uint64_t generation);
#endif
