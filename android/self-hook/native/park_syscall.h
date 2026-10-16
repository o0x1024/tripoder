#ifndef TRIPODER_PARK_SYSCALL_H
#define TRIPODER_PARK_SYSCALL_H
#include <stdint.h>
#include <sys/syscall.h>
#include <time.h>

#if !defined(__aarch64__)
#error The fixed-platform parking protocol requires AArch64 Linux.
#endif

/* No libc/TLS/errno path: a paused thread can own any user-space library lock. */
static inline long tp_park_syscall(long number, long a, long b, long c, long d, long e, long f) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    register long x8 __asm__("x8") = number;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
    return x0;
}
static inline uint64_t tp_park_now(void) {
    struct timespec value;
    if (tp_park_syscall(SYS_clock_gettime, CLOCK_MONOTONIC, (long)&value, 0, 0, 0, 0) != 0) return 0;
    return (uint64_t)value.tv_sec * UINT64_C(1000000000) + (uint64_t)value.tv_nsec;
}
static inline void tp_park_sleep(long nanoseconds) {
    struct timespec delay;
    delay.tv_sec = 0;
    delay.tv_nsec = nanoseconds;
    (void)tp_park_syscall(SYS_nanosleep, (long)&delay, 0, 0, 0, 0, 0);
}
#endif
