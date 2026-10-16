#include "park_diagnostic.h"
#include "park_syscall.h"
#include <fcntl.h>
#include <stddef.h>

/* Timeout evidence, read before unpark. Never used as permission to skip a thread. */
bool tp_park_blocked_mask(int directory, int tid, uint64_t *mask) {
    char path[32], digits[12];
    unsigned count = 0, length = 0;
    if (tid <= 0) return false;
    do { digits[count++] = (char)('0' + tid % 10); tid /= 10; } while (tid != 0);
    while (count != 0) path[length++] = digits[--count];
    const char suffix[] = "/status";
    for (unsigned i = 0; i < sizeof(suffix); i++) path[length++] = suffix[i];
    long fd = tp_park_syscall(SYS_openat, directory, (long)path, O_RDONLY | O_CLOEXEC, 0, 0, 0);
    if (fd < 0) return false;
    char bytes[4096];
    long size = tp_park_syscall(SYS_read, fd, (long)bytes, sizeof(bytes), 0, 0, 0);
    (void)tp_park_syscall(SYS_close, fd, 0, 0, 0, 0, 0);
    if (size <= 0) return false;
    const char key[] = "SigBlk:\t";
    for (long i = 0; i + (long)sizeof(key) - 1 + 16 < size; i++) {
        if (i != 0 && bytes[i - 1] != '\n') continue;
        bool match = true;
        for (unsigned j = 0; j < sizeof(key) - 1; j++) if (bytes[i + j] != key[j]) match = false;
        if (!match) continue;
        uint64_t value = 0;
        for (unsigned j = 0; j < 16; j++) {
            char c = bytes[i + sizeof(key) - 1 + j];
            unsigned digit = c >= '0' && c <= '9' ? (unsigned)(c - '0') :
                c >= 'a' && c <= 'f' ? (unsigned)(c - 'a' + 10) : 16;
            if (digit == 16) return false;
            value = (value << 4) | digit;
        }
        if (bytes[i + sizeof(key) - 1 + 16] != '\n') return false;
        *mask = value;
        return true;
    }
    return false;
}
