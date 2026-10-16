#!/usr/bin/env python3
"""Decode the production panel's EDID independently of its device selftest."""
import ctypes
import hashlib
import json
import subprocess
import tempfile
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix="tripoder-edid-") as directory:
        library = Path(directory) / "panel.dylib"
        subprocess.run(["clang", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror",
                        str(ROOT / "native/panel-edid.c"), "-o", str(library)], check=True)
        native = ctypes.CDLL(str(library))
        generate = native.tripoder_panel_edid
        generate.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32]
        generate.restype = ctypes.c_size_t
        native.tripoder_panel_edid_selftest()
        buffer = (ctypes.c_uint8 * 256)(*[0xa5] * 256)
        assert generate(buffer, 256, 1024, 768) == 128
        assert bytes(buffer[128:]) == bytes([0xa5] * 128)
        data = bytes(buffer[:128])
        assert data[:8] == bytes.fromhex("00ffffffffffff00")
        assert data[18:20] == bytes([1, 4]) and sum(data) % 256 == 0
        assert data[35:38] == bytes(3) and data[38:54] == bytes([1] * 16)
        assert data[126] == 0 and not (data[24] & 1)
        dtd = data[54:72]
        pixel_clock = int.from_bytes(dtd[:2], "little") * 10000
        hactive = dtd[2] | (dtd[4] >> 4) << 8
        hblank = dtd[3] | (dtd[4] & 15) << 8
        vactive = dtd[5] | (dtd[7] >> 4) << 8
        vblank = dtd[6] | (dtd[7] & 15) << 8
        hfront = dtd[8] | (dtd[11] >> 6) << 8
        hsync = dtd[9] | ((dtd[11] >> 4) & 3) << 8
        vfront = (dtd[10] >> 4) | ((dtd[11] >> 2) & 3) << 4
        vsync = (dtd[10] & 15) | (dtd[11] & 3) << 4
        assert (hactive, vactive) == (1024, 768)
        assert hblank > hfront + hsync > 0 and vblank > vfront + vsync > 0
        assert dtd[17] == 0x18
        for start, kind in [(72, 0xfc), (90, 0xff), (108, 0x10)]:
            assert data[start:start + 3] == bytes(3) and data[start + 3] == kind
        refresh = Fraction(pixel_clock, (hactive + hblank) * (vactive + vblank))
        assert refresh == 60
        period_ns = round(1_000_000_000 / refresh)
        assert abs(1_000_000_000 / period_ns - 60) < 0.001
        original = bytes(buffer)
        for width, height, capacity in [(0, 0, 256), (1920, 1080, 256), (1024, 768, 127)]:
            assert generate(buffer, capacity, width, height) == 0 and bytes(buffer) == original
        print(json.dumps({"status": "PASS", "sha256": hashlib.sha256(data).hexdigest(),
                          "modes": 1, "width": hactive, "height": vactive,
                          "pixelClockHz": pixel_clock, "horizontalTotal": hactive + hblank,
                          "verticalTotal": vactive + vblank, "refreshHz": float(refresh),
                          "integerVsyncPeriodNs": period_ns}, indent=2))


if __name__ == "__main__":
    main()
