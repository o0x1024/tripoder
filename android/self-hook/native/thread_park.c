#define _GNU_SOURCE
#include "tripoder_thread_park.h"
#include "park_syscall.h"
#include "park_diagnostic.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/futex.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <ucontext.h>
#include <unistd.h>

_Static_assert(ATOMIC_BOOL_LOCK_FREE == 2 && ATOMIC_INT_LOCK_FREE == 2 && ATOMIC_LLONG_LOCK_FREE == 2,
               "handler atomics must be lock free");
_Static_assert(sizeof(_Atomic unsigned) == 4, "Linux futex word size");
enum slot_state { PENDING = 1, ENTERING, PARKED, RETURNED, GONE, CANCELLED };
struct park_slot {
    _Atomic unsigned long long control; /* epoch and state form one indivisible identity */
    _Atomic int tid;
    uintptr_t pc, sp, lr;              /* ENTERING owns writes; PARKED release publishes */
};
static struct {
    _Atomic bool ready, busy;
    _Atomic int owner;
    _Atomic unsigned released, late[1025];
    int pid, uid, directory;
    unsigned epoch, count;
    bool active;
    siginfo_t signal_info;
    struct park_slot slots[TP_PARK_MAX_THREADS];
} park;

static unsigned long long identity(unsigned epoch, enum slot_state state) {
    return ((unsigned long long)epoch << 32) | (unsigned)state;
}
static unsigned state_of(unsigned long long value) { return (unsigned)value; }
static void fatal_release(void) {
    (void)tp_park_syscall(SYS_exit_group, 192, 0, 0, 0, 0, 0);
    __builtin_trap();
}

static void park_handler(int signal, siginfo_t *info, void *raw_context) {
    if (signal != TP_PARK_SIGNAL || info == NULL || raw_context == NULL ||
        info->si_code != SI_QUEUE || info->si_pid != park.pid || info->si_uid != (unsigned)park.uid) return;
    unsigned epoch = (unsigned)info->si_value.sival_int;
    if (epoch == 0 || epoch > 1024) return;
    if (atomic_load_explicit(&park.released, memory_order_acquire) >= epoch) {
        atomic_fetch_add_explicit(&park.late[epoch], 1, memory_order_relaxed);
        return;
    }
    int tid = (int)tp_park_syscall(SYS_gettid, 0, 0, 0, 0, 0, 0);
    for (unsigned i = 0; i < TP_PARK_MAX_THREADS; i++) {
        struct park_slot *slot = &park.slots[i];
        if (atomic_load_explicit(&slot->tid, memory_order_relaxed) != tid) continue;
        unsigned long long expected = identity(epoch, PENDING);
        if (!atomic_compare_exchange_strong_explicit(&slot->control, &expected,
                identity(epoch, ENTERING), memory_order_acq_rel, memory_order_acquire)) continue;
        ucontext_t *context = raw_context;
        slot->pc = context->uc_mcontext.pc;
        slot->sp = context->uc_mcontext.sp;
        slot->lr = context->uc_mcontext.regs[30];
        atomic_store_explicit(&slot->control, identity(epoch, PARKED), memory_order_release);
        for (;;) {
            unsigned released = atomic_load_explicit(&park.released, memory_order_acquire);
            if (released >= epoch) break;
            (void)tp_park_syscall(SYS_futex, (long)&park.released, FUTEX_WAIT_PRIVATE, released, 0, 0, 0);
        }
        atomic_store_explicit(&slot->control, identity(epoch, RETURNED), memory_order_release);
        return; /* storage/code remain alive even after RETURNED and before rt_sigreturn */
    }
}

enum tp_park_status tp_park_init(void) {
    if (atomic_load_explicit(&park.ready, memory_order_acquire)) return TP_PARK_BUSY;
    bool expected = false;
    if (!atomic_compare_exchange_strong(&park.busy, &expected, true)) return TP_PARK_BUSY;
    if (atomic_load_explicit(&park.ready, memory_order_acquire)) {
        atomic_store(&park.busy, false); return TP_PARK_BUSY;
    }
    enum tp_park_status status = TP_PARK_SYSTEM;
    struct sigaction old, action = {0};
    if (sigaction(TP_PARK_SIGNAL, NULL, &old) != 0) goto done;
    if (old.sa_handler != SIG_DFL) { status = TP_PARK_SIGNAL_OWNED; goto done; }
    int fd = open("/proc/self/task", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) goto done;
    park.pid = getpid(); park.uid = getuid(); park.directory = fd;
    park.signal_info.si_signo = TP_PARK_SIGNAL;
    park.signal_info.si_code = SI_QUEUE;
    park.signal_info.si_pid = park.pid;
    park.signal_info.si_uid = (unsigned)park.uid;
    action.sa_sigaction = park_handler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    sigfillset(&action.sa_mask);
    if (sigaction(TP_PARK_SIGNAL, &action, NULL) != 0) { close(fd); goto done; }
    atomic_store_explicit(&park.ready, true, memory_order_release);
    status = TP_PARK_OK;
done:
    atomic_store(&park.busy, false);
    return status;
}

enum tp_park_status tp_park_prepare_process(void) {
    if(!atomic_load_explicit(&park.ready,memory_order_acquire))return tp_park_init();
    struct sigaction action;
    if(sigaction(TP_PARK_SIGNAL,NULL,&action)!=0)return TP_PARK_SYSTEM;
    return action.sa_sigaction==park_handler&&(action.sa_flags&SA_SIGINFO)?TP_PARK_OK:TP_PARK_SIGNAL_OWNED;
}

struct kernel_dirent64 {
    uint64_t inode;
    int64_t offset;
    unsigned short length;
    unsigned char type;
    char name[];
};

/* Only direct syscalls and fixed storage from here until all entered handlers return. */
static enum tp_park_status add_thread(int tid, struct tp_park_snapshot *snapshot, unsigned *added) {
    if (tid == atomic_load_explicit(&park.owner, memory_order_relaxed)) return TP_PARK_OK;
    for (unsigned i = 0; i < park.count; i++) {
        if (atomic_load_explicit(&park.slots[i].tid, memory_order_relaxed) != tid) continue;
        if (state_of(atomic_load_explicit(&park.slots[i].control, memory_order_acquire)) == GONE)
            return TP_PARK_INVENTORY; /* an exited identity reappeared; no reuse assumption */
        return TP_PARK_OK;
    }
    if (park.count == TP_PARK_MAX_THREADS) return TP_PARK_LIMIT;
    struct park_slot *slot = &park.slots[park.count++];
    atomic_store_explicit(&slot->tid, tid, memory_order_relaxed);
    atomic_store_explicit(&slot->control, identity(park.epoch, PENDING), memory_order_release);
    snapshot->observed = park.count;
    (*added)++;
    long sent = tp_park_syscall(SYS_rt_tgsigqueueinfo, park.pid, tid, TP_PARK_SIGNAL,
                               (long)&park.signal_info, 0, 0);
    if (sent == -ESRCH) {
        unsigned long long expected = identity(park.epoch, PENDING);
        (void)atomic_compare_exchange_strong(&slot->control, &expected, identity(park.epoch, GONE));
        return TP_PARK_OK;
    }
    if (sent != 0) { snapshot->system_error = (int)-sent; return TP_PARK_SYSTEM; }
    return TP_PARK_OK;
}

static enum tp_park_status scan(struct tp_park_snapshot *snapshot, unsigned *added) {
    _Alignas(8) unsigned char buffer[4096];
    if (tp_park_syscall(SYS_lseek, park.directory, 0, SEEK_SET, 0, 0, 0) < 0) return TP_PARK_SYSTEM;
    for (;;) {
        long size = tp_park_syscall(SYS_getdents64, park.directory, (long)buffer, sizeof(buffer), 0, 0, 0);
        if (size == 0) return TP_PARK_OK;
        if (size < 0) { snapshot->system_error = (int)-size; return TP_PARK_SYSTEM; }
        for (size_t at = 0; at < (size_t)size;) {
            struct kernel_dirent64 *entry = (void *)(buffer + at);
            size_t prefix = offsetof(struct kernel_dirent64, name);
            if ((size_t)size - at < prefix + 1 || entry->length < prefix + 1 ||
                entry->length > (size_t)size - at || (entry->length & 7)) return TP_PARK_INVENTORY;
            int tid = 0;
            bool terminated = false;
            for (size_t i = 0; i < entry->length - prefix; i++) {
                unsigned char c = (unsigned char)entry->name[i];
                if (c == 0) { terminated = true; break; }
                if (c == '.' && i == 0) { terminated = true; break; }
                if (c < '0' || c > '9' || tid > (INT_MAX - (c - '0')) / 10) return TP_PARK_INVENTORY;
                tid = tid * 10 + c - '0';
            }
            if (!terminated) return TP_PARK_INVENTORY;
            if (tid > 0) {
                enum tp_park_status status = add_thread(tid, snapshot, added);
                if (status != TP_PARK_OK) return status;
            }
            at += entry->length;
        }
    }
}

static enum tp_park_status await_threads(struct tp_park_snapshot *snapshot, uint64_t deadline) {
    for (;;) {
        unsigned pending = 0, parked = 0, vanished = 0;
        snapshot->pending_tid = 0;
        for (unsigned i = 0; i < park.count; i++) {
            struct park_slot *slot = &park.slots[i];
            unsigned state = state_of(atomic_load_explicit(&slot->control, memory_order_acquire));
            if (state == PARKED) { parked++; continue; }
            if (state == GONE) { vanished++; continue; }
            if (state != PENDING && state != ENTERING) return TP_PARK_INVENTORY;
            if (state == PENDING) {
                long exists = tp_park_syscall(SYS_tgkill, park.pid, atomic_load(&slot->tid), 0, 0, 0, 0);
                if (exists == -ESRCH) {
                    unsigned long long expected = identity(park.epoch, PENDING);
                    if (atomic_compare_exchange_strong(&slot->control, &expected, identity(park.epoch, GONE))) {
                        vanished++; continue;
                    }
                } else if (exists != 0) { snapshot->system_error = (int)-exists; return TP_PARK_SYSTEM; }
            }
            pending++;
            if (snapshot->pending_tid == 0) snapshot->pending_tid = atomic_load(&slot->tid);
        }
        snapshot->parked = parked; snapshot->vanished = vanished;
        if (pending == 0) return TP_PARK_OK;
        uint64_t now = tp_park_now();
        if (now == 0) return TP_PARK_SYSTEM;
        if (now >= deadline) {
            snapshot->pending_mask_valid = tp_park_blocked_mask(park.directory, snapshot->pending_tid,
                                                               &snapshot->pending_signal_mask);
            return TP_PARK_TIMEOUT;
        }
        tp_park_sleep(100000);
    }
}

static void release_all(void) {
    for (unsigned i = 0; i < park.count; i++) {
        unsigned long long expected = identity(park.epoch, PENDING);
        (void)atomic_compare_exchange_strong(&park.slots[i].control, &expected, identity(park.epoch, CANCELLED));
    }
    atomic_store_explicit(&park.released, park.epoch, memory_order_release);
    (void)tp_park_syscall(SYS_futex, (long)&park.released, FUTEX_WAKE_PRIVATE, INT_MAX, 0, 0, 0);
    uint64_t start = tp_park_now();
    if (start == 0) fatal_release();
    for (;;) {
        bool entered = false;
        for (unsigned i = 0; i < park.count; i++) {
            unsigned state = state_of(atomic_load_explicit(&park.slots[i].control, memory_order_acquire));
            if (state == ENTERING || state == PARKED) entered = true;
        }
        if (!entered) break;
        uint64_t now = tp_park_now();
        if (now == 0 || now - start >= UINT64_C(1000000000)) fatal_release();
        tp_park_sleep(100000);
    }
    park.active = false;
    atomic_store(&park.owner, 0);
    atomic_store_explicit(&park.busy, false, memory_order_release);
}

enum tp_park_status tp_park_begin(struct tp_park_snapshot *snapshot) {
    if (snapshot == NULL || !atomic_load_explicit(&park.ready, memory_order_acquire)) return TP_PARK_NOT_READY;
    bool expected = false;
    if (!atomic_compare_exchange_strong_explicit(&park.busy, &expected, true,
            memory_order_acq_rel, memory_order_acquire)) return TP_PARK_BUSY;
    /* Before the first signal, libc is still allowed. Verify reservation has not changed. */
    struct sigaction action;
    if (sigaction(TP_PARK_SIGNAL, NULL, &action) != 0 || action.sa_sigaction != park_handler ||
        !(action.sa_flags & SA_SIGINFO)) {
        atomic_store(&park.busy, false); return TP_PARK_SIGNAL_OWNED;
    }
    if (park.epoch == 1024) { atomic_store(&park.busy, false); return TP_PARK_LIMIT; }
    park.epoch++; park.count = 0;
    park.signal_info.si_value.sival_int = (int)park.epoch;
    atomic_store(&park.owner, (int)tp_park_syscall(SYS_gettid, 0, 0, 0, 0, 0, 0));
    snapshot->epoch = park.epoch; snapshot->observed = 0; snapshot->parked = 0;
    snapshot->vanished = 0; snapshot->passes = 0; snapshot->system_error = 0;
    snapshot->pending_tid = 0; snapshot->pending_mask_valid = 0; snapshot->pending_signal_mask = 0;
    uint64_t now = tp_park_now();
    enum tp_park_status status = TP_PARK_SYSTEM;
    if (now == 0) goto failed;
    uint64_t deadline = now + UINT64_C(100000000);
    for (unsigned pass = 0; pass < 16; pass++) {
        unsigned added = 0;
        status = scan(snapshot, &added);
        snapshot->passes++;
        if (status != TP_PARK_OK) goto failed;
        status = await_threads(snapshot, deadline);
        if (status != TP_PARK_OK) goto failed;
        now = tp_park_now();
        if (now == 0) { status = TP_PARK_SYSTEM; goto failed; }
        if (now >= deadline) { status = TP_PARK_TIMEOUT; goto failed; }
        if (added == 0) { park.active = true; return TP_PARK_OK; }
    }
    status = TP_PARK_LIMIT;
failed:
    release_all();
    return status;
}

static enum tp_park_status owned(unsigned epoch) {
    if (atomic_load_explicit(&park.owner, memory_order_acquire) !=
        (int)tp_park_syscall(SYS_gettid, 0, 0, 0, 0, 0, 0)) return TP_PARK_WRONG_OWNER;
    return park.active && epoch == park.epoch ? TP_PARK_OK : TP_PARK_STALE;
}
enum tp_park_status tp_park_end(unsigned epoch) {
    enum tp_park_status status = owned(epoch);
    if (status == TP_PARK_OK) release_all();
    return status;
}
enum tp_park_status tp_park_context(unsigned epoch, unsigned index, struct tp_park_context *context) {
    enum tp_park_status status = owned(epoch);
    if (status != TP_PARK_OK) return status;
    if (context == NULL || index >= park.count) return TP_PARK_INVENTORY;
    struct park_slot *slot = &park.slots[index];
    if (state_of(atomic_load_explicit(&slot->control, memory_order_acquire)) != PARKED) return TP_PARK_INVENTORY;
    context->tid = atomic_load(&slot->tid);
    context->pc = slot->pc; context->sp = slot->sp; context->lr = slot->lr;
    return TP_PARK_OK;
}
unsigned tp_park_late_signals(unsigned epoch) {
    return epoch > 0 && epoch <= 1024 ? atomic_load(&park.late[epoch]) : 0;
}
