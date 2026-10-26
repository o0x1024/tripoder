# -*- coding: utf-8 -*-
"""把分镜图渲染成带运镜、溶接、字幕与光尘的 1080p 视频。"""
import argparse
import json
import math
import os
import subprocess
import sys
import time

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

import film

W, H = film.OUT_W, film.OUT_H
FPS = film.FPS
SRC = (2560, 1440)
SONGTI = "/System/Library/Fonts/Supplemental/Songti.ttc"
FONT_BOLD = ImageFont.truetype(SONGTI, 46, index=1)
FONT_TITLE = ImageFont.truetype(SONGTI, 26, index=6)


def ease(p):
    p = max(0.0, min(1.0, p))
    return p * p * (3 - 2 * p)


MOTION = {
    "in":      dict(s0=1.075, s1=1.005, span=0.0),
    "in_slow": dict(s0=1.045, s1=1.005, span=0.0),
    "pan_right": dict(s0=1.125, s1=1.115, span=0.098),
    "pan_left":  dict(s0=1.125, s1=1.115, span=0.098),
}


def crop_frame(img, p, motion, drift=0.0):
    """在 2560x1440 母图上做取景窗平移/推近，输出 1920x1080。"""
    m = MOTION[motion]
    e = ease(p)
    s = max(1.0, m["s0"] + (m["s1"] - m["s0"]) * e)
    cw, ch = SRC[0] / s, SRC[1] / s
    slack = (SRC[0] - cw) / SRC[0]          # 可平移的最大比例
    span = min(m["span"], slack * 0.98)     # 防止越界被裁剪
    if motion == "pan_right":
        cx = 0.5 - span / 2 + span * e
    elif motion == "pan_left":
        cx = 0.5 + span / 2 - span * e
    else:
        cx = 0.5
    cy = 0.5 + drift * (e - 0.5)
    x0 = cx * SRC[0] - cw / 2
    y0 = cy * SRC[1] - ch / 2
    x0 = min(max(0.0, x0), SRC[0] - cw)
    y0 = min(max(0.0, y0), SRC[1] - ch)
    a = cw / W
    e2 = ch / H
    return img.transform((W, H), Image.AFFINE, (a, 0, x0, 0, e2, y0), resample=Image.BICUBIC)


def scrim():
    """底部渐隐遮罩，保证字幕可读。"""
    a = np.zeros((H, W), np.uint8)
    y0 = int(H * 0.80)
    for y in range(y0, H):
        t = (y - y0) / (H - y0)
        a[y, :] = int(175 * (t ** 1.7))
    layer = np.zeros((H, W, 4), np.uint8)
    layer[..., 0:3] = (6, 6, 10)
    layer[..., 3] = a
    return Image.fromarray(layer, "RGBA")


SCRIM = scrim()


def make_subtitle(text):
    """只画文字：底部遮罩常驻整部片子，避免每场切换时明暗闪动。"""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    y = int(H * 0.915)
    if len(text) > 26:
        mid = text.rfind("，", 0, len(text) // 2 + 6)
        if mid < 0:
            mid = len(text) // 2
        lines = [text[:mid + 1], text[mid + 1:]]
    else:
        lines = [text]
    n = len(lines)
    for i, line in enumerate(lines):
        yy = y + (i - (n - 1) / 2) * 62
        d.text((W / 2 + 2, yy + 3), line, font=FONT_BOLD, fill=(0, 0, 0, 170), anchor="mm")
        d.text((W / 2, yy), line, font=FONT_BOLD, fill=(250, 245, 233, 255), anchor="mm")
    return img


def dot_sprite(size=28, color=(255, 250, 226)):
    s = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(s)
    cx = cy = size / 2
    for i in range(size // 2, 0, -1):
        a = int(200 * (1 - i / (size / 2)) ** 2.0)
        d.ellipse([cx - i, cy - i, cx + i, cy + i], fill=color + (a,))
    return s


DOT = dot_sprite()


def particle_layer(t, seed, n=26, spread=1.0):
    rng = np.random.default_rng(seed)
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for i in range(n):
        x0 = rng.uniform(0, W)
        y0 = rng.uniform(H * 0.15, H)
        sp = rng.uniform(9, 26)
        ph = rng.uniform(0, 6.28)
        sc = rng.uniform(0.25, 0.8)
        y = (y0 - sp * t) % (H * 1.1) - H * 0.05
        x = x0 + math.sin(t * 0.45 + ph) * 18
        sz = max(6, int(28 * sc))
        dot = DOT.resize((sz, sz), Image.BILINEAR)
        lay.paste(dot, (int(x), int(y)), dot)
    return lay


def load_scene(sc):
    path = f"frames/scene_{sc['index'] + 1:02d}_{sc['kind']}.png"
    img = Image.open(path).convert("RGB")
    if img.size != SRC:
        img = img.resize(SRC, Image.LANCZOS)
    return img


def build(args):
    tl = film.load()
    scenes = tl["scenes"]
    total = tl["total"]
    for sc in scenes:
        sc["img"] = load_scene(sc)
        sc["sub"] = make_subtitle(sc["subtitle"]) if sc["subtitle"] else None

    nframes = int(round(total * FPS))
    print(f"total {total:.2f}s frames {nframes}", flush=True)

    if args.preview:
        os.makedirs("frames/preview", exist_ok=True)
        for frac in [0.02, 0.1, 0.25, 0.4, 0.55, 0.7, 0.85, 0.97]:
            t = total * frac
            frame = render_at(scenes, t, total)
            frame.save(f"frames/preview/{int(t*10):04d}.png")
        print("preview frames saved")
        return

    if args.video_out:
        # 只出画面（分段编码，便于失败重跑）
        cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
               "-an", "-c:v", "libx264", "-preset", args.preset, "-crf", str(args.crf),
               "-pix_fmt", "yuv420p", "-profile:v", "high", "-g", "60",
               args.video_out]
        first, last = args.start, args.end
    else:
        cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
               "-i", "audio/final.wav",
               "-t", f"{total:.3f}",
               "-af", f"afade=t=in:st=0:d=1.2,afade=t=out:st={max(0.0, total-2.2):.3f}:d=2.2",
               "-c:v", "libx264", "-preset", args.preset, "-crf", str(args.crf),
               "-pix_fmt", "yuv420p", "-profile:v", "high", "-movflags", "+faststart",
               "-c:a", "aac", "-b:a", "192k", "-ar", "44100",
               args.out]
        first, last = 0, nframes

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    t0 = time.time()
    for i in range(first, last):
        t = i / FPS
        frame = render_at(scenes, t, total)
        proc.stdin.write(frame.tobytes())
        if (i - first) % 90 == 0:
            el = time.time() - t0
            print(f"  {i - first}/{last - first}  {(i - first)/max(1,el):.1f} fps  el {el:.0f}s",
                  flush=True)
    proc.stdin.close()
    rc = proc.wait()
    print("ffmpeg rc", rc, "elapsed", round(time.time() - t0, 1))
    if rc != 0:
        sys.exit(rc)


def render_at(scenes, t, total):
    act = [s for s in scenes if s["vstart"] - 1e-6 <= t < s["vend"] - 1e-6]
    if not act:
        act = [scenes[-1]]
    frames = []
    for sc in act:
        p = (t - sc["vstart"]) / max(1e-6, sc["duration"])
        frames.append(crop_frame(sc["img"], p, sc["motion"]))

    if len(frames) == 1:
        frame = frames[0]
    else:
        nxt = act[1]
        k = ease((t - nxt["vstart"]) / max(1e-6, film.XFADE))
        frame = Image.blend(frames[0], frames[1], k)

    # 常驻底部遮罩（不随镜头切换闪动）
    frame.paste(SCRIM, (0, 0), SCRIM)

    # 字幕：溶接期间也在画面混合之后贴，避免两张字幕叠字
    best, best_op = None, 0.0
    for sc in act:
        if sc["sub"] is None:
            continue
        t_in = sc["vstart"] + 0.45
        t_out = sc["vend"] - 0.90          # 与下一场字幕的淡入无缝衔接
        op = min((t - t_in) / 0.45, (t_out - t) / 0.45 + 1.0, 1.0)
        if op > best_op:
            best, best_op = sc["sub"], op
    if best is not None and best_op > 0.004:
        if best_op >= 0.999:
            frame.paste(best, (0, 0), best)
        else:
            sub = best.copy()
            sub.putalpha(sub.getchannel("A").point(lambda v: int(v * best_op)))
            frame.paste(sub, (0, 0), sub)

    # 光尘
    sc0 = act[0]
    lay = particle_layer(t, sc0["index"] * 13 + 7, 22)
    frame.paste(lay, (0, 0), lay)

    # 首尾黑场
    fade = 1.0
    if t < 1.2:
        fade = t / 1.2
    if t > total - 1.6:
        fade = max(0.0, (total - t) / 1.6)
    if fade < 1.0:
        black = Image.new("RGB", (W, H), (0, 0, 0))
        frame = Image.blend(black, frame, max(0.0, min(1.0, fade)))
    return frame


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out/snow_white.mp4")
    ap.add_argument("--crf", type=int, default=19)
    ap.add_argument("--preset", default="medium")
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--start", type=int, default=0, help="起始帧（分段渲染用）")
    ap.add_argument("--end", type=int, default=0, help="结束帧（不含）")
    ap.add_argument("--video-out", default=None, help="只出画面到该文件，不带音轨")
    a = ap.parse_args()
    os.makedirs("out", exist_ok=True)
    if a.end == 0:
        a.end = int(round(film.load()["total"] * FPS))
    build(a)
