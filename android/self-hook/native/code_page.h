#ifndef TRIPODER_CODE_PAGE_H
#define TRIPODER_CODE_PAGE_H
#include <stdint.h>
#include "tripoder_owned_hook.h"
#define TP_CODE_PAGE_WORDS 1024
struct tp_page_identity { uint64_t inode, offset; unsigned major, minor; bool bti; };
struct tp_code_page {
    void *address, *original_view;
    struct tp_page_identity source;
    bool anonymous;
    uint32_t original_image[TP_CODE_PAGE_WORDS];
};
struct tp_code_page_update {
    struct tp_code_page *owner;
    void *prepared, *destination;
    uint32_t expected[TP_CODE_PAGE_WORDS], replacement[TP_CODE_PAGE_WORDS];
    bool was_anonymous, committed, restore_file;
};
int tp_code_page_identity(void *page, const char *permissions, struct tp_page_identity *identity);
int tp_code_page_matches(void *page, const struct tp_page_identity *identity, const uint32_t words[static TP_CODE_PAGE_WORDS]);
/* Complete immutable file page image. Holds a separate RX original view.
 * These internal APIs require the owner's exclusive lock and drained call domain. */
int tp_code_page_claim(struct tp_code_page *page, void *address, const char *path, const uint32_t words[static TP_CODE_PAGE_WORDS]);
int tp_code_page_current(const struct tp_code_page *page, const uint32_t words[static TP_CODE_PAGE_WORDS]);
/* Prepare privately, then replace at the same address. Never write published RX.
 * -1: preparation failed, destination untouched; 0: identity mismatch.
 * Uncertain fixed-address publication terminates the process. */
int tp_code_page_publish(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS], const uint32_t replacement[static TP_CODE_PAGE_WORDS]);
/* Split publication. Prepare/finish/cancel
 * may use libc and must run with ordinary threads resumed. Commit contains only
 * word comparisons, inline cache maintenance and direct syscalls; caller must
 * already own an exclusive/drained or closed parked execution domain, the whole
 * page image and every changed entry. No stack/return-address reclamation claim. */
int tp_code_page_prepare(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS],
                         const uint32_t replacement[static TP_CODE_PAGE_WORDS], struct tp_code_page_update *update);
int tp_code_page_commit(struct tp_code_page_update *update);
int tp_code_page_validate(const struct tp_code_page_update *update);
int tp_code_page_prepare_restore(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS],
                                 struct tp_code_page_update *update);
void tp_code_page_finish(struct tp_code_page_update *update);
void tp_code_page_cancel(struct tp_code_page_update *update);
void tp_code_page_release(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS], const uint32_t original[static TP_CODE_PAGE_WORDS]);
void tp_code_pages_report(struct tp_owned_page_report *result);
#ifdef TP_SELF_HOOK_TESTING
void tp_code_pages_reject_next_prepare(void);
#endif
void tp_code_fatal(void);
#endif
