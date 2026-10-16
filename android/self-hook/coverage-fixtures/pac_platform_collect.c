#define _GNU_SOURCE
#include "pac_platform.h"
#include <asm/hwcap.h>
#include <errno.h>
#include <linux/auxvec.h>
#include <linux/prctl.h>
#include <sched.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/prctl.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

#if !defined(__aarch64__) || !defined(__ANDROID__)
#error This collector is for the owned Android ARM64 probe only.
#endif
_Static_assert(HWCAP_PACA == (1UL << 30), "Linux ARM64 PACA ABI");
_Static_assert(HWCAP_CPUID == (1UL << 11), "Linux ARM64 CPUID ABI");
_Static_assert(HWCAP2_BTI == (1UL << 17), "Linux ARM64 BTI ABI");

static struct tp_pac_value aux(unsigned long key) {
    errno = 0;
    unsigned long value = getauxval(key);
    int error = errno;
    return (struct tp_pac_value){value,
        error == ENOENT ? TP_PAC_VALUE_MISSING : error ? TP_PAC_VALUE_ERROR : TP_PAC_VALUE_PRESENT, error};
}
static void text(char out[65], const char *source) {
    size_t length = strnlen(source, 64);
    memcpy(out, source, length); out[length] = 0;
}
int tp_pac_platform_collect(struct tp_pac_platform_report *r) {
    if (!r) return 0;
    int saved_errno = errno;
    memset(r, 0, sizeof(*r));
    r->schema = 1; r->pointer_bits = sizeof(void *) * 8u;
    r->pid = getpid(); r->tid = gettid();
    r->cpu_before = sched_getcpu();
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) == 0)
        r->monotonic_ns = (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
    else r->clock_error = errno;
    struct utsname identity;
    if (uname(&identity) == 0) {
        text(r->kernel_release, identity.release); text(r->kernel_version, identity.version);
        text(r->machine, identity.machine);
    } else r->uname_error = errno;
    r->hwcap = aux(AT_HWCAP); r->hwcap2 = aux(AT_HWCAP2);
    r->hwcap3 = aux(AT_HWCAP3); r->hwcap4 = aux(AT_HWCAP4); r->page_size = aux(AT_PAGESZ);
    errno = 0;
    long keys = prctl(PR_PAC_GET_ENABLED_KEYS, 0UL, 0UL, 0UL, 0UL);
    r->enabled_keys = (struct tp_pac_value){keys < 0 ? 0 : (uint64_t)keys,
        keys < 0 ? TP_PAC_VALUE_ERROR : TP_PAC_VALUE_PRESENT, keys < 0 ? errno : 0};
    if (r->hwcap.status == TP_PAC_VALUE_PRESENT && (r->hwcap.value & HWCAP_CPUID)) {
        // All five are inside the advertised Linux emulation space:
        // Op0=3, Op1=0, CRn=0, CRm=0/4/6. No blind optional-instruction probing.
        __asm__ volatile("mrs %0, S3_0_C0_C6_1" : "=r"(r->isar1));
        __asm__ volatile("mrs %0, S3_0_C0_C6_2" : "=r"(r->isar2));
        __asm__ volatile("mrs %0, S3_0_C0_C6_3" : "=r"(r->isar3));
        __asm__ volatile("mrs %0, S3_0_C0_C4_1" : "=r"(r->pfr1));
        __asm__ volatile("mrs %0, S3_0_C0_C0_0" : "=r"(r->midr));
        r->id_status = TP_PAC_IDS_LINUX_USER_VIEW;
    } else r->id_status = TP_PAC_IDS_NO_CPUID_ABI;
    r->cpu_after = sched_getcpu();
    errno = saved_errno;
    return 1;
}
