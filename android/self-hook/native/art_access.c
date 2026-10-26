#define _GNU_SOURCE
#include "art_access.h"
#include "art_fixed_contract.h"
#include "art_managed_contract.h"
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <pthread.h>
#include <stdatomic.h>
#include <time.h>
#include <unistd.h>

static FILE *raw_maps_open(void) {
    int fd = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return NULL;
    FILE *stream = fdopen(fd, "r");
    if (!stream) { int saved = errno; syscall(SYS_close, fd); errno = saved; }
    return stream;
}

static const char *path = "/apex/com.android.art/lib64/libart.so";
static int find(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    if (strcmp(info->dlpi_name, path) != 0) return 0;
    *(uintptr_t *)data = info->dlpi_addr;
    return 1;
}
/* One live /proc/self/maps observation shared by the checks of a single bind call. The kernel generates the file
 * (about 10 ms in a large process) on every open, so a session reads it once and every check in the call parses that
 * observation. Nothing outlives the call and no later call reuses it. */
static __thread char *session_text;
static __thread size_t session_size;
static __thread unsigned session_depth;
void tp_art_maps_session_begin(void) {
    if (session_depth++) return;
    FILE *stream = raw_maps_open();
    if (!stream) return;
    size_t capacity = 1 << 20, size = 0;
    char *text = malloc(capacity);
    while (text) {
        size_t got = fread(text + size, 1, capacity - size, stream);
        size += got;
        if (size < capacity) break;
        char *grown = realloc(text, capacity *= 2);
        if (!grown) { free(text); text = NULL; break; }
        text = grown;
    }
    int failed = ferror(stream);
    fclose(stream);
    if (text && !failed) { session_text = text; session_size = size; } else free(text);
}
void tp_art_maps_session_end(void) {
    if (!session_depth || --session_depth) return;
    free(session_text); session_text = NULL; session_size = 0;
}
static int mapped_inner(uintptr_t address, unsigned bytes, uintptr_t rva, const struct stat *file) {
    if (address == 0 || address > UINTPTR_MAX - bytes) return 0;
    FILE *stream = session_text ? fmemopen(session_text, session_size, "r") : raw_maps_open();
    if (stream == NULL) return 0;
    char line[2048], mode[5];
    unsigned long start, end, offset, inode;
    unsigned major, minor;
    int result = 0;
    while (fgets(line, sizeof(line), stream)) {
        // Most records cannot contain this address. Parse their range only;
        // the complete existing identity check still runs on a matching map.
        // This avoids formatting seven fields thousands of times per method,
        // without caching a mapping or weakening the live /proc observation.
        char *separator,*range_end;
        unsigned long range_start=strtoul(line,&separator,16);
        if(separator==line||*separator!='-')continue;
        unsigned long range_limit=strtoul(separator+1,&range_end,16);
        if(range_end==separator+1||*range_end!=' '||range_start>address||address+bytes>range_limit)continue;
        if (sscanf(line, "%lx-%lx %4s %lx %x:%x %lu", &start, &end, mode, &offset, &major, &minor, &inode) != 7)
            continue;
        if (start <= address && address + bytes <= end) {
            result = mode[0] == 'r';
            if (file != NULL) result = result && strcmp(mode, "r-xp") == 0 && inode == (unsigned long)file->st_ino
                && major == major(file->st_dev) && minor == minor(file->st_dev) && offset + address - start == rva;
            break;
        }
    }
    fclose(stream);
    return result;
}
static _Atomic uint64_t mapping_calls,mapping_ns,refresh_count;
uint64_t tp_art_refresh_count(void){return atomic_load(&refresh_count);}
static uint64_t monotonic_ns(void){struct timespec value;return clock_gettime(CLOCK_MONOTONIC,&value)==0?(uint64_t)value.tv_sec*1000000000u+(uint64_t)value.tv_nsec:0;}
static int mapped(uintptr_t address,unsigned bytes,uintptr_t rva,const struct stat *file){
    uint64_t start=monotonic_ns();int result=mapped_inner(address,bytes,rva,file);uint64_t end=monotonic_ns();
    atomic_fetch_add(&mapping_calls,1);if(start&&end>=start)atomic_fetch_add(&mapping_ns,end-start);return result;
}
void tp_art_mapping_metrics(uint64_t *calls,uint64_t *nanoseconds){*calls=atomic_load(&mapping_calls);*nanoseconds=0;*nanoseconds=atomic_load(&mapping_ns);}
struct tp_readable_range { uintptr_t begin, end; };
static pthread_mutex_t readable_lock = PTHREAD_MUTEX_INITIALIZER;
static struct tp_readable_range readable_ranges[32768];
static unsigned readable_count;
static int refresh_readable_ranges_locked(uintptr_t address, unsigned bytes) {
    atomic_fetch_add(&refresh_count,1);
    FILE *maps = raw_maps_open();
    if (!maps) return 0;
    readable_count = 0;
    char line[2048], mode[5];
    unsigned long start, end;
    int found = 0;
    while (fgets(line, sizeof(line), maps)) {
        char *sep, *limit_end;
        start = strtoul(line, &sep, 16);
        if (sep == line || *sep != '-') continue;
        end = strtoul(sep + 1, &limit_end, 16);
        if (limit_end == sep + 1 || *limit_end != ' ') continue;
        if (limit_end[1] != 'r') continue;
        (void)mode;
        // The bounded cache only accelerates later lookups. Its capacity must
        // not discard the result of this complete kernel mapping observation.
        if (start <= address && address + bytes <= end) found = 1;
        if (readable_count && readable_ranges[readable_count - 1].end == start) {
            readable_ranges[readable_count - 1].end = end;
        } else if (readable_count < 32768) {
            readable_ranges[readable_count++] = (struct tp_readable_range){start, end};
        }
    }
    fclose(maps);
    return found;
}
int tp_art_readable(uintptr_t address, unsigned bytes) {
    // The pinned ARM64 platform tags malloc pointers. Preserve the access
    // pointer's tag, stripping only the TBI byte when consulting canonical maps.
    address &= UINT64_C(0x00ffffffffffffff);
    if (address == 0 || address > UINTPTR_MAX - bytes) return 0;
    uint64_t start = monotonic_ns();
    int result = 0;
    pthread_mutex_lock(&readable_lock);
    // /proc/self/maps is address-ordered and the cache keeps that order, so the containing range is found by bisection.
    unsigned low = 0, high = readable_count;
    while (low < high) {
        unsigned mid = low + (high - low) / 2;
        if (readable_ranges[mid].begin <= address) low = mid + 1; else high = mid;
    }
    if (low && address + bytes <= readable_ranges[low - 1].end) result = 1;
    if (!result) result = refresh_readable_ranges_locked(address, bytes);
    pthread_mutex_unlock(&readable_lock);
    uint64_t end = monotonic_ns();
    atomic_fetch_add(&mapping_calls, 1);
    if (start && end >= start) atomic_fetch_add(&mapping_ns, end - start);
    return result;
}
int tp_art_validate_code(const struct tp_art_access *access,uintptr_t rva,const void *bytes,unsigned size) {
    struct stat file;
    return access&&access->base&&rva<=UINTPTR_MAX-access->base&&stat(path,&file)==0&&
        mapped(access->base+rva,size,rva,&file)&&memcmp((void *)(access->base+rva),bytes,size)==0;
}
static int verify_access(struct tp_art_access *result) {
    uintptr_t base = 0;
    struct stat file;
    if (stat(path, &file) != 0 || !S_ISREG(file.st_mode)) return 0;
    dl_iterate_phdr(find, &base);
    if (base == 0 || base > UINTPTR_MAX - TP_ART_CURRENT_RVA) return 0;
#define VERIFY(name, NAME) \
    if (!mapped(base + TP_ART_##NAME##_RVA, sizeof(tp_art_##name##_bytes), TP_ART_##NAME##_RVA, &file) \
        || memcmp((void *)(base + TP_ART_##NAME##_RVA), tp_art_##name##_bytes, sizeof(tp_art_##name##_bytes))) return 0
    VERIFY(current, CURRENT);
    VERIFY(suspend, SUSPEND);
    VERIFY(resume, RESUME);
#undef VERIFY
    *result = (struct tp_art_access){base, (void *(*)(void))(base + TP_ART_CURRENT_RVA),
        (void (*)(void *, const char *, bool))(base + TP_ART_SUSPEND_RVA),
        (void (*)(void *))(base + TP_ART_RESUME_RVA)};
    return 1;
}
/* These three pinned VM routines are not Hook targets and libart is the
 * process-lifetime ART owner. Bind their proven mapping/code once, rather than
 * rescanning every mapping three times for every managed method snapshot.
 * A failed first binding remains failed; no retry or alternate identity. */
static pthread_once_t access_once=PTHREAD_ONCE_INIT;
static struct tp_art_access process_access;
static bool process_access_valid;
static void initialize_access(void){process_access_valid=verify_access(&process_access);}
int tp_art_access_bind(struct tp_art_access *result){
    if(!result||pthread_once(&access_once,initialize_access)||!process_access_valid)return 0;
    *result=process_access;return 1;
}
static _Atomic jfieldID art_method_field;
uintptr_t tp_art_method_address(JNIEnv *env, jobject method) {
    jfieldID field = atomic_load_explicit(&art_method_field, memory_order_acquire);
    if (field == NULL) {
        jclass executable = (*env)->FindClass(env, "java/lang/reflect/Executable");
        if (executable == NULL) return 0;
        field = (*env)->GetFieldID(env, executable, "artMethod", "J");
        (*env)->DeleteLocalRef(env, executable);
        if (field == NULL) return 0;
        atomic_store_explicit(&art_method_field, field, memory_order_release);
    }
    uintptr_t address = (uintptr_t)(*env)->GetLongField(env, method, field);
    if ((*env)->ExceptionCheck(env) || (address & 7) || !tp_art_readable(address, 32)) return 0;
    return address;
}
int tp_art_method_observe(JNIEnv *env,jobject method,uintptr_t *observed,struct tp_art_method_view *result) {
    _Static_assert(sizeof(struct tp_art_method_view) == 32, "fixed 64-bit ArtMethod evidence");
    if(!method||!observed||!result)return 0;
    struct tp_art_access access;
    if (!tp_art_access_bind(&access)) return 0;
    uintptr_t address = tp_art_method_address(env, method);
    if (!address || access.current() == NULL) return 0;
    char scope = 0;
    uint32_t words[8];
    access.suspend(&scope, "tripoder-owned-method-snapshot", false);
    for (unsigned i = 0; i < 8; i++) words[i] = ((volatile uint32_t *)address)[i];
    access.resume(&scope);
    memcpy(result, words, sizeof(words));
    *observed=address;
    return 1;
}
int tp_art_method_snapshot(JNIEnv *env,jobject method,struct tp_art_method_view *result){
    uintptr_t address;return tp_art_method_observe(env,method,&address,result);
}
int tp_art_managed_bind(struct tp_art_managed_access *result) {
    struct stat file;
    struct tp_art_access access;
    if (!tp_art_access_bind(&access) || stat(path, &file) != 0) return 0;
    uintptr_t base = access.base;
#define VERIFY(name, NAME) \
    if (!mapped(base + TP_ART_##NAME##_RVA, sizeof(tp_art_##name##_bytes), TP_ART_##NAME##_RVA, &file) \
        || memcmp((void *)(base + TP_ART_##NAME##_RVA), tp_art_##name##_bytes, sizeof(tp_art_##name##_bytes))) return 0
    VERIFY(jit_suspend, JIT_SUSPEND);
    VERIFY(jit_resume, JIT_RESUME);
    VERIFY(copy, COPY);
    VERIFY(jni_bridge, JNI_BRIDGE);
    VERIFY(interpreter_bridge, INTERPRETER_BRIDGE);
#undef VERIFY
    *result = (struct tp_art_managed_access){access,
        (void (*)(void *))(base + TP_ART_JIT_SUSPEND_RVA),
        (void (*)(void *))(base + TP_ART_JIT_RESUME_RVA),
        (void (*)(void *, void *, unsigned))(base + TP_ART_COPY_RVA),
        base + TP_ART_JNI_BRIDGE_RVA, base + TP_ART_INTERPRETER_BRIDGE_RVA};
    return 1;
}
int tp_art_deopt_bind(struct tp_art_deopt_access *result) {
    struct tp_art_managed_access managed;
    struct stat file;
    if (!tp_art_managed_bind(&managed) || stat(path, &file) != 0) return 0;
    uintptr_t base = managed.access.base;
#define VERIFY(name, NAME) \
    if (!mapped(base + TP_ART_##NAME##_RVA, sizeof(tp_art_##name##_bytes), TP_ART_##NAME##_RVA, &file) \
        || memcmp((void *)(base + TP_ART_##NAME##_RVA), tp_art_##name##_bytes, sizeof(tp_art_##name##_bytes))) return 0
    VERIFY(deopt, DEOPT);
    VERIFY(is_deopt, IS_DEOPT);
    // Validate the exact instruction evidence for Runtime's instrumentation pointer.
    // This global deoptimization function is never called by this probe.
    VERIFY(runtime_deopt_evidence, RUNTIME_DEOPT_EVIDENCE);
#undef VERIFY
    uintptr_t slot = base + TP_ART_RUNTIME_INSTANCE_RVA;
    if (!tp_art_readable(slot, 8)) return 0;
    uintptr_t runtime = *(uintptr_t *)slot;
    if (!runtime || runtime > UINTPTR_MAX - TP_ART_RUNTIME_INSTRUMENTATION_OFFSET - 8
        || !tp_art_readable(runtime + TP_ART_RUNTIME_INSTRUMENTATION_OFFSET, 8)) return 0;
    void *instrumentation = *(void **)(runtime + TP_ART_RUNTIME_INSTRUMENTATION_OFFSET);
    if (!tp_art_readable((uintptr_t)instrumentation, 8)) return 0;
    *result = (struct tp_art_deopt_access){managed, instrumentation,
        (void (*)(void *, void *))(base + TP_ART_DEOPT_RVA),
        (bool (*)(void *, void *))(base + TP_ART_IS_DEOPT_RVA)};
    return 1;
}
