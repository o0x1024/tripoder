#define _GNU_SOURCE
#include "code_page.h"
#include "tripoder_a64.h"
#include <android/set_abort_message.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <unistd.h>

static FILE *raw_proc_open(const char *name) {
    int fd = (int)syscall(SYS_openat, AT_FDCWD, name, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return NULL;
    FILE *stream = fdopen(fd, "r");
    if (!stream) { int saved = errno; syscall(SYS_close, fd); errno = saved; }
    return stream;
}

static struct tp_owned_page_report report;
#ifdef TP_SELF_HOOK_TESTING
static bool reject_next_prepare;
void tp_code_pages_reject_next_prepare(void) { reject_next_prepare = true; }
#endif
void tp_code_pages_report(struct tp_owned_page_report *result) { *result = report; }
void tp_code_fatal(void) {
    android_set_abort_message("TP_OWNED_INVARIANT_FAILED");
    abort();
}
static void page_fatal(const char *stage, void *address) {
    char message[256];
    snprintf(message, sizeof(message), "TP_PAGE_%s errno=%d address=%p", stage, errno, address);
    android_set_abort_message(message);
    abort();
}
int tp_code_page_identity(void *page, const char *permissions, struct tp_page_identity *identity) {
    uintptr_t address = (uintptr_t)page;
    if (address == 0 || (address & 4095) != 0 || address > UINTPTR_MAX - 4096) return 0;
    FILE *stream = raw_proc_open("/proc/self/smaps");
    if (stream == NULL) return 0;
    char line[2048], mode[5];
    unsigned long start, end, offset, inode;
    unsigned major, minor;
    int selected = 0, found = 0;
    struct tp_page_identity parsed = {0};
    while (fgets(line, sizeof(line), stream) != NULL) {
        if (sscanf(line, "%lx-%lx %4s %lx %x:%x %lu", &start, &end, mode,
                   &offset, &major, &minor, &inode) == 7) {
            selected = start <= address && address + 4096 <= end;
            if (selected) {
                if (strcmp(mode, permissions) != 0) break;
                parsed = (struct tp_page_identity){.inode=inode, .offset=inode ? offset + address - start : 0,
                    .major=major, .minor=minor};
            }
        } else if (selected && strncmp(line, "VmFlags:", 8) == 0) {
            /* Preserve BTI exactly. MTE code-page semantics remain unsupported. */
            char *save = NULL;
            int guarded = 0;
            for (char *flag = strtok_r(line + 8, " \n", &save); flag != NULL;
                 flag = strtok_r(NULL, " \n", &save))
                if (strcmp(flag, "bt") == 0) parsed.bti = true;
                else if (strcmp(flag, "mt") == 0) guarded = 1;
            found = !guarded;
            break;
        }
    }
    if (ferror(stream)) found = 0;
    fclose(stream);
    if (found) *identity = parsed;
    return found;
}
static int same(const struct tp_page_identity *a, const struct tp_page_identity *b) {
    return a->inode == b->inode && a->offset == b->offset && a->major == b->major && a->minor == b->minor && a->bti == b->bti;
}
static int contents(void *page, const uint32_t words[static TP_CODE_PAGE_WORDS]) {
    const uint32_t *code = page;
    for (unsigned i = 0; i < 1024; i++)
        if (code[i] != words[i]) return 0;
    return 1;
}
int tp_code_page_matches(void *page, const struct tp_page_identity *identity, const uint32_t words[static TP_CODE_PAGE_WORDS]) {
    struct tp_page_identity actual;
    return tp_code_page_identity(page, "r-xp", &actual) && same(identity, &actual) && contents(page, words);
}
static const struct tp_page_identity anonymous = {0};
int tp_code_page_current(const struct tp_code_page *page, const uint32_t words[static TP_CODE_PAGE_WORDS]) {
    struct tp_page_identity actual_anonymous = {.bti=page->source.bti};
    return tp_code_page_matches(page->address, page->anonymous ? &actual_anonymous : &page->source, words);
}
static void staged(void) {
    report.live_staging_pages++;
    if (report.live_staging_pages > report.peak_staging_pages) report.peak_staging_pages = report.live_staging_pages;
}
static void discard(void *address) {
    if (munmap(address, 4096) != 0) page_fatal("DISCARD", address);
    if (report.live_staging_pages == 0) tp_code_fatal();
    report.live_staging_pages--;
}
int tp_code_page_claim(struct tp_code_page *page, void *address, const char *path, const uint32_t words[static TP_CODE_PAGE_WORDS]) {
    struct tp_page_identity source;
    if (!tp_code_page_identity(address, "r-xp", &source) || source.inode == 0 ||
        source.offset > INT64_MAX - 4096 || !tp_code_page_matches(address, &source, words)) return 0;
    int fd = (int)syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW, 0);
    if (fd < 0) return 0;
    struct stat info;
    int valid = fstat(fd, &info) == 0 && S_ISREG(info.st_mode) && (uint64_t)info.st_ino == source.inode
        && major(info.st_dev) == source.major && minor(info.st_dev) == source.minor
        /* Linux maps the remainder of a final partial file page as zero.
         * The complete mapped preimage below must still match that file view. */
        && info.st_size > (off_t)source.offset;
    void *saved = valid ? mmap(NULL, 4096, PROT_READ | PROT_EXEC | (source.bti ? PROT_BTI : 0),
                              MAP_PRIVATE, fd, (off_t)source.offset) : MAP_FAILED;
    int saved_errno = errno;
    if (syscall(SYS_close, fd) != 0) tp_code_fatal();
    if (saved == MAP_FAILED) { report.prepare_errno = saved_errno; return 0; }
    staged();
    if (!tp_code_page_matches(saved, &source, words)) { discard(saved); return 0; }
    *page = (struct tp_code_page){.address=address, .original_view=saved, .source=source};
    memcpy(page->original_image, words, sizeof(page->original_image));
    return 1;
}
static void publish(void *prepared, void *destination) {
    /* MREMAP_FIXED discards the destination. Failure is NOT assumed atomic.
     * Never reopen admission on failure; source is ours, destination is leased. */
    void *moved = mremap(prepared, 4096, 4096, MREMAP_MAYMOVE | MREMAP_FIXED, destination);
    if (moved != destination) page_fatal("FIXED_PUBLICATION", destination);
    report.live_staging_pages--;
    /* Synchronize at final VA too; readers execute ISB after admission. */
    __builtin___clear_cache((char *)destination, (char *)destination + 4096);
}
int tp_code_page_prepare(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS],
                         const uint32_t replacement[static TP_CODE_PAGE_WORDS], struct tp_code_page_update *update) {
    if (update == NULL || update->owner != NULL || update->prepared != NULL || update->committed) return 0;
    if (!tp_code_page_current(page, expected)) return 0;
    void *prepared = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (prepared == MAP_FAILED) { report.prepare_errno = errno; return -1; }
    staged();
    struct tp_page_identity actual;
    if (!tp_code_page_identity(prepared, "rw-p", &actual) || !same(&actual, &anonymous))
        page_fatal("PREPARE_RW", prepared);
    report.staged_rw++;
    uint32_t *words = prepared;
    for (unsigned i = 0; i < 1024; i++) words[i] = replacement[i];
    __builtin___clear_cache((char *)prepared, (char *)prepared + 4096);
#ifdef TP_SELF_HOOK_TESTING
    /* Deterministically exercise candidate cleanup before touching the destination.
     * This counter is distinct from real kernel permission errors. */
    if (reject_next_prepare) {
        reject_next_prepare = false;
        report.injected_prepare_rejections++;
        discard(prepared);
        return -1;
    }
#endif
    if (mprotect(prepared, 4096, PROT_READ | PROT_EXEC | (page->source.bti ? PROT_BTI : 0)) != 0) {
        report.prepare_errno = errno;
        discard(prepared);
        return -1;
    }
    struct tp_page_identity final_anonymous = {.bti=page->source.bti};
    if (!tp_code_page_matches(prepared, &final_anonymous, replacement)) page_fatal("PREPARE_RX", prepared);
    report.staged_rx++;
    if (!tp_code_page_current(page, expected)) { discard(prepared); return 0; }
    update->owner = page; update->prepared = prepared; update->destination = page->address;
    memcpy(update->expected, expected, sizeof(update->expected));
    memcpy(update->replacement, replacement, sizeof(update->replacement));
    update->was_anonymous = page->anonymous;
    return 1;
}
int tp_code_page_prepare_restore(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS],
                                 struct tp_code_page_update *update) {
    if (update == NULL || update->owner || update->prepared || update->committed || !page->anonymous ||
        page->original_view == NULL || !tp_code_page_current(page, expected)) return 0;
    if (!tp_code_page_matches(page->original_view, &page->source, page->original_image)) return 0;
    update->owner = page; update->prepared = page->original_view; update->destination = page->address;
    memcpy(update->expected, expected, sizeof(update->expected));
    memcpy(update->replacement, page->original_image, sizeof(update->replacement));
    update->was_anonymous = true; update->restore_file = true;
    return 1;
}
void tp_code_page_finish(struct tp_code_page_update *update) {
    if (update == NULL || !update->committed || update->owner == NULL || report.live_staging_pages == 0) tp_code_fatal();
    struct tp_code_page *page = update->owner;
    struct tp_page_identity final_anonymous = {.bti=page->source.bti};
    if (!tp_code_page_matches(page->address, update->restore_file ? &page->source : &final_anonymous,
                              update->replacement)) page_fatal("PUBLISHED", page->address);
    report.live_staging_pages--;
    if (update->restore_file) {
        report.live_anonymous_pages--; report.restored++;
        *page = (struct tp_code_page){0};
    } else {
        if (!page->anonymous) report.live_anonymous_pages++;
        page->anonymous = true;
        report.published++;
    }
    *update = (struct tp_code_page_update){0};
}
void tp_code_page_cancel(struct tp_code_page_update *update) {
    if (update == NULL || update->owner == NULL || update->prepared == NULL || update->committed) tp_code_fatal();
    if (!update->restore_file) discard(update->prepared);
    *update = (struct tp_code_page_update){0};
}
int tp_code_page_publish(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS], const uint32_t replacement[static TP_CODE_PAGE_WORDS]) {
    struct tp_code_page_update update = {0};
    int status = tp_code_page_prepare(page, expected, replacement, &update);
    if (status != 1) return status;
    if (!tp_code_page_commit(&update)) { tp_code_page_cancel(&update); return 0; }
    tp_code_page_finish(&update);
    return 1;
}
void tp_code_page_release(struct tp_code_page *page, const uint32_t expected[static TP_CODE_PAGE_WORDS], const uint32_t original[static TP_CODE_PAGE_WORDS]) {
    if (page->address == NULL) return;
    if (!tp_code_page_current(page, expected) || !tp_code_page_matches(page->original_view, &page->source, original))
        page_fatal("RELEASE_IDENTITY", page->address);
    if (page->anonymous) {
        publish(page->original_view, page->address);
        if (!tp_code_page_matches(page->address, &page->source, original)) page_fatal("RESTORE_FILE", page->address);
        report.live_anonymous_pages--;
        report.restored++;
    } else discard(page->original_view);
    *page = (struct tp_code_page){0};
}
