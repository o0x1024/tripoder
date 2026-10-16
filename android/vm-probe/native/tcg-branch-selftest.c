/* Exercise real near/far ARM64 TB linking while other threads execute it. */
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <sys/mman.h>
#include <unistd.h>

extern void tb_target_set_jmp_target(uintptr_t code, uintptr_t jump, uintptr_t target);
static pthread_barrier_t start_barrier;
static int (*entry)(void);
enum { WORKERS = 4, READS = 250000, PATCHES = 100000 };

static void require(int condition) {
    if (!condition) {
        fprintf(stderr, "TRIPODER_TCG_BRANCH_SELFTEST_FAILED\n");
        abort();
    }
}

static void *reader(void *opaque) {
    (void)opaque;
    int status = pthread_barrier_wait(&start_barrier);
    require(status == 0 || status == PTHREAD_BARRIER_SERIAL_THREAD);
    for (unsigned i = 0; i < READS; ++i) {
        int value = entry();
        require(value == 11 || value == 22 || value == 33);
    }
    return NULL;
}

static void emit_return(uint32_t *code, unsigned value) {
    code[0] = 0xd503249f; /* BTI j */
    code[1] = 0x52800000 | (value << 5); /* MOV W0, value */
    code[2] = 0xd65f0120; /* RET X9; X30 is the TCG scratch register */
}

void tripoder_tcg_branch_selftest(void) {
    size_t span = 320UL * 1024 * 1024;
    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    /* Reserve address distance, committing only three pages. */
    char *area = mmap(NULL, span, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(area != MAP_FAILED);
    char *middle = area + span / 2;
    char *last = area + span - page;
    char *pages[] = {area, middle, last};
    for (unsigned i = 0; i < 3; ++i) {
        require(mprotect(pages[i], page, PROT_READ | PROT_WRITE | PROT_EXEC) == 0);
    }
    uint32_t *code = (uint32_t *)middle;
    code[0] = 0xd503245f; /* BTI c */
    code[1] = 0xaa1e03e9; /* MOV X9, X30: preserve the test caller return PC */
    code[2] = 0x14000004; /* reset jumps over BR and literal */
    code[3] = 0xd61f03c0; /* immutable BR X30 */
    uintptr_t targets[] = {(uintptr_t)(middle + 24), (uintptr_t)area, (uintptr_t)last};
    for (unsigned i = 0; i < 3; ++i) emit_return((uint32_t *)targets[i], 11 * (i + 1));
    /* Resume precisely at BR after the earlier LDR has already completed. */
    code[16] = 0xd503245f; /* BTI c */
    code[17] = 0xaa1e03e9; /* MOV X9, X30 */
    code[18] = 0xaa0003fe; /* MOV X30, X0: saved result of the old LDR */
    code[19] = 0x17fffff0; /* B middle+12, the unmodified second instruction */
    for (unsigned i = 0; i < 3; ++i) __builtin___clear_cache(pages[i], pages[i] + page);
    entry = (int (*)(void))middle;
    uintptr_t jump = (uintptr_t)&code[2];
    for (unsigned i = 0; i < 3; ++i) {
        tb_target_set_jmp_target((uintptr_t)middle, jump, targets[i]);
        require(code[3] == 0xd61f03c0 && *(uint64_t *)(jump + 8) == targets[i]);
        if (i) require(code[2] == 0x5800005e); /* LDR X30, PC+8 */
        require(entry() == (int)(11 * (i + 1)));
    }
    require(pthread_barrier_init(&start_barrier, NULL, WORKERS + 1) == 0);
    pthread_t threads[WORKERS];
    for (unsigned i = 0; i < WORKERS; ++i) require(pthread_create(&threads[i], NULL, reader, NULL) == 0);
    int status = pthread_barrier_wait(&start_barrier);
    require(status == 0 || status == PTHREAD_BARRIER_SERIAL_THREAD);
    for (unsigned i = 0; i < PATCHES; ++i) {
        tb_target_set_jmp_target((uintptr_t)middle, jump, targets[i % 3]);
    }
    for (unsigned i = 0; i < WORKERS; ++i) require(pthread_join(threads[i], NULL) == 0);
    int (*resume)(uintptr_t) = (int (*)(uintptr_t))(middle + 64);
    for (unsigned i = 0; i < 3; ++i) {
        tb_target_set_jmp_target((uintptr_t)middle, jump, targets[(i + 1) % 3]);
        require(resume(targets[i]) == (int)(11 * (i + 1)));
    }
    require(pthread_barrier_destroy(&start_barrier) == 0);
    require(munmap(area, span) == 0);
    fprintf(stderr, "TRIPODER_TCG_BRANCH_SELFTEST_PASS threads=%d executions=%d patches=%d reset=1 far_positive=1 far_negative=1 suspended_resumes=3\n",
            WORKERS, WORKERS * READS + 6, PATCHES + 6);
}
