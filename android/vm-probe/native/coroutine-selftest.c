/* Regression checks run inside the ordinary APK, under the device's PAC keys. */
#include "qemu/osdep.h"
#include "qemu/coroutine.h"
#include "qemu/thread.h"

enum { WORKERS = 4, BATCH = 96, ROUNDS = 32 };
typedef struct {
    Coroutine *co;
    pid_t owner;
    unsigned visits;
} Sample;

static void __attribute__((noinline)) exercise_frames(Sample *sample, unsigned depth)
{
    volatile unsigned canary = 0xfeed0100 + depth;
    if (depth) {
        exercise_frames(sample, depth - 1);
    } else {
        for (unsigned i = 0; i < 2; i++) {
            g_assert(sample->owner == gettid());
            g_assert(qemu_coroutine_self() == sample->co);
            sample->visits++;
            qemu_coroutine_yield();
        }
    }
    g_assert(canary == 0xfeed0100 + depth);
    g_assert(sample->owner == gettid());
}

static void entry(void *opaque)
{
    Sample *sample = opaque;
    g_assert(qemu_in_coroutine());
    sample->owner = gettid();
    exercise_frames(sample, 4);
    sample->visits++;
}

static void complete(Sample *sample)
{
    for (unsigned i = 0; i < 3; i++) {
        qemu_coroutine_enter(sample->co);
        g_assert(sample->visits == i + 1);
        g_assert(!qemu_in_coroutine());
    }
}

static void *worker(void *opaque)
{
    /* Created by the submitting thread, first entered by this worker. */
    complete(opaque);
    for (unsigned round = 0; round < ROUNDS; round++) {
        Sample batch[BATCH] = {0};
        for (unsigned i = 0; i < BATCH; i++) {
            batch[i].co = qemu_coroutine_create(entry, &batch[i]);
        }
        for (unsigned i = 0; i < BATCH; i++) {
            complete(&batch[i]);
        }
    }
    return NULL;
}

void tripoder_coroutine_selftest(void)
{
    QemuThread threads[WORKERS];
    Sample transferred[WORKERS] = {0};
    /* The JNI caller is a Java-created thread. Warm its pool before handing
     * a completed stack to a new execution thread. */
    Sample warm = {0};
    warm.co = qemu_coroutine_create(entry, &warm);
    complete(&warm);
    for (unsigned i = 0; i < WORKERS; i++) {
        transferred[i].co = qemu_coroutine_create(entry, &transferred[i]);
        qemu_thread_create(&threads[i], "co-selftest", worker, &transferred[i], QEMU_THREAD_JOINABLE);
    }
    for (unsigned i = 0; i < WORKERS; i++) {
        qemu_thread_join(&threads[i]);
        g_assert(transferred[i].visits == 3);
    }
    fprintf(stderr, "TRIPODER_COROUTINE_SELFTEST_PASS threads=%d coroutines=%d switches=%d\n",
            WORKERS, WORKERS * (BATCH * ROUNDS + 1) + 1, (WORKERS * (BATCH * ROUNDS + 1) + 1) * 6);
    fflush(stderr);
}
