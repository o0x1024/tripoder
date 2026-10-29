#ifndef TRIPODER_LIBC_FIXED_CONTRACT_H
#define TRIPODER_LIBC_FIXED_CONTRACT_H
#include <stdint.h>

/* Measured OEM libc, SHA256 b963b539545b64aa8fbcaed3228d04bcb6466bfd47055aa16e4ccdcadfdc461e.
 * Java's platform gate verifies the entire file before entering this native API.
 * Each offset and complete six-word body is independently tied to that ELF. */
#define TP_FIXED_LIBC_PATH "/apex/com.android.runtime/lib64/bionic/libc.so"
#ifndef TP_FIXED_LIBC_ENTRIES
#define TP_FIXED_LIBC_ENTRIES 11
#endif
struct tp_libc_entry_contract { const char *name; uintptr_t offset; uint32_t words[6]; };
static const struct tp_libc_entry_contract tp_libc_contract[TP_FIXED_LIBC_ENTRIES] = {
    {"__openat",   0xe0a40, {0xd503245f,0xd2800708,0xd4000001,0xb13ffc1f,0x54cd9322,0xd65f03c0}},
    {"fstatat",    0xe0b40, {0xd503245f,0xd28009e8,0xd4000001,0xb13ffc1f,0x54cd8b22,0xd65f03c0}},
    {"unlinkat",   0xe0d00, {0xd503245f,0xd2800468,0xd4000001,0xb13ffc1f,0x54cd7d22,0xd65f03c0}},
    {"mkdirat",    0xe0bc0, {0xd503245f,0xd2800448,0xd4000001,0xb13ffc1f,0x54cd8722,0xd65f03c0}},
    {"renameat2",  0xe0c80, {0xd503245f,0xd2802288,0xd4000001,0xb13ffc1f,0x54cd8122,0xd65f03c0}},
    {"__faccessat",0xe0a80, {0xd503245f,0xd2800608,0xd4000001,0xb13ffc1f,0x54cd9122,0xd65f03c0}},
    {"readlinkat", 0xe0c40, {0xd503245f,0xd28009c8,0xd4000001,0xb13ffc1f,0x54cd8322,0xd65f03c0}},
    {"chdir",      0xe1040, {0xd503245f,0xd2800628,0xd4000001,0xb13ffc1f,0x54cd6322,0xd65f03c0}},
    {"symlinkat",  0xe0cc0, {0xd503245f,0xd2800488,0xd4000001,0xb13ffc1f,0x54cd7f22,0xd65f03c0}},
    {"linkat",     0xe0b80, {0xd503245f,0xd28004a8,0xd4000001,0xb13ffc1f,0x54cd8922,0xd65f03c0}},
    {"__fchmodat", 0xe0ac0, {0xd503245f,0xd28006a8,0xd4000001,0xb13ffc1f,0x54cd8f22,0xd65f03c0}},
};
static const unsigned char tp_libc_build_id[16] = {
    0x48,0xc4,0xb1,0x83,0x04,0xac,0xf5,0x19,0xb2,0x64,0x57,0xcc,0xbc,0x3d,0x43,0x5f
};
#endif
