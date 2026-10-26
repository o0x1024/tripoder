# -*- coding: utf-8 -*-
"""成片校验：容器/流信息、帧数、音画时长、抽样帧亮度与字幕可读性。"""
import json
import os
import subprocess
import sys

import numpy as np
from PIL import Image

MP4 = "out/snow_white.mp4"


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json",
                          "-show_format", "-show_streams", path],
                         capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def main():
    info = probe(MP4)
    fmt = info["format"]
    v = next(s for s in info["streams"] if s["codec_type"] == "video")
    a = next(s for s in info["streams"] if s["codec_type"] == "audio")
    print(f"file      {MP4}  {os.path.getsize(MP4)/1e6:.1f} MB")
    print(f"duration  {float(fmt['duration']):.2f}s   bitrate {int(fmt['bit_rate'])/1e6:.2f} Mb/s")
    print(f"video     {v['codec_name']} {v['profile']} {v['width']}x{v['height']} "
          f"{v['r_frame_rate']} {v['pix_fmt']}")
    print(f"audio     {a['codec_name']} {a['sample_rate']}Hz ch={a['channels']} "
          f"{a.get('bit_rate','?')} dur={a.get('duration','?')}")

    n = int(subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                            "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", MP4],
                           capture_output=True, text=True).stdout.strip())
    print(f"frames    {n}")

    # 抽样解码：检查黑场/花屏与字幕可读性
    os.makedirs("frames/check", exist_ok=True)
    times = [1.0, 8.0, 15.0, 30.0, 50.0, 70.0, 95.0, 118.0, 135.0, 150.0, 168.0, 182.0]
    prev = None
    for t in times:
        p = f"frames/check/t{int(t):03d}.png"
        subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                        "-ss", f"{t:.2f}", "-i", MP4, "-frames:v", "1", p], check=True)
        g = np.asarray(Image.open(p).convert("L"))
        band = g[980:1060, 300:1620]
        txt = int((band > 210).sum())
        # 与上一抽样帧的差异（避免整片重复同一画面）
        diff = 0.0 if prev is None else float(np.abs(g.astype(np.int16) - prev).mean())
        prev = g.astype(np.int16)
        print(f"  t={t:6.2f}s  mean {g.mean():5.1f}  contrast {g.max()-g.min():3d}  "
              f"subtitle_px {txt:5d}  diff {diff:5.1f}")

    # 音频抽样电平
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", MP4, "-ac", "1", "-ar", "8000",
                          "-f", "s16le", "-"], capture_output=True, check=True).stdout
    pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768
    print(f"audio     peak {np.abs(pcm).max():.3f}  rms {20*np.log10(np.sqrt((pcm**2).mean())+1e-9):.1f} dB  "
          f"samples {len(pcm)}  silence_ratio {float((np.abs(pcm)<0.002).mean()):.3f}")


if __name__ == "__main__":
    main()
