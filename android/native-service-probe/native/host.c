// Hosts an ANativeService entry point inside an ordinary isolated Java process:
// the platform's native-service process is not used. ANativeService is five
// callback slots, so the host owns an equivalent record and drives the callbacks.
#include <android/binder_ibinder.h>
#include <android/binder_ibinder_jni.h>
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define TAG "NativeHost"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef void (*create_fn)(void *service);
typedef AIBinder *(*bind_fn)(void *service, uint64_t token, const char *action, const char *data);
typedef void (*set_fn)(void *service, void *callback);

static uint16_t u16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t u32(const uint8_t *p) { return (uint32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24)); }

// Locate a stored entry's data offset by its name; returns -1 when absent or not stored.
static int64_t stored_entry(int fd, const char *name, uint64_t *size) {
    struct stat st;
    if (fstat(fd, &st) || st.st_size < 22) return -1;
    size_t tail = st.st_size < 70000 ? (size_t)st.st_size : 70000;
    uint8_t *buffer = malloc(tail);
    if (!buffer || pread(fd, buffer, tail, st.st_size - tail) != (ssize_t)tail) { free(buffer); return -1; }
    int64_t eocd = -1;
    for (int64_t i = (int64_t)tail - 22; i >= 0; i--) if (u32(buffer + i) == 0x06054b50) { eocd = i; break; }
    if (eocd < 0) { free(buffer); return -1; }
    uint32_t cd_size = u32(buffer + eocd + 12), cd_offset = u32(buffer + eocd + 16);
    uint16_t count = u16(buffer + eocd + 10);
    free(buffer);
    uint8_t *cd = malloc(cd_size);
    if (!cd || pread(fd, cd, cd_size, cd_offset) != (ssize_t)cd_size) { free(cd); return -1; }
    size_t at = 0, name_len = strlen(name);
    int64_t result = -1;
    for (uint16_t i = 0; i < count && at + 46 <= cd_size; i++) {
        uint16_t method = u16(cd + at + 10), n = u16(cd + at + 28), extra = u16(cd + at + 30), comment = u16(cd + at + 32);
        uint32_t csize = u32(cd + at + 20), local = u32(cd + at + 42);
        if (n == name_len && !memcmp(cd + at + 46, name, n)) {
            uint8_t header[30];
            if (method == 0 && pread(fd, header, 30, local) == 30 && u32(header) == 0x04034b50) {
                result = (int64_t)local + 30 + u16(header + 26) + u16(header + 28);
                *size = csize;
            }
            break;
        }
        at += 46 + n + extra + comment;
    }
    free(cd);
    return result;
}

JNIEXPORT jobject JNICALL Java_dev_tripoder_nativeserviceprobe_HostService_nativeStart(JNIEnv *env, jclass type, jstring apk_path, jstring entry, jstring function, jstring action, jstring data) {
    (void)type;
    const char *apk = (*env)->GetStringUTFChars(env, apk_path, NULL), *name = (*env)->GetStringUTFChars(env, entry, NULL), *fn = (*env)->GetStringUTFChars(env, function, NULL);
    const char *act = action ? (*env)->GetStringUTFChars(env, action, NULL) : NULL, *dat = data ? (*env)->GetStringUTFChars(env, data, NULL) : NULL;
    jobject result = NULL;
    int fd = open(apk, O_RDONLY | O_CLOEXEC);
    uint64_t size = 0;
    int64_t offset = fd < 0 ? -1 : stored_entry(fd, name, &size);
    LOG("open=%d offset=%lld size=%llu", fd, (long long)offset, (unsigned long long)size);
    if (offset >= 0) {
        android_dlextinfo info = {0};
        info.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_USE_LIBRARY_FD_OFFSET;
        info.library_fd = fd;
        info.library_fd_offset = (off64_t)offset;
        void *library = android_dlopen_ext(strrchr(name, '/') ? strrchr(name, '/') + 1 : name, RTLD_NOW | RTLD_LOCAL, &info);
        LOG("dlopen=%p err=%s", library, library ? "" : dlerror());
        create_fn create = library ? (create_fn)dlsym(library, fn) : NULL;
        LOG("create=%p", create);
        if (create) {
            void **service = calloc(1, 256);
            create(service);
            LOG("callbacks bind=%p unbind=%p rebind=%p destroy=%p trim=%p", service[0], service[1], service[2], service[3], service[4]);
            if (service[0]) {
                AIBinder *binder = ((bind_fn)service[0])(service, 1, act, dat);
                LOG("onBind=%p", binder);
                if (binder) result = AIBinder_toJavaBinder(env, binder);
            }
        }
    }
    if (fd >= 0 && !result) close(fd);
    return result;
}
