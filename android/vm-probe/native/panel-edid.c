/* A fixed virtual panel, not a desktop monitor with inferred timing modes. */
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

size_t tripoder_panel_edid(uint8_t *out, size_t capacity,
                          uint32_t width, uint32_t height)
{
    if (!out || capacity < 128 || width != 1024 || height != 768) return 0;
    static const uint8_t header[] = {0, 255, 255, 255, 255, 255, 255, 0};
    memset(out, 0, 128);
    memcpy(out, header, sizeof(header));
    out[8] = 0x52; out[9] = 0x50; /* Manufacturer TRP. */
    out[10] = 1; out[12] = 1;
    out[16] = 1; out[17] = 36; /* Week 1, 2026. */
    out[18] = 1; out[19] = 4;
    out[20] = 0xa5; /* Digital, 8 bits per colour, DisplayPort. */
    out[21] = 26; out[22] = 19; out[23] = 120;
    out[24] = 0x06; /* sRGB and preferred timing; no continuous timings. */
    const uint8_t srgb[] = {0xee, 0x91, 0xa3, 0x54, 0x4c, 0x99,
                            0x26, 0x0f, 0x50, 0x54};
    memcpy(out + 25, srgb, sizeof(srgb));
    /* No established/standard timings and no extension blocks. */
    for (int i = 38; i < 54; ++i) out[i] = 1;
    /* 61.44 MHz / (1280 * 800) = exactly 60 Hz.
     * H: active 1024, front 24, sync 136, back 96.
     * V: active 768, front 3, sync 6, back 23. */
    const uint8_t timing[] = {
        0x00, 0x18, 0x00, 0x00, 0x41, 0x00, 0x20, 0x30,
        0x18, 0x88, 0x36, 0x00, 0x00, 0xc0, 0x10, 0x00, 0x00, 0x18
    };
    memcpy(out + 54, timing, sizeof(timing));
    out[75] = 0xfc;
    memcpy(out + 77, "Tripoder LCD\n ", 13);
    out[93] = 0xff;
    memcpy(out + 95, "000000000001\n", 13);
    out[111] = 0x10; /* Unused descriptor, not a range admitting extra modes. */
    unsigned sum = 0;
    for (int i = 0; i < 127; ++i) sum += out[i];
    out[127] = (uint8_t)(-sum);
    return 128;
}

void tripoder_panel_edid_selftest(void)
{
    uint8_t edid[256];
    memset(edid, 0xa5, sizeof(edid));
    if (tripoder_panel_edid(edid, sizeof(edid), 1024, 768) != 128) abort();
    unsigned sum = 0;
    for (int i = 0; i < 128; ++i) sum += edid[i];
    for (int i = 128; i < 256; ++i) if (edid[i] != 0xa5) abort();
    if ((sum & 255) || edid[126] || edid[35] || edid[36] || edid[37]) abort();
    uint8_t before[256];
    memcpy(before, edid, sizeof(edid));
    if (tripoder_panel_edid(edid, sizeof(edid), 1920, 1080) != 0 ||
        tripoder_panel_edid(edid, 127, 1024, 768) != 0 ||
        memcmp(before, edid, sizeof(edid))) abort();
    fprintf(stderr, "TRIPODER_PANEL_EDID_SELFTEST_PASS width=1024 height=768 "
            "pixel_clock_hz=61440000 htotal=1280 vtotal=800 refresh_hz=60 modes=1\n");
}
