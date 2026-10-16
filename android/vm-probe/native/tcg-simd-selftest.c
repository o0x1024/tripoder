/* Execute the real TCG CMLE emitter across negative, zero and positive lanes. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

extern void tripoder_tcg_emit_cmle_test(uint32_t *code, unsigned size, unsigned q);

void tripoder_tcg_simd_selftest(void) {
    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    uint32_t *code = mmap(NULL, page, PROT_READ | PROT_WRITE | PROT_EXEC,
                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (code == MAP_FAILED) abort();
    code[0] = 0xd503245f; /* BTI c */
    code[1] = 0x3dc00000; /* LDR Q0, [X0] */
    code[3] = 0x3d800020; /* STR Q0, [X1] */
    code[4] = 0xd65f03c0; /* RET */
    void (*compare)(const void *, void *) = (void (*)(const void *, void *))code;
    unsigned elements = 0;
    for (unsigned variant = 0; variant < 8; ++variant) {
        unsigned size = variant % 4;
        unsigned q = variant / 4;
        unsigned vector_bytes = q ? 16 : 8;
        unsigned bytes = 1U << size;
        unsigned bits = bytes * 8;
        uint64_t sign = UINT64_C(1) << (bits - 1);
        uint64_t mask = size == 3 ? UINT64_MAX : (UINT64_C(1) << bits) - 1;
        uint64_t values[] = {mask, 0, 1, sign, sign - 1, mask - 1, 2};
        tripoder_tcg_emit_cmle_test(&code[2], size, q);
        __builtin___clear_cache((char *)code, (char *)&code[5]);
        for (unsigned test = 0; test < 7; ++test) {
            uint8_t input[16] = {0}, actual[16], expected[16] = {0};
            for (unsigned lane = 0; lane < vector_bytes / bytes; ++lane) {
                uint64_t value = values[(test + lane) % 7];
                uint64_t result = value == 0 || (value & sign) ? mask : 0;
                memcpy(input + lane * bytes, &value, bytes);
                memcpy(expected + lane * bytes, &result, bytes);
                ++elements;
            }
            memset(actual, 0xa5, sizeof(actual));
            compare(input, actual);
            if (memcmp(actual, expected, sizeof(actual)) != 0) {
                fprintf(stderr, "TRIPODER_TCG_SIMD_SELFTEST_FAILED q=%u size=%u test=%u\n", q, size, test);
                abort();
            }
        }
    }
    if (munmap(code, page) != 0) abort();
    fprintf(stderr, "TRIPODER_TCG_SIMD_SELFTEST_PASS forms=8 vectors=56 elements=%u scalar64=1\n", elements);
}
