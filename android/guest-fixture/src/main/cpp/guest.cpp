#include <jni.h>
#include <link.h>
#include <elf.h>
#include <cstring>
#include <string>
#include <cstdint>
#include <cerrno>
#include <unistd.h>
#include <signal.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

extern "C" __attribute__((visibility("default"), noinline))
int guest_delay_ms(int base) { return base * 3 + 7; }

extern "C" __attribute__((visibility("default"), noinline))
uint64_t SSL_new(uint64_t ctx) { return ctx ^ 0x55aa01ULL; }

extern "C" __attribute__((visibility("default"), noinline))
void SSL_free(uint64_t ssl) { (void)ssl; }

extern "C" __attribute__((visibility("default"), noinline))
int32_t SSL_read(uint64_t ssl, uint64_t buf, int32_t num) {
    (void)ssl;
    if (buf && num >= 16) {
        const char *resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
        int32_t len = (int32_t)strlen(resp);
        if (len <= num) {
            memcpy(reinterpret_cast<void *>(buf), resp, (size_t)len);
            return len;
        }
    }
    return 0;
}

extern "C" __attribute__((visibility("default"), noinline))
int32_t SSL_write(uint64_t ssl, uint64_t buf, int32_t num) {
    (void)ssl; (void)buf;
    return num;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tripoder_fixture_GuestProbe_nativeTryKillExternal(JNIEnv *, jclass, jint pid) {
    errno = 0;
    int r = kill((pid_t)pid, 0);
    return r == 0 ? 0 : -errno;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tripoder_fixture_GuestProbe_nativeTryConnectExternal(JNIEnv *, jclass) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (fd < 0) return -errno;
    struct sockaddr_in sin{};
    sin.sin_family = AF_INET;
    sin.sin_port = htons(443);
    sin.sin_addr.s_addr = htonl(0x01010101U); // 1.1.1.1 (non-loopback)
    errno = 0;
    int r = connect(fd, reinterpret_cast<struct sockaddr *>(&sin), sizeof(sin));
    int err = r == 0 ? 0 : -errno;
    close(fd);
    return err;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_tripoder_fixture_GuestProbe_nativeDelay(JNIEnv *, jclass, jint base) {
    return guest_delay_ms(base);
}

static int readBuildId(dl_phdr_info *info, size_t, void *result) {
    if (!strstr(info->dlpi_name, "libguestfixture.so")) return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const auto &ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_NOTE) continue;
        const auto *p = reinterpret_cast<const unsigned char *>(info->dlpi_addr + ph.p_vaddr);
        const auto *end = p + ph.p_memsz;
        while (p + sizeof(Elf64_Nhdr) <= end) {
            const auto *note = reinterpret_cast<const Elf64_Nhdr *>(p);
            p += sizeof(*note);
            size_t names = (static_cast<size_t>(note->n_namesz) + 3) & ~size_t(3);
            size_t descs = (static_cast<size_t>(note->n_descsz) + 3) & ~size_t(3);
            if (names > static_cast<size_t>(end - p) || descs > static_cast<size_t>(end - p) - names) break;
            if (note->n_type == NT_GNU_BUILD_ID && note->n_namesz == 4 && !memcmp(p, "GNU", 4)) {
                const char hex[] = "0123456789abcdef";
                auto &out = *static_cast<std::string *>(result);
                for (size_t j = 0; j < note->n_descsz; j++) {
                    auto b = p[names + j]; out += hex[b >> 4]; out += hex[b & 15];
                }
                return 1;
            }
            p += names + descs;
        }
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_tripoder_fixture_GuestProbe_nativeBuildId(JNIEnv *env, jclass) {
    std::string value;
    dl_iterate_phdr(readBuildId, &value);
    return env->NewStringUTF(value.c_str());
}
