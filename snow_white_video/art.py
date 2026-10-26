# -*- coding: utf-8 -*-
"""绘本风插画引擎：用 PIL 程序化绘制《白雪公主与七个小矮人》分镜。"""
import math
import random

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

W, H = 2560, 1440
SS = 2  # 超采样倍数
SONGTI = "/System/Library/Fonts/Supplemental/Songti.ttc"
FONT_IDX = {"black": 0, "bold": 1, "light": 3, "regular": 6}


# ---------------------------------------------------------------- 基础工具
def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def shade(c, f):
    if f <= 1:
        return tuple(int(round(c[i] * f)) for i in range(3))
    return mix(c, (255, 255, 255), min(1.0, f - 1))


def font(size, weight="regular"):
    return ImageFont.truetype(SONGTI, int(size * SS), index=FONT_IDX[weight])


class CV:
    """设计坐标 2560x1440，内部按 SS 倍超采样。"""

    def __init__(self):
        self.img = Image.new("RGB", (W * SS, H * SS), (0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    # -- 基本形状（设计坐标输入）
    def polygon(self, pts, fill=None, outline=None, width=0):
        self.d.polygon([(x * SS, y * SS) for x, y in pts], fill=fill, outline=outline,
                       width=int(width * SS))

    def ellipse(self, cx, cy, rx, ry=None, fill=None, outline=None, width=0):
        ry = rx if ry is None else ry
        self.d.ellipse([(cx - rx) * SS, (cy - ry) * SS, (cx + rx) * SS, (cy + ry) * SS],
                       fill=fill, outline=outline, width=int(width * SS))

    def circle(self, cx, cy, r, fill=None, outline=None, width=0):
        self.ellipse(cx, cy, r, r, fill, outline, width)

    def rrect(self, x0, y0, x1, y1, r, fill=None, outline=None, width=0):
        self.d.rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], radius=max(0, r * SS),
                                 fill=fill, outline=outline, width=int(width * SS))

    def line(self, pts, fill, width, joint="curve"):
        self.d.line([(x * SS, y * SS) for x, y in pts], fill=fill,
                    width=max(1, int(width * SS)), joint=joint)

    def capsule(self, p0, p1, r, fill):
        self.line([p0, p1], fill, width=2 * r)
        self.circle(p0[0], p0[1], r, fill)
        self.circle(p1[0], p1[1], r, fill)

    def arc(self, cx, cy, rx, ry, a0, a1, fill, width):
        self.d.arc([(cx - rx) * SS, (cy - ry) * SS, (cx + rx) * SS, (cy + ry) * SS],
                   a0, a1, fill=fill, width=max(1, int(width * SS)))

    def pie(self, cx, cy, rx, ry, a0, a1, fill):
        self.d.pieslice([(cx - rx) * SS, (cy - ry) * SS, (cx + rx) * SS, (cy + ry) * SS],
                        a0, a1, fill=fill)

    def pieslice(self, cx, cy, rx, ry, a0, a1, fill):
        self.pie(cx, cy, rx, ry, a0, a1, fill)

    def text(self, x, y, s, size, fill, weight="regular", anchor="mm", tracking=0,
             stroke=0, stroke_fill=None, spacing=0):
        f = font(size, weight)
        if tracking == 0:
            self.d.text((x * SS, y * SS), s, font=f, fill=fill, anchor=anchor,
                        stroke_width=int(stroke * SS), stroke_fill=stroke_fill,
                        spacing=spacing * SS)
            return
        # 字距控制：逐字绘制
        widths = [self.d.textlength(ch, font=f) for ch in s]
        total = sum(widths) + tracking * SS * (len(s) - 1)
        cx = x * SS - (total / 2 if anchor[0] == "m" else 0)
        if anchor[0] == "r":
            cx = x * SS - total
        for ch, w in zip(s, widths):
            self.d.text((cx, y * SS), ch, font=f, fill=fill, anchor="l" + anchor[1])
            cx += w + tracking * SS

    # -- 图层
    def paste(self, layer, blur=0.0, opacity=1.0):
        if blur:
            layer = layer.filter(ImageFilter.GaussianBlur(blur * SS))
        if opacity < 1.0:
            a = layer.getchannel("A").point(lambda v: int(v * opacity))
            layer = layer.copy()
            layer.putalpha(a)
        self.img.paste(layer, (0, 0), layer)

    def glow(self, cx, cy, radius, color, strength=1.0, shrink=6):
        """加性光晕：低分辨率计算 -> 柔化 -> 放大回超采样画布叠加。"""
        w, h = max(8, (W * SS) // (shrink * SS)), max(8, (H * SS) // (shrink * SS))
        yy, xx = np.mgrid[0:h, 0:w]
        d = np.sqrt((xx - cx * SS / (shrink * SS)) ** 2 + (yy - cy * SS / (shrink * SS)) ** 2)
        d = d / max(1e-6, radius * SS / (shrink * SS))
        fall = np.clip(1.0 - d, 0, 1) ** 2.2
        rgb = np.zeros((h, w, 3), np.uint8)
        for i in range(3):
            rgb[..., i] = np.clip(fall * color[i] * strength, 0, 255).astype(np.uint8)
        layer = Image.fromarray(rgb).filter(ImageFilter.GaussianBlur(shrink * 0.6))
        layer = layer.resize((W * SS, H * SS), Image.BILINEAR)
        self.img = ImageChops.screen(self.img, layer)
        self.d = ImageDraw.Draw(self.img)

    def add(self, cx, cy, radius, color, strength=1.0, shrink=6):
        """线性叠加的亮光（用于烛光、宝石反光）。"""
        w, h = max(8, W // shrink), max(8, H // shrink)
        yy, xx = np.mgrid[0:h, 0:w]
        d = np.sqrt((xx - cx / shrink) ** 2 + (yy - cy / shrink) ** 2) / max(1e-6, radius / shrink)
        fall = np.clip(1.0 - d, 0, 1) ** 2.0
        rgb = np.zeros((h, w, 3), np.uint8)
        for i in range(3):
            rgb[..., i] = np.clip(fall * color[i] * strength, 0, 255).astype(np.uint8)
        layer = Image.fromarray(rgb).filter(ImageFilter.GaussianBlur(shrink * 0.5))
        layer = layer.resize((W * SS, H * SS), Image.BILINEAR)
        self.img = ImageChops.add(self.img, layer)
        self.d = ImageDraw.Draw(self.img)

    def texture(self, rng, amount=5.0):
        arr = np.asarray(self.img).astype(np.int16)
        noise = rng.normal(0, amount, (H // 2, W // 2)).astype(np.float32)
        n = np.asarray(Image.fromarray(noise).resize((W, H), Image.BILINEAR))
        arr = np.clip(arr + n[..., None], 0, 255).astype(np.uint8)
        self.img = Image.fromarray(arr)
        self.d = ImageDraw.Draw(self.img)

    def finish(self, vignette=0.42, grain=4.0, seed=7):
        """统一调色：降采样 -> 暗角 -> 暖调 -> 纸感噪点。"""
        out = self.img.resize((W, H), Image.LANCZOS)
        arr = np.asarray(out).astype(np.float32)
        yy, xx = np.mgrid[0:H, 0:W]
        nx = (xx - W / 2) / (W / 2)
        ny = (yy - H / 2) / (H / 2)
        r = np.sqrt(nx ** 2 + ny ** 2) / 1.414
        mask = np.clip(1.0 - vignette * (r ** 1.7) * 1.5, 0.35, 1.0)[..., None]
        arr = arr * mask
        arr[..., 0] *= 1.035
        arr[..., 2] *= 0.985
        rng = np.random.default_rng(seed)
        small = rng.normal(0, grain, (H // 3, W // 3)).astype(np.float32)
        n = np.asarray(Image.fromarray(small).resize((W, H), Image.BILINEAR))[..., None]
        arr = np.clip(arr + n * 0.85, 0, 255).astype(np.uint8)
        self.img = Image.fromarray(arr)


def layer():
    return Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))


def pen_on(img):
    """在任意图层上画图（设计坐标）。返回 (draw, 映射函数)。"""
    d = ImageDraw.Draw(img)
    return d


# ---------------------------------------------------------------- 天空与大气
def sky(cv, top, bottom, mid=None, horizon=0.62):
    mid = mid or mix(top, bottom, 0.55)
    ys = np.linspace(0, 1, H)
    cols = np.zeros((H, 3), np.float32)
    for i, t in enumerate(ys):
        if t < horizon:
            u = t / max(1e-6, horizon)
            c = np.array(mix(top, mid, u ** 0.9), np.float32)
        else:
            u = (t - horizon) / max(1e-6, 1 - horizon)
            c = np.array(mix(mid, bottom, u ** 1.25), np.float32)
        cols[i] = c
    arr = np.repeat(cols[:, None, :], W, axis=1).astype(np.uint8)
    sky_img = Image.fromarray(arr).resize((W * SS, H * SS), Image.BILINEAR)
    cv.img = sky_img
    cv.d = ImageDraw.Draw(cv.img)


def stars(cv, rng, n=140, ymax=0.6, color=(255, 250, 235)):
    for _ in range(n):
        x = rng.uniform(0, W)
        y = rng.uniform(0, H * ymax)
        r = rng.choice([0.8, 1.0, 1.2, 1.6, 2.2])
        a = rng.uniform(0.25, 0.95)
        lay = layer()
        d = ImageDraw.Draw(lay)
        d.ellipse([(x - r) * SS, (y - r) * SS, (x + r) * SS, (y + r) * SS],
                  fill=color + (int(255 * a),))
        if r > 1.9:
            d.ellipse([(x - r * 3) * SS, (y - r * 3) * SS, (x + r * 3) * SS, (y + r * 3) * SS],
                      fill=color + (int(90 * a),))
        cv.paste(lay, blur=1.2 if r > 1.9 else 0)


def moon(cv, x, y, r, color=(246, 241, 214), glow=1.0):
    cv.glow(x, y, r * 6.5, mix(color, (255, 255, 255), 0.2), 0.55 * glow, shrink=6)
    cv.circle(x, y, r, color)
    cv.circle(x - r * 0.28, y - r * 0.2, r * 0.14, shade(color, 0.93))
    cv.circle(x + r * 0.3, y + r * 0.25, r * 0.1, shade(color, 0.93))
    cv.circle(x + r * 0.05, y + r * 0.45, r * 0.07, shade(color, 0.93))


def sun(cv, x, y, r, color=(255, 226, 168), glow=1.0):
    cv.glow(x, y, r * 7.0, color, 0.85 * glow, shrink=6)
    cv.circle(x, y, r, mix(color, (255, 255, 255), 0.55))
    cv.circle(x, y, r * 0.72, mix(color, (255, 255, 255), 0.75))


def clouds(cv, rng, y, n=5, color=(255, 255, 255), opacity=0.16, scale=1.0, spread=(0, 1)):
    lay = layer()
    d = ImageDraw.Draw(lay)
    for _ in range(n):
        cx = rng.uniform(spread[0] * W, spread[1] * W)
        cy = y + rng.uniform(-40, 40)
        w = rng.uniform(240, 520) * scale
        h = w * rng.uniform(0.16, 0.26)
        for k in range(6):
            ox = rng.uniform(-0.55, 0.55) * w
            oy = rng.uniform(-0.35, 0.25) * h
            rw = rng.uniform(0.3, 0.62) * w
            rh = h * rng.uniform(0.7, 1.5)
            d.ellipse([(cx + ox - rw) * SS, (cy + oy - rh) * SS,
                       (cx + ox + rw) * SS, (cy + oy + rh) * SS],
                      fill=color + (int(255 * opacity),))
    cv.paste(lay, blur=18)


def ridge(cv, rng, base_y, height, color, seed=0, roughness=0.55, snow=None,
          snow_h=0.35, x0=-200, x1=W + 200, steps=26):
    """折线山脊。"""
    r = random.Random(seed)
    pts = []
    n = steps
    for i in range(n + 1):
        t = i / n
        x = x0 + (x1 - x0) * t
        # 几个正弦叠加，形成自然的起伏
        y = base_y - height * (
            0.55 * math.sin(t * math.pi * 1.7 + r.uniform(-0.1, 0.1) + seed)
            + 0.35 * math.sin(t * math.pi * 4.3 + seed * 2.1)
            + 0.22 * math.sin(t * math.pi * 8.1 + seed * 3.7))
        y -= height * r.uniform(-roughness * 0.12, roughness * 0.12)
        pts.append((x, y))
    poly = [(-400, base_y + 400)] + pts + [(W + 400, base_y + 400)]
    cv.polygon(poly, fill=color)
    if snow:
        # 山顶积雪：按高度裁剪一块亮色
        lay = layer()
        d = ImageDraw.Draw(lay)
        d.polygon([(x * SS, y * SS) for x, y in poly], fill=snow + (255,))
        mask = Image.new("L", (W * SS, H * SS), 0)
        md = ImageDraw.Draw(mask)
        thresh = base_y - height * (0.55 + snow_h)
        md.rectangle([0, 0, W * SS, int(thresh * SS)], fill=255)
        mask = mask.filter(ImageFilter.GaussianBlur(2 * SS))
        lay.putalpha(ImageChops.multiply(lay.getchannel("A"), mask))
        cv.paste(lay)


def mist(cv, rng, y, h=180, color=(255, 255, 255), opacity=0.2, n=16):
    lay = layer()
    d = ImageDraw.Draw(lay)
    for _ in range(n):
        cx = rng.uniform(-0.1, 1.1) * W
        cy = y + rng.uniform(-h * 0.5, h * 0.5)
        rw = rng.uniform(300, 760)
        rh = rng.uniform(28, 70)
        d.ellipse([(cx - rw) * SS, (cy - rh) * SS, (cx + rw) * SS, (cy + rh) * SS],
                  fill=color + (int(255 * opacity),))
    cv.paste(lay, blur=26)


def pine(cv, x, base_y, h, color, snow=None, trunk=None, lean=0.0):
    tiers = 5
    w = h * 0.46
    if trunk:
        cv.rrect(x - h * 0.035, base_y - h * 0.18, x + h * 0.035, base_y + h * 0.02, h * 0.02, trunk)
    for i in range(tiers):
        t = i / tiers
        top = base_y - h + t * h * 0.72
        hw = w * (0.34 + 0.66 * t)
        bx = x + lean * h * (1 - t)
        cv.polygon([(bx - hw, base_y - h * 0.14 - (1 - t) * h * 0.0 + t * h * 0.0),
                    (bx + hw, base_y - h * 0.14),
                    (bx + lean * h * 0.1, top)], fill=color)
        if snow:
            cv.polygon([(bx - hw * 0.55, base_y - h * 0.14 - (h * 0.1) * (1 - t) * 0.0),
                        (bx + hw * 0.55, base_y - h * 0.14),
                        (bx, top + h * 0.06)], fill=snow)


def forest_band(cv, rng, base_y, h, color, density=22, snow=None, jitter=0.35):
    step = (W + 400) / density
    for i in range(density + 1):
        x = -200 + step * i + rng.uniform(-step * 0.3, step * 0.3)
        hh = h * rng.uniform(1 - jitter, 1 + jitter)
        pine(cv, x, base_y + rng.uniform(-6, 10), hh, color, snow=snow)


def hill(cv, rng, base_y, amp, color, seed=1, phase=0.0):
    pts = []
    n = 40
    for i in range(n + 1):
        t = i / n
        x = -200 + (W + 400) * t
        y = base_y - amp * (0.6 * math.sin(t * math.pi * 2.2 + phase) + 0.4 * math.sin(t * math.pi * 5 + phase * 2 + seed))
        pts.append((x, y))
    cv.polygon([(-200, H + 200)] + pts + [(W + 200, H + 200)], fill=color)


def ground(cv, y, color, rng=None, tufts=None, density=60):
    cv.polygon([(-200, y - 10), (W + 200, y - 10), (W + 200, H + 100), (-200, H + 100)], fill=color)
    if rng and tufts:
        for _ in range(density):
            x = rng.uniform(0, W)
            yy = rng.uniform(y + 20, H)
            s = rng.uniform(6, 16) * (0.6 + 0.8 * (yy - y) / max(1, H - y))
            cv.line([(x, yy), (x - s * 0.4, yy - s)], tufts, 2.2)
            cv.line([(x, yy), (x + s * 0.45, yy - s * 0.8)], tufts, 2.2)


def vignette_corners(cv, color, opacity=0.3):
    lay = layer()
    d = ImageDraw.Draw(lay)
    for cx, cy in [(-200, -200), (W + 200, -200), (-200, H + 300), (W + 200, H + 300)]:
        d.ellipse([(cx - 900) * SS, (cy - 700) * SS, (cx + 900) * SS, (cy + 700) * SS],
                  fill=color + (int(255 * opacity),))
    cv.paste(lay, blur=70)


# ---------------------------------------------------------------- 建筑
def castle(cv, x, base_y, s, stone, roof, lit=(255, 208, 130), flag=(196, 62, 68), lit_rate=1.0):
    rng = random.Random(int(x))
    body_w = 420 * s
    # 主体
    cv.polygon([(x - body_w / 2, base_y), (x - body_w / 2, base_y - 260 * s),
                (x + body_w / 2, base_y - 260 * s), (x + body_w / 2, base_y)], fill=stone)
    # 垛口
    for i in range(7):
        bx = x - body_w / 2 + i * body_w / 7
        cv.polygon([(bx, base_y - 260 * s), (bx, base_y - 300 * s),
                    (bx + body_w / 14, base_y - 300 * s), (bx + body_w / 14, base_y - 260 * s)],
                   fill=shade(stone, 1.06))
    # 塔
    for dx, th, tw in [(-body_w * 0.62, 470, 130), (body_w * 0.62, 420, 120), (0, 560, 150)]:
        tx = x + dx * 1.0
        w = tw * s
        cv.polygon([(tx - w / 2, base_y), (tx - w / 2, base_y - th * s),
                    (tx + w / 2, base_y - th * s), (tx + w / 2, base_y)], fill=shade(stone, 0.96))
        cv.polygon([(tx - w * 0.68, base_y - th * s), (tx + w * 0.68, base_y - th * s),
                    (tx, base_y - (th + 200) * s)], fill=roof)
        cv.line([(tx, base_y - (th + 200) * s), (tx, base_y - (th + 300) * s)], flag, 3 * s)
        cv.polygon([(tx + 2 * s, base_y - (th + 300) * s), (tx + 46 * s, base_y - (th + 282) * s),
                    (tx + 2 * s, base_y - (th + 262) * s)], fill=flag)
        for i in range(3):
            wy = base_y - th * s + 70 * s + i * 90 * s
            if rng.random() < lit_rate:
                cv.rrect(tx - 13 * s, wy, tx + 13 * s, wy + 44 * s, 13 * s, lit)
                cv.glow(tx, wy + 22 * s, 120 * s, lit, 0.35)
            else:
                cv.rrect(tx - 13 * s, wy, tx + 13 * s, wy + 44 * s, 13 * s, shade(stone, 0.7))
    # 城门
    cv.polygon([(x - 55 * s, base_y), (x - 55 * s, base_y - 90 * s),
                (x, base_y - 140 * s), (x + 55 * s, base_y - 90 * s), (x + 55 * s, base_y)],
               fill=shade(stone, 0.55))


def cottage(cv, x, base_y, s, wall, roof, lit=(255, 206, 128), smoke=True, rng=None):
    bw = 520 * s
    bh = 300 * s
    # 主体
    cv.polygon([(x - bw / 2, base_y), (x - bw / 2, base_y - bh),
                (x + bw / 2, base_y - bh), (x + bw / 2, base_y)], fill=wall)
    # 木梁
    cv.line([(x - bw / 2, base_y - bh * 0.55), (x + bw / 2, base_y - bh * 0.55)],
            shade(wall, 0.82), 9 * s)
    for dx in (-0.32, 0.32):
        cv.line([(x + bw * dx, base_y), (x + bw * dx, base_y - bh)], shade(wall, 0.85), 8 * s)
    # 屋顶
    cv.polygon([(x - bw * 0.66, base_y - bh * 0.92), (x + bw * 0.66, base_y - bh * 0.92),
                (x, base_y - bh * 1.72)], fill=roof)
    cv.polygon([(x - bw * 0.66, base_y - bh * 0.92), (x + bw * 0.66, base_y - bh * 0.92),
                (x + bw * 0.6, base_y - bh * 0.83), (x - bw * 0.6, base_y - bh * 0.83)],
               fill=shade(roof, 0.86))
    # 屋檐锯齿
    for i in range(9):
        t = i / 8
        px = x - bw * 0.6 + bw * 1.2 * t
        cv.polygon([(px, base_y - bh * 0.83), (px + bw * 0.05, base_y - bh * 0.83),
                    (px + bw * 0.025, base_y - bh * 0.77)], fill=shade(roof, 0.78))
    # 门
    dw = 92 * s
    cv.polygon([(x - dw / 2, base_y), (x - dw / 2, base_y - 150 * s),
                (x, base_y - 190 * s), (x + dw / 2, base_y - 150 * s), (x + dw / 2, base_y)],
               fill=shade(wall, 0.45))
    cv.circle(x + dw * 0.28, base_y - 78 * s, 4.5 * s, (232, 196, 120))
    # 窗
    for dx in (-0.3, 0.3):
        wx = x + bw * dx
        wy = base_y - bh * 0.62
        cv.rrect(wx - 52 * s, wy - 52 * s, wx + 52 * s, wy + 52 * s, 10 * s, shade(wall, 0.5))
        cv.rrect(wx - 44 * s, wy - 44 * s, wx + 44 * s, wy + 44 * s, 6 * s, lit)
        cv.line([(wx, wy - 44 * s), (wx, wy + 44 * s)], shade(wall, 0.5), 6 * s)
        cv.line([(wx - 44 * s, wy), (wx + 44 * s, wy)], shade(wall, 0.5), 6 * s)
        cv.glow(wx, wy, 190 * s, lit, 0.42)
        # 花箱
        cv.rrect(wx - 50 * s, wy + 50 * s, wx + 50 * s, wy + 74 * s, 6 * s, shade(wall, 0.62))
        for k in range(5):
            cv.circle(wx - 40 * s + k * 20 * s, wy + 50 * s, 7 * s, (214, 92, 96) if k % 2 else (244, 206, 140))
    # 烟囱
    cx = x + bw * 0.24
    cv.polygon([(cx - 34 * s, base_y - bh * 1.28), (cx + 34 * s, base_y - bh * 1.28),
                (cx + 34 * s, base_y - bh * 1.62), (cx - 34 * s, base_y - bh * 1.62)],
               fill=shade(wall, 0.75))
    if smoke and rng:
        lay = layer()
        d = ImageDraw.Draw(lay)
        for i in range(9):
            t = i / 9
            px = cx + math.sin(t * 4.2) * 70 * s + t * 60 * s
            py = base_y - bh * 1.66 - t * 330 * s
            r = (26 + t * 60) * s
            d.ellipse([(px - r) * SS, (py - r) * SS, (px + r) * SS, (py + r) * SS],
                      fill=(232, 232, 240, int(70 * (1 - t) + 10)))
        cv.paste(lay, blur=12)


def room(cv, rng, wall, floor_c, beam, win_sky=None, win_glow=None):
    """室内场景底：木墙 + 地板 + 窗。"""
    cv.polygon([(-100, -100), (W + 100, -100), (W + 100, H * 0.66), (-100, H * 0.66)], fill=wall)
    # 木板缝
    for i in range(0, 26):
        x = i * (W / 24)
        cv.line([(x, 0), (x, H * 0.66)], shade(wall, 0.93), 3)
    cv.polygon([(-100, H * 0.66), (W + 100, H * 0.66), (W + 100, H + 100), (-100, H + 100)], fill=floor_c)
    for i in range(1, 9):
        y = H * 0.66 + i * (H * 0.36 / 8)
        cv.line([(-100, y), (W + 100, y + i * 6)], shade(floor_c, 0.88), 4)
    # 横梁
    cv.polygon([(-100, H * 0.1), (W + 100, H * 0.1), (W + 100, H * 0.145), (-100, H * 0.145)], fill=beam)
    return


def window_night(cv, x, y, w, h, sky_c=(24, 32, 62), star_c=(255, 246, 220), moon_pos=None):
    cv.rrect(x - w / 2 - 22, y - h / 2 - 22, x + w / 2 + 22, y + h / 2 + 22, 16, (74, 46, 34))
    cv.rrect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, 8, sky_c)
    rng = random.Random(int(x + y))
    for _ in range(16):
        sx = rng.uniform(x - w / 2 + 10, x + w / 2 - 10)
        sy = rng.uniform(y - h / 2 + 10, y + h / 2 - 10)
        cv.circle(sx, sy, rng.uniform(1, 2.4), star_c)
    if moon_pos:
        cv.circle(moon_pos[0], moon_pos[1], 34, (250, 244, 214))
        cv.glow(moon_pos[0], moon_pos[1], 150, (250, 244, 214), 0.4)
    cv.line([(x, y - h / 2), (x, y + h / 2)], (74, 46, 34), 10)
    cv.line([(x - w / 2, y), (x + w / 2, y)], (74, 46, 34), 10)


def candle(cv, x, y, h=46, color=(255, 214, 140)):
    cv.rrect(x - 9, y - h, x + 9, y, 4, (238, 232, 220))
    cv.glow(x, y - h - 12, 120, (255, 196, 110), 0.8)
    cv.ellipse(x, y - h - 12, 6, 12, (255, 236, 176))
    cv.ellipse(x, y - h - 9, 3, 6, (255, 252, 230))


def lantern(cv, x, y, s=1.0, color=(255, 200, 120)):
    cv.line([(x, y - 60 * s), (x, y - 8 * s)], (58, 44, 40), 3 * s)
    cv.rrect(x - 16 * s, y - 8 * s, x + 16 * s, y + 26 * s, 6 * s, (86, 62, 46))
    cv.rrect(x - 11 * s, y - 4 * s, x + 11 * s, y + 22 * s, 4 * s, color)
    cv.glow(x, y + 8 * s, 130 * s, color, 0.7)


# ---------------------------------------------------------------- 道具
def apple(cv, cx, cy, r, color=(206, 46, 58), leaf=(96, 148, 82)):
    cv.circle(cx, cy, r, color)
    cv.ellipse(cx - r * 0.35, cy - r * 0.3, r * 0.28, r * 0.16, mix(color, (255, 255, 255), 0.55))
    cv.line([(cx, cy - r * 0.9), (cx + r * 0.08, cy - r * 1.35)], (92, 66, 42), max(2, r * 0.12))
    cv.polygon([(cx + r * 0.05, cy - r * 1.2), (cx + r * 0.85, cy - r * 1.45),
                (cx + r * 0.5, cy - r * 0.98)], fill=leaf)


def basket(cv, cx, cy, s=1.0, wicker=(158, 112, 66), apples=4):
    cv.polygon([(cx - 70 * s, cy), (cx + 70 * s, cy), (cx + 52 * s, cy + 80 * s),
                (cx - 52 * s, cy + 80 * s)], fill=wicker)
    for i in range(3):
        y = cy + 18 * s + i * 22 * s
        cv.line([(cx - 64 * s, y), (cx + 64 * s, y)], shade(wicker, 0.82), 4 * s)
    rng = random.Random(3)
    for i in range(apples):
        apple(cv, cx - 40 * s + i * 26 * s, cy - 4 * s + rng.uniform(-6, 6) * s, 20 * s)
    cv.arc(cx, cy, 62 * s, 62 * s, 200, 340, shade(wicker, 0.85), 5 * s)


def gems(cv, rng, x, y, n=6, scale=1.0):
    cols = [(214, 88, 108), (96, 176, 214), (150, 206, 126), (226, 196, 110), (176, 138, 220)]
    for i in range(n):
        gx = x + rng.uniform(-70, 70) * scale
        gy = y + rng.uniform(-30, 24) * scale
        r = rng.uniform(9, 20) * scale
        c = cols[i % len(cols)]
        cv.polygon([(gx, gy - r), (gx + r * 0.85, gy - r * 0.1), (gx + r * 0.3, gy + r),
                    (gx - r * 0.5, gy + r * 0.7), (gx - r * 0.9, gy - r * 0.2)], fill=c)
        cv.polygon([(gx, gy - r), (gx + r * 0.3, gy + r), (gx - r * 0.4, gy + r * 0.4)],
                   fill=mix(c, (255, 255, 255), 0.45))


def flowers(cv, rng, x0, x1, y0, y1, n=60, scale=1.0, cols=None):
    cols = cols or [(238, 226, 240), (244, 196, 118), (222, 128, 152), (176, 200, 236)]
    for _ in range(n):
        x = rng.uniform(x0, x1)
        y = rng.uniform(y0, y1)
        s = scale * rng.uniform(0.6, 1.3) * (0.5 + 0.9 * (y - y0) / max(1, y1 - y0))
        c = rng.choice(cols)
        for k in range(5):
            a = k * (2 * math.pi / 5) + rng.uniform(0, 1)
            cv.circle(x + math.cos(a) * 5 * s, y + math.sin(a) * 5 * s, 5 * s, c)
        cv.circle(x, y, 3.4 * s, (246, 216, 120))


def bird(cv, x, y, s=1.0, color=(52, 46, 54)):
    cv.arc(x - 16 * s, y, 16 * s, 12 * s, 190, 350, color, 4 * s)
    cv.arc(x + 16 * s, y, 16 * s, 12 * s, 190, 350, color, 4 * s)


def fireflies(cv, rng, n=40, y0=0.3, y1=0.9, color=(255, 236, 160)):
    for _ in range(n):
        x = rng.uniform(0, W)
        y = rng.uniform(H * y0, H * y1)
        cv.glow(x, y, rng.uniform(30, 70), color, rng.uniform(0.5, 0.95), shrink=8)
        cv.circle(x, y, rng.uniform(1.4, 2.6), (255, 252, 226))


def table_settings(cv, cx, cy, s=1.0, n=7):
    """长桌上的七只盘子。"""
    tw = 420 * s
    cv.rrect(cx - tw, cy - 26 * s, cx + tw, cy + 22 * s, 12 * s, (146, 96, 62))
    cv.rrect(cx - tw, cy - 30 * s, cx + tw, cy - 20 * s, 6 * s, (176, 122, 80))
    for i in range(n):
        px = cx - tw + 70 * s + i * (tw * 2 - 140 * s) / (n - 1)
        cv.ellipse(px, cy - 8 * s, 30 * s, 11 * s, (236, 232, 224))
        cv.ellipse(px, cy - 12 * s, 24 * s, 8 * s, (252, 250, 246))
        cv.circle(px, cy - 18 * s, 6 * s, (206, 148, 92))


# ---------------------------------------------------------------- 人物
def _char(cv_layer):
    return cv_layer


def dwarf(cv, x, base_y, h, hat, tunic, beard, skin=(246, 216, 190), pose="stand",
          facing=1, shoes=(72, 54, 48), scale_arm=None):
    """七个小矮人（chibi 比例）。"""
    f = facing
    bw = h * 0.44
    # 影子
    lay = layer()
    d = ImageDraw.Draw(lay)
    d.ellipse([(x - bw * 0.8) * SS, (base_y - h * 0.05) * SS, (x + bw * 0.8) * SS,
               (base_y + h * 0.05) * SS], fill=(0, 0, 0, 70))
    cv.paste(lay, blur=10)
    # 靴子
    cv.ellipse(x - bw * 0.34, base_y - h * 0.03, h * 0.1, h * 0.055, shoes)
    cv.ellipse(x + bw * 0.34, base_y - h * 0.03, h * 0.1, h * 0.055, shoes)
    # 腿
    cv.rrect(x - bw * 0.34 - h * 0.05, base_y - h * 0.24, x - bw * 0.34 + h * 0.05, base_y - h * 0.03,
             h * 0.04, shade(tunic, 0.78))
    cv.rrect(x + bw * 0.34 - h * 0.05, base_y - h * 0.24, x + bw * 0.34 + h * 0.05, base_y - h * 0.03,
             h * 0.04, shade(tunic, 0.78))
    # 身体（上衣）
    body_top = base_y - h * 0.62
    cv.rrect(x - bw / 2, body_top, x + bw / 2, base_y - h * 0.2, bw * 0.26, tunic)
    cv.rrect(x - bw / 2, base_y - h * 0.3, x + bw / 2, base_y - h * 0.22, bw * 0.1, shade(tunic, 0.62))
    cv.circle(x + f * 0, base_y - h * 0.26, h * 0.028, (232, 198, 120))
    # 手臂
    if pose == "up":
        cv.capsule((x - bw * 0.46, base_y - h * 0.56), (x - bw * 0.86, base_y - h * 0.86), h * 0.062, tunic)
        cv.capsule((x + bw * 0.46, base_y - h * 0.56), (x + bw * 0.86, base_y - h * 0.86), h * 0.062, tunic)
        cv.circle(x - bw * 0.88, base_y - h * 0.88, h * 0.05, skin)
        cv.circle(x + bw * 0.88, base_y - h * 0.88, h * 0.05, skin)
    elif pose == "hold":
        cv.capsule((x + bw * 0.44, base_y - h * 0.54), (x + bw * 0.72, base_y - h * 0.34), h * 0.062, tunic)
        cv.circle(x + bw * 0.74, base_y - h * 0.32, h * 0.05, skin)
        cv.capsule((x - bw * 0.44, base_y - h * 0.54), (x - bw * 0.6, base_y - h * 0.3), h * 0.062, tunic)
    elif pose == "waist":
        cv.capsule((x - bw * 0.44, base_y - h * 0.55), (x - bw * 0.6, base_y - h * 0.42), h * 0.062, tunic)
        cv.capsule((x + bw * 0.44, base_y - h * 0.55), (x + bw * 0.6, base_y - h * 0.42), h * 0.062, tunic)
    else:  # walk
        cv.capsule((x - bw * 0.44, base_y - h * 0.54), (x - bw * 0.68, base_y - h * 0.3), h * 0.062, tunic)
        cv.capsule((x + bw * 0.44, base_y - h * 0.54), (x + bw * 0.55, base_y - h * 0.3), h * 0.062, tunic)
        cv.circle(x - bw * 0.7, base_y - h * 0.28, h * 0.05, skin)
        cv.circle(x + bw * 0.56, base_y - h * 0.28, h * 0.05, skin)
    # 胡子
    by = base_y - h * 0.6
    cv.polygon([(x - bw * 0.36, by), (x + bw * 0.36, by), (x + bw * 0.3, by + h * 0.2),
                (x, by + h * 0.31), (x - bw * 0.3, by + h * 0.2)], fill=beard)
    cv.ellipse(x, by + h * 0.09, bw * 0.2, h * 0.055, mix(beard, (0, 0, 0), 0.12))
    # 脸
    fy = by - h * 0.02
    cv.circle(x, fy - h * 0.03, h * 0.135, skin)
    cv.circle(x - h * 0.05, fy - h * 0.05, h * 0.0145, (52, 42, 40))
    cv.circle(x + h * 0.05, fy - h * 0.05, h * 0.0145, (52, 42, 40))
    cv.circle(x, fy + h * 0.005, h * 0.036, mix(skin, (226, 150, 130), 0.45))
    cv.arc(x, fy + h * 0.02, h * 0.045, h * 0.035, 20, 160, (120, 66, 60), h * 0.011)
    cv.circle(x - h * 0.085, fy + h * 0.01, h * 0.022, mix(skin, (230, 130, 130), 0.5))
    cv.circle(x + h * 0.085, fy + h * 0.01, h * 0.022, mix(skin, (230, 130, 130), 0.5))
    # 帽子
    hy = fy - h * 0.1
    cv.polygon([(x - h * 0.165, hy), (x + h * 0.165, hy), (x + h * 0.145, hy - h * 0.075),
                (x - h * 0.145, hy - h * 0.075)], fill=hat)
    tipx = x + f * h * 0.12
    cv.polygon([(x - h * 0.145, hy - h * 0.07), (x + h * 0.145, hy - h * 0.07),
                (tipx, hy - h * 0.28)], fill=hat)
    cv.circle(tipx, hy - h * 0.28, h * 0.032, mix(hat, (255, 255, 255), 0.25))


def princess_sprite(h, colors=None):
    """返回直立白雪公主的 RGBA 图层（画布为 1.2h x 2.6h，脚底居中偏下）。"""
    c = colors or {}
    hair = c.get("hair", (38, 32, 40))
    skin = c.get("skin", (250, 232, 220))
    dress = c.get("dress", (58, 92, 158))
    skirt = c.get("skirt", (242, 200, 96))
    ribbon = c.get("ribbon", (206, 62, 72))
    cw, ch = int(h * 1.5), int(h * 2.2)
    img = Image.new("RGBA", (cw * SS, ch * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    ox, oy = cw * 0.5, ch * 0.92  # 脚底位置

    def E(cx, cy, rx, ry, fill):
        d.ellipse([(cx - rx) * SS, (cy - ry) * SS, (cx + rx) * SS, (cy + ry) * SS], fill=fill)

    def P(pts, fill):
        d.polygon([(x * SS, y * SS) for x, y in pts], fill=fill)

    def R(x0, y0, x1, y1, r, fill):
        d.rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], radius=r * SS, fill=fill)

    # 小脚
    E(ox - h * 0.1, oy - h * 0.02, h * 0.07, h * 0.035, (70, 54, 48))
    E(ox + h * 0.1, oy - h * 0.02, h * 0.07, h * 0.035, (70, 54, 48))
    # 裙子
    P([(ox - h * 0.13, oy - h * 0.5), (ox + h * 0.13, oy - h * 0.5),
       (ox + h * 0.34, oy - h * 0.02), (ox - h * 0.34, oy - h * 0.02)], skirt)
    P([(ox - h * 0.13, oy - h * 0.5), (ox + h * 0.13, oy - h * 0.5),
       (ox + h * 0.2, oy - h * 0.34), (ox - h * 0.2, oy - h * 0.34)], mix(skirt, (255, 255, 255), 0.28))
    for i in range(4):
        xx = ox - h * 0.26 + i * h * 0.17
        P([(xx, oy - h * 0.3), (xx + h * 0.05, oy - h * 0.3), (xx + h * 0.02, oy - h * 0.02),
           (xx - h * 0.03, oy - h * 0.02)], mix(skirt, (214, 150, 60), 0.5))
    # 上衣
    P([(ox - h * 0.15, oy - h * 0.96), (ox + h * 0.15, oy - h * 0.96),
       (ox + h * 0.13, oy - h * 0.46), (ox - h * 0.13, oy - h * 0.46)], dress)
    R(ox - h * 0.14, oy - h * 0.56, ox + h * 0.14, oy - h * 0.5, h * 0.02, mix(dress, (0, 0, 0), 0.2))
    # 泡泡袖
    E(ox - h * 0.19, oy - h * 0.86, h * 0.075, h * 0.075, mix(dress, (255, 255, 255), 0.18))
    E(ox + h * 0.19, oy - h * 0.86, h * 0.075, h * 0.075, mix(dress, (255, 255, 255), 0.18))
    # 手臂
    R(ox - h * 0.23, oy - h * 0.82, ox - h * 0.155, oy - h * 0.5, h * 0.035, skin)
    R(ox + h * 0.155, oy - h * 0.82, ox + h * 0.23, oy - h * 0.5, h * 0.035, skin)
    E(ox - h * 0.19, oy - h * 0.48, h * 0.045, h * 0.04, skin)
    E(ox + h * 0.19, oy - h * 0.48, h * 0.045, h * 0.04, skin)
    # 白领
    P([(ox - h * 0.15, oy - h * 0.96), (ox + h * 0.15, oy - h * 0.96),
       (ox + h * 0.1, oy - h * 1.0), (ox - h * 0.1, oy - h * 1.0)], (248, 246, 240))
    # 脖子 + 头
    R(ox - h * 0.035, oy - h * 1.02, ox + h * 0.035, oy - h * 0.95, h * 0.02, shade(skin, 0.92))
    E(ox, oy - h * 1.13, h * 0.125, h * 0.135, skin)
    # 头发
    d.pieslice([(ox - h * 0.145) * SS, (oy - h * 1.28) * SS, (ox + h * 0.145) * SS,
                (oy - h * 1.0) * SS], 180, 360, fill=hair)
    E(ox - h * 0.125, oy - h * 1.11, h * 0.045, h * 0.1, hair)
    E(ox + h * 0.125, oy - h * 1.11, h * 0.045, h * 0.1, hair)
    R(ox - h * 0.145, oy - h * 1.2, ox - h * 0.1, oy - h * 1.02, h * 0.03, hair)
    R(ox + h * 0.1, oy - h * 1.2, ox + h * 0.145, oy - h * 1.02, h * 0.03, hair)
    # 蝴蝶结
    E(ox - h * 0.05, oy - h * 1.26, h * 0.045, h * 0.032, ribbon)
    E(ox + h * 0.05, oy - h * 1.26, h * 0.045, h * 0.032, ribbon)
    E(ox, oy - h * 1.26, h * 0.025, h * 0.025, mix(ribbon, (255, 255, 255), 0.2))
    # 五官
    E(ox - h * 0.05, oy - h * 1.145, h * 0.014, h * 0.019, (48, 40, 48))
    E(ox + h * 0.05, oy - h * 1.145, h * 0.014, h * 0.019, (48, 40, 48))
    E(ox - h * 0.088, oy - h * 1.1, h * 0.024, h * 0.016, (246, 186, 178))
    E(ox + h * 0.088, oy - h * 1.1, h * 0.024, h * 0.016, (246, 186, 178))
    d.arc([(ox - h * 0.028) * SS, (oy - h * 1.115) * SS, (ox + h * 0.028) * SS,
           (oy - h * 1.075) * SS], 20, 160, fill=(176, 86, 86), width=int(h * 0.012 * SS))
    return img, (cw, ch, ox, oy)


def place_princess(cv, x, base_y, h, colors=None, rotate=0.0):
    img, (cw, ch, ox, oy) = princess_sprite(h, colors)
    if rotate:
        img = img.rotate(rotate, resample=Image.BICUBIC, expand=True)
        # 旋转后重新定位中心
        cv.img.paste(img, (int((x - img.width / (2 * SS)) * SS), int((base_y - img.height / (2 * SS)) * SS)), img)
    else:
        cv.img.paste(img, (int((x - ox) * SS), int((base_y - oy) * SS)), img)
        cv.d = ImageDraw.Draw(cv.img)


def prince(cv, x, base_y, h, tunic=(60, 78, 138), cape=(158, 54, 62), hair=(52, 44, 44),
           skin=(248, 226, 206), crown=True):
    bw = h * 0.3
    lay = layer()
    d = ImageDraw.Draw(lay)
    d.ellipse([(x - bw) * SS, (base_y - h * 0.05) * SS, (x + bw) * SS, (base_y + h * 0.05) * SS],
              fill=(0, 0, 0, 70))
    cv.paste(lay, blur=10)
    # 斗篷
    cv.polygon([(x - h * 0.16, base_y - h * 0.8), (x + h * 0.02, base_y - h * 0.8),
                (x + h * 0.2, base_y - h * 0.06), (x - h * 0.24, base_y - h * 0.06)], cape)
    # 靴子
    cv.rrect(x - h * 0.13, base_y - h * 0.22, x - h * 0.02, base_y - h * 0.01, h * 0.03, (62, 48, 44))
    cv.rrect(x + h * 0.02, base_y - h * 0.22, x + h * 0.13, base_y - h * 0.01, h * 0.03, (62, 48, 44))
    # 腿
    cv.rrect(x - h * 0.09, base_y - h * 0.36, x - h * 0.03, base_y - h * 0.18, h * 0.02, (78, 62, 58))
    cv.rrect(x + h * 0.03, base_y - h * 0.36, x + h * 0.09, base_y - h * 0.18, h * 0.02, (78, 62, 58))
    # 上衣
    cv.polygon([(x - h * 0.14, base_y - h * 0.74), (x + h * 0.14, base_y - h * 0.74),
                (x + h * 0.11, base_y - h * 0.33), (x - h * 0.11, base_y - h * 0.33)], tunic)
    cv.rrect(x - h * 0.115, base_y - h * 0.4, x + h * 0.115, base_y - h * 0.34, h * 0.02, (206, 168, 96))
    cv.circle(x, base_y - h * 0.37, h * 0.022, (232, 204, 140))
    # 手臂（伸向公主）
    cv.capsule((x - h * 0.13, base_y - h * 0.68), (x - h * 0.3, base_y - h * 0.5), h * 0.035, tunic)
    cv.capsule((x + h * 0.13, base_y - h * 0.68), (x + h * 0.28, base_y - h * 0.52), h * 0.035, tunic)
    cv.circle(x - h * 0.31, base_y - h * 0.49, h * 0.035, skin)
    cv.circle(x + h * 0.29, base_y - h * 0.51, h * 0.035, skin)
    # 头
    cv.rrect(x - h * 0.03, base_y - h * 0.8, x + h * 0.03, base_y - h * 0.72, h * 0.02, shade(skin, 0.93))
    cv.circle(x, base_y - h * 0.85, h * 0.105, skin)
    cv.pieslice(x, base_y - h * 0.88, h * 0.115, h * 0.12, 185, 355, hair)
    cv.circle(x - h * 0.04, base_y - h * 0.855, h * 0.012, (48, 42, 44))
    cv.circle(x + h * 0.04, base_y - h * 0.855, h * 0.012, (48, 42, 44))
    cv.arc(x, base_y - h * 0.83, h * 0.03, h * 0.022, 20, 160, (150, 96, 88), h * 0.01)
    if crown:
        cv.polygon([(x - h * 0.075, base_y - h * 0.95), (x - h * 0.05, base_y - h * 1.02),
                    (x - h * 0.02, base_y - h * 0.955), (x, base_y - h * 1.03),
                    (x + h * 0.02, base_y - h * 0.955), (x + h * 0.05, base_y - h * 1.02),
                    (x + h * 0.075, base_y - h * 0.95)], fill=(232, 196, 112))
        cv.glow(x, base_y - h * 0.99, h * 0.2, (255, 226, 150), 0.5)


def witch(cv, x, base_y, h, cloak=(56, 44, 68), face=(176, 196, 152), basket_pos=None):
    bw = h * 0.42
    lay = layer()
    d = ImageDraw.Draw(lay)
    d.ellipse([(x - bw) * SS, (base_y - h * 0.04) * SS, (x + bw) * SS, (base_y + h * 0.05) * SS],
              fill=(0, 0, 0, 80))
    cv.paste(lay, blur=12)
    # 斗篷
    cv.polygon([(x - h * 0.1, base_y - h * 0.85), (x + h * 0.1, base_y - h * 0.85),
                (x + h * 0.34, base_y), (x - h * 0.36, base_y)], fill=cloak)
    cv.polygon([(x - h * 0.1, base_y - h * 0.85), (x - h * 0.02, base_y - h * 0.85),
                (x - h * 0.08, base_y), (x - h * 0.36, base_y)], fill=shade(cloak, 1.22))
    # 兜帽
    cv.pieslice(x, base_y - h * 0.95, h * 0.2, h * 0.24, 0, 360, shade(cloak, 1.1))
    cv.pieslice(x, base_y - h * 0.94, h * 0.135, h * 0.17, 0, 360, (28, 24, 32))
    cv.circle(x, base_y - h * 0.94, h * 0.115, face)
    # 尖鼻、眼睛
    cv.polygon([(x + h * 0.02, base_y - h * 0.97), (x + h * 0.13, base_y - h * 0.93),
                (x + h * 0.03, base_y - h * 0.9)], fill=shade(face, 0.92))
    cv.circle(x - h * 0.045, base_y - h * 0.98, h * 0.013, (28, 24, 28))
    cv.circle(x + h * 0.045, base_y - h * 0.98, h * 0.013, (28, 24, 28))
    cv.arc(x, base_y - h * 0.91, h * 0.05, h * 0.03, 200, 340, (70, 40, 44), h * 0.012)
    # 枯瘦手
    cv.capsule((x + h * 0.16, base_y - h * 0.7), (x + h * 0.28, base_y - h * 0.55), h * 0.026, face)
    cv.capsule((x - h * 0.16, base_y - h * 0.7), (x - h * 0.3, base_y - h * 0.48), h * 0.026, face)
    if basket_pos:
        bx, by = basket_pos
        basket(cv, bx, by, 0.9)
        apple(cv, bx + 4, by - 42, 26)


def mirror_frame(cv, cx, cy, w, h, frame=(164, 128, 74), glass=(24, 30, 44), glow_c=(126, 214, 210)):
    # 挂链
    cv.line([(cx - w * 0.34, cy - h * 0.52), (cx - w * 0.2, -100)], (96, 82, 62), 7)
    cv.line([(cx + w * 0.34, cy - h * 0.52), (cx + w * 0.2, -100)], (96, 82, 62), 7)
    # 外框
    cv.ellipse(cx, cy, w * 0.56, h * 0.56, frame)
    cv.ellipse(cx, cy, w * 0.48, h * 0.48, shade(frame, 0.8))
    # 玻璃
    cv.ellipse(cx, cy, w * 0.44, h * 0.44, glass)
    # 顶部装饰
    cv.circle(cx, cy - h * 0.58, w * 0.07, frame)
    cv.circle(cx, cy - h * 0.58, w * 0.035, mix(glow_c, (255, 255, 255), 0.3))
    cv.glow(cx, cy, w * 0.9, glow_c, 0.5)
    # 玻璃内微光
    lay = layer()
    d = ImageDraw.Draw(lay)
    for i in range(5):
        r = w * (0.1 + i * 0.07)
        d.ellipse([(cx - r) * SS, (cy - r * 0.9) * SS, (cx + r) * SS, (cy + r * 0.9) * SS],
                  outline=glow_c + (40,), width=int(2 * SS))
    cv.paste(lay, blur=6)


# ---------------------------------------------------------------- 场景
def s_title(cv, rng):
    sky(cv, (10, 14, 36), (54, 62, 108), mid=(28, 34, 68), horizon=0.58)
    stars(cv, rng, 190, 0.66)
    moon(cv, W * 0.79, H * 0.2, 95)
    clouds(cv, rng, H * 0.42, 4, (170, 182, 224), 0.1, 1.3)
    ridge(cv, rng, H * 0.74, H * 0.3, (34, 42, 78), seed=2)
    ridge(cv, rng, H * 0.86, H * 0.22, (24, 30, 60), seed=5)
    forest_band(cv, rng, H * 0.93, 380, (14, 20, 44), 20)
    hill(cv, rng, H * 0.95, 26, (10, 15, 36), seed=3)
    mist(cv, rng, H * 0.88, 160, (150, 168, 214), 0.14)
    # 山丘上七个剪影
    for i in range(7):
        hh = 110 + (i % 3) * 12
        dwarf(cv, W * 0.5 + (i - 3) * 105, H * 0.995, hh, (18, 22, 44), (16, 20, 40),
              (26, 30, 52), skin=(20, 24, 46), shoes=(12, 16, 34))
    fireflies(cv, rng, 24, 0.6, 0.92)
    # 标题
    cv.glow(W * 0.5, H * 0.42, 700, (120, 140, 210), 0.35, shrink=6)
    cv.text(W * 0.5, H * 0.36, "白雪公主", 168, (250, 244, 228), "black", tracking=14)
    cv.text(W * 0.5, H * 0.475, "与七个小矮人", 74, (238, 226, 200), "bold", tracking=10)
    cv.text(W * 0.5, H * 0.55, "SNOW WHITE  AND  THE  SEVEN  DWARFS", 30, (176, 194, 226),
            "regular", tracking=8)
    cv.line([(W * 0.5 - 330, H * 0.415), (W * 0.5 + 330, H * 0.415)], (196, 170, 120), 2)


def s_mirror(cv, rng):
    wall = (44, 34, 56)
    cv.polygon([(-100, -100), (W + 100, -100), (W + 100, H + 100), (-100, H + 100)], fill=wall)
    for i in range(0, 30):
        x = i * (W / 28)
        cv.line([(x, 0), (x - 60, H)], shade(wall, 0.9), 3)
    # 石砖
    for r in range(9):
        y = r * (H / 8)
        cv.line([(-100, y), (W + 100, y)], shade(wall, 0.88), 3)
        for c in range(14):
            off = (r % 2) * (W / 28)
            cv.line([(c * (W / 14) + off, y), (c * (W / 14) + off, y + H / 8)], shade(wall, 0.9), 2)
    # 窗帘
    for side, x0 in [(-1, -120), (1, W + 120)]:
        cv.polygon([(x0, -100), (x0 + side * 620, -100), (x0 + side * 500, H * 0.86),
                    (x0 + side * 260, H * 0.86), (x0 + side * 300, -100)], fill=(92, 34, 52))
        cv.polygon([(x0 + side * 120, -100), (x0 + side * 210, -100), (x0 + side * 170, H * 0.86),
                    (x0 + side * 80, H * 0.86)], fill=(72, 26, 42))
    mirror_frame(cv, W * 0.5, H * 0.44, 620, 900)
    # 镜中王后剪影
    cv.pieslice(W * 0.5, H * 0.55, 120, 150, 190, 350, (16, 20, 30))
    cv.polygon([(W * 0.5 - 150, H * 0.68), (W * 0.5 + 150, H * 0.68),
                (W * 0.5 + 110, H * 0.44), (W * 0.5 - 110, H * 0.44)], (16, 20, 30))
    cv.circle(W * 0.5, H * 0.36, 58, (232, 196, 112))
    cv.glow(W * 0.5, H * 0.36, 220, (255, 216, 130), 0.7)
    # 王后（背对，戴高冠）
    qx, qy = W * 0.5 - 720, H * 0.99
    cv.polygon([(qx - 150, qy), (qx + 150, qy), (qx + 90, qy - 520), (qx - 90, qy - 520)], (32, 24, 46))
    cv.polygon([(qx - 150, qy), (qx - 20, qy), (qx - 10, qy - 520), (qx - 90, qy - 520)], (44, 32, 62))
    cv.circle(qx, qy - 600, 88, (226, 198, 168))
    cv.pieslice(qx, qy - 620, 95, 100, 180, 360, (26, 20, 30))
    cv.polygon([(qx - 78, qy - 690), (qx - 40, qy - 800), (qx, qy - 720), (qx + 40, qy - 810),
                (qx + 78, qy - 690)], fill=(206, 168, 96))
    cv.circle(qx, qy - 760, 14, (226, 76, 96))
    # 烛台
    for cx in (W * 0.13, W * 0.87):
        cv.rrect(cx - 16, H * 0.72, cx + 16, H * 0.99, 8, (166, 132, 72))
        for k in range(3):
            candle(cv, cx - 60 + k * 60, H * 0.72, 90)
    cv.glow(W * 0.5, H * 0.9, 700, (86, 60, 80), 0.4, shrink=8)


def s_forest_run(cv, rng):
    sky(cv, (8, 14, 30), (30, 48, 62), mid=(14, 26, 44), horizon=0.5)
    stars(cv, rng, 90, 0.4)
    moon(cv, W * 0.24, H * 0.17, 78, (226, 232, 226), 0.8)
    ridge(cv, rng, H * 0.6, H * 0.18, (18, 32, 44), seed=9)
    # 月光束
    lay = layer()
    d = ImageDraw.Draw(lay)
    for i in range(3):
        d.polygon([(W * 0.22 + i * 40, H * 0.12), (W * 0.3 + i * 60, H * 0.12),
                   (W * 0.62 + i * 120, H), (W * 0.3 + i * 120, H)],
                  fill=(196, 216, 214, 26))
    cv.paste(lay, blur=30)
    forest_band(cv, rng, H * 0.84, 700, (10, 20, 30), 13, jitter=0.28)
    ground(cv, H * 0.86, (16, 26, 30), rng, (26, 40, 40), 40)
    mist(cv, rng, H * 0.84, 150, (150, 176, 178), 0.2)
    # 逃跑的公主（小、侧身）
    place_princess(cv, W * 0.56, H * 0.93, 250)
    # 前景大树
    for x, w in [(70, 1.0), (W - 90, 1.1)]:
        cv.polygon([(x - 60 * w, H + 100), (x - 42 * w, -100), (x + 42 * w, -100),
                    (x + 60 * w, H + 100)], fill=(6, 12, 20))
        for k in range(4):
            cv.polygon([(x, -100 + k * 60), (x - 260 * w, 60 + k * 90), (x + 200 * w, 20 + k * 100)],
                       fill=(6, 12, 20))
    fireflies(cv, rng, 18, 0.5, 0.9, (212, 236, 200))
    vignette_corners(cv, (2, 6, 12), 0.42)


def s_cottage_dusk(cv, rng):
    sky(cv, (28, 30, 66), (150, 106, 116), mid=(70, 58, 96), horizon=0.5)
    stars(cv, rng, 110, 0.42)
    moon(cv, W * 0.16, H * 0.16, 62, (246, 240, 216), 0.55)
    clouds(cv, rng, H * 0.3, 5, (240, 186, 170), 0.16, 1.2)
    ridge(cv, rng, H * 0.62, H * 0.2, (48, 44, 76), seed=4)
    forest_band(cv, rng, H * 0.74, 380, (32, 36, 58), 18)
    hill(cv, rng, H * 0.86, 30, (30, 40, 52), seed=6)
    ground(cv, H * 0.9, (26, 38, 44), rng, (36, 52, 56), 50)
    cottage(cv, W * 0.54, H * 0.9, 1.05, (96, 70, 54), (86, 52, 48), (255, 208, 132), True, rng)
    cv.glow(W * 0.54, H * 0.86, 620, (255, 190, 120), 0.5, shrink=6)
    # 小径
    pts = [(W * 0.5, H * 0.94), (W * 0.44, H), (W * 0.2, H), (W * 0.36, H * 0.96)]
    cv.polygon([(W * 0.5, H * 0.93), (W * 0.62, H * 0.95), (W - 100, H + 50), (W * 0.1, H + 50),
                (W * 0.4, H * 0.95)], fill=(120, 92, 78))
    fireflies(cv, rng, 34, 0.55, 0.95)
    shrubs = (34, 48, 46)
    for x in (W * 0.12, W * 0.9, W * 0.3):
        for k in range(4):
            cv.ellipse(x + k * 30 - 45, H * 0.94 + rng.uniform(-10, 10), 46, 34, shrubs)


def s_cottage_inside(cv, rng):
    room(cv, rng, (92, 62, 46), (122, 84, 58), (74, 48, 36))
    window_night(cv, W * 0.14, H * 0.3, 300, 330, (18, 26, 52), (250, 244, 218), (W * 0.1, H * 0.24))
    cv.glow(W * 0.14, H * 0.3, 560, (140, 170, 220), 0.35, shrink=6)
    # 壁炉
    cv.polygon([(W * 0.72, H * 0.66), (W * 0.98, H * 0.66), (W * 0.96, H * 0.3),
                (W * 0.74, H * 0.3)], fill=(84, 58, 46))
    cv.rrect(W * 0.75, H * 0.6, W * 0.95, H * 0.9, 26, (48, 32, 28))
    cv.glow(W * 0.85, H * 0.76, 520, (255, 156, 72), 0.9, shrink=6)
    for i in range(7):
        cv.polygon([(W * 0.77 + i * 12, H * 0.86), (W * 0.8 + i * 12, H * 0.78),
                    (W * 0.83 + i * 12, H * 0.86)], fill=(255, 186, 92))
    # 七张小床（背景墙）
    for i in range(7):
        bx = W * 0.12 + i * W * 0.085
        cv.rrect(bx - 46, H * 0.5, bx + 46, H * 0.63, 12, (128, 86, 60))
        cv.rrect(bx - 40, H * 0.49, bx + 40, H * 0.55, 10, (214, 200, 190))
        cv.rrect(bx - 38, H * 0.53, bx + 38, H * 0.58, 8, (176, 110, 110) if i % 2 else (120, 148, 178))
    # 桌子
    cv.polygon([(W * 0.3, H * 0.74), (W * 0.72, H * 0.74), (W * 0.7, H * 0.8), (W * 0.32, H * 0.8)],
               fill=(150, 102, 66))
    table_settings(cv, W * 0.5, H * 0.74, 1.0, 7)
    for x in (W * 0.36, W * 0.64):
        candle(cv, x, H * 0.735, 54)
    # 扫帚与水桶
    cv.line([(W * 0.2, H * 0.86), (W * 0.26, H * 0.74)], (150, 112, 70), 10)
    cv.polygon([(W * 0.19, H * 0.88), (W * 0.24, H * 0.845), (W * 0.205, H * 0.83),
                (W * 0.16, H * 0.865)], fill=(206, 172, 106))
    # 公主打扫
    place_princess(cv, W * 0.5, H * 0.98, 330)
    cv.glow(W * 0.5, H * 0.85, 700, (255, 180, 110), 0.3, shrink=8)


def s_dwarfs_home(cv, rng):
    sky(cv, (34, 40, 84), (232, 158, 116), mid=(120, 88, 116), horizon=0.52)
    clouds(cv, rng, H * 0.28, 6, (250, 208, 176), 0.2, 1.4)
    sun(cv, W * 0.78, H * 0.42, 90, (255, 206, 150), 0.9)
    ridge(cv, rng, H * 0.66, H * 0.22, (94, 68, 100), seed=8)
    ridge(cv, rng, H * 0.78, H * 0.16, (64, 52, 76), seed=12)
    forest_band(cv, rng, H * 0.86, 300, (44, 40, 58), 16)
    hill(cv, rng, H * 0.94, 22, (38, 44, 54), seed=2)
    ground(cv, H * 0.95, (46, 52, 54), rng, (58, 66, 60), 40)
    # 远处木屋
    cottage(cv, W * 0.86, H * 0.95, 0.42, (92, 68, 52), (78, 48, 46), (255, 206, 132), False, rng)
    # 小径
    cv.polygon([(W * 0.1, H + 60), (W * 0.34, H + 60), (W * 0.92, H * 0.95), (W * 0.72, H * 0.95)],
               fill=(126, 104, 86))
    hats = [(196, 88, 64), (86, 132, 84), (72, 104, 156), (156, 90, 140), (200, 148, 70),
            (108, 112, 136), (168, 70, 70)]
    beards = [(236, 232, 224), (200, 196, 188), (150, 116, 84), (222, 218, 210),
              (184, 180, 172), (120, 96, 76), (226, 222, 214)]
    for i in range(7):
        t = i / 6
        x = W * 0.2 + t * W * 0.5
        y = H * 0.985 - t * 20
        hh = 300 - abs(i - 3) * 12
        dwarf(cv, x, y, hh, hats[i], shade(hats[i], 1.25), beards[i],
              pose="walk" if i % 2 == 0 else "stand", facing=1)
    cv.glow(W * 0.78, H * 0.42, 900, (255, 190, 130), 0.35, shrink=6)
    fireflies(cv, rng, 26, 0.7, 0.98)


def s_dwarfs_promise(cv, rng):
    room(cv, rng, (88, 60, 48), (116, 80, 56), (70, 46, 34))
    cv.glow(W * 0.5, H * 0.42, 900, (255, 186, 110), 0.55, shrink=6)
    # 窗
    window_night(cv, W * 0.82, H * 0.28, 300, 320, (22, 30, 56), (250, 244, 220), (W * 0.84, H * 0.22))
    # 桌子
    cv.polygon([(W * 0.06, H * 0.78), (W * 0.5, H * 0.78), (W * 0.48, H * 0.86), (W * 0.08, H * 0.86)],
               fill=(152, 104, 68))
    table_settings(cv, W * 0.28, H * 0.78, 0.75, 7)
    # 公主站中间
    place_princess(cv, W * 0.5, H * 0.99, 360)
    cv.glow(W * 0.5, H * 0.88, 640, (255, 200, 130), 0.3, shrink=8)
    hats = [(196, 88, 64), (86, 132, 84), (72, 104, 156), (156, 90, 140), (200, 148, 70),
            (108, 112, 136), (168, 70, 70)]
    beards = [(236, 232, 224), (200, 196, 188), (150, 116, 84), (222, 218, 210),
              (184, 180, 172), (120, 96, 76), (226, 222, 214)]
    xs = [0.1, 0.19, 0.28, 0.37, 0.63, 0.74, 0.87]
    for i, x in enumerate(xs):
        hh = 250 if i % 2 else 265
        dwarf(cv, W * x, H * 0.99 - (0 if i < 4 else 6), hh, hats[i], shade(hats[i], 1.25),
              beards[i], pose="up" if i in (1, 5) else "stand")
    for x in (W * 0.34, W * 0.66):
        candle(cv, x, H * 0.96, 46)


def s_witch_apple(cv, rng):
    sky(cv, (26, 24, 52), (108, 74, 96), mid=(52, 42, 74), horizon=0.5)
    moon(cv, W * 0.18, H * 0.18, 70, (238, 232, 210), 0.5)
    forest_band(cv, rng, H * 0.78, 560, (18, 20, 34), 12, jitter=0.3)
    ground(cv, H * 0.9, (34, 30, 38), rng, (48, 42, 46), 46)
    cottage(cv, W * 0.2, H * 0.94, 0.8, (92, 68, 52), (74, 46, 44), (255, 200, 128), False, rng)
    cv.glow(W * 0.2, H * 0.88, 500, (255, 176, 110), 0.45, shrink=6)
    mist(cv, rng, H * 0.86, 130, (150, 140, 170), 0.22)
    witch(cv, W * 0.62, H * 0.965, 620, (52, 42, 64), (168, 190, 146),
          basket_pos=(W * 0.76, H * 0.86))
    cv.glow(W * 0.755, H * 0.79, 220, (255, 120, 110), 0.3, shrink=8)
    # 乌鸦
    for x, y in [(W * 0.44, H * 0.16), (W * 0.5, H * 0.1), (W * 0.9, H * 0.2)]:
        bird(cv, x, y, 1.2, (14, 14, 20))
    vignette_corners(cv, (4, 2, 10), 0.4)


def s_apple_fall(cv, rng):
    room(cv, rng, (66, 46, 42), (88, 62, 46), (52, 34, 30))
    cv.glow(W * 0.2, H * 0.3, 420, (90, 110, 150), 0.2, shrink=8)
    window_night(cv, W * 0.16, H * 0.28, 280, 300, (16, 22, 44), (240, 236, 212), (W * 0.12, H * 0.22))
    # 倒下的椅子
    cv.polygon([(W * 0.36, H * 0.84), (W * 0.5, H * 0.74), (W * 0.54, H * 0.79),
                (W * 0.4, H * 0.89)], fill=(126, 86, 58))
    cv.polygon([(W * 0.49, H * 0.73), (W * 0.51, H * 0.6), (W * 0.56, H * 0.62),
                (W * 0.55, H * 0.76)], fill=(126, 86, 58))
    # 公主倒下（旋转横躺）
    place_princess(cv, W * 0.52, H * 0.88, 340, rotate=78)
    cv.glow(W * 0.5, H * 0.9, 700, (90, 100, 140), 0.18, shrink=8)
    apple(cv, W * 0.66, H * 0.93, 30)
    # 桌上的苹果篮
    basket(cv, W * 0.84, H * 0.72, 0.8)
    vignette_corners(cv, (2, 2, 8), 0.5)


def s_sorrow(cv, rng):
    room(cv, rng, (54, 48, 54), (72, 60, 56), (40, 34, 38))
    # 雨窗
    window_night(cv, W * 0.5, H * 0.24, 420, 380, (40, 50, 70), (150, 170, 190), None)
    lay = layer()
    d = ImageDraw.Draw(lay)
    for i in range(90):
        x = W * 0.5 - 200 + rng.uniform(0, 400)
        y = H * 0.06 + rng.uniform(0, 330)
        d.line([(x * SS, y * SS), ((x - 6) * SS, (y + 26) * SS)], fill=(180, 200, 220, 90), width=int(2 * SS))
    cv.paste(lay, blur=1)
    # 床
    cv.rrect(W * 0.16, H * 0.66, W * 0.84, H * 0.78, 22, (120, 84, 62))
    cv.rrect(W * 0.19, H * 0.6, W * 0.81, H * 0.7, 18, (198, 188, 186))
    # 躺着的公主
    place_princess(cv, W * 0.5, H * 0.65, 300, rotate=86)
    cv.rrect(W * 0.19, H * 0.72, W * 0.81, H * 0.76, 12, (188, 96, 106))
    hats = [(150, 78, 64), (78, 112, 80), (68, 92, 130), (128, 84, 122), (168, 130, 74),
            (96, 98, 112), (138, 70, 70)]
    beards = [(206, 202, 196), (176, 172, 166), (138, 108, 80), (196, 192, 186),
              (162, 158, 152), (110, 88, 72), (198, 194, 188)]
    xs = [0.24, 0.33, 0.42, 0.58, 0.67, 0.76, 0.86]
    for i, x in enumerate(xs):
        dwarf(cv, W * x, H * 0.97, 210, hats[i], shade(hats[i], 1.2), beards[i],
              pose="waist", facing=1 if x < 0.5 else -1)
    for x in (W * 0.1, W * 0.9):
        candle(cv, x, H * 0.85, 40, (255, 210, 150))
    vignette_corners(cv, (2, 4, 10), 0.55)


def s_coffin_hill(cv, rng):
    sky(cv, (58, 66, 116), (238, 190, 168), mid=(150, 132, 156), horizon=0.48)
    sun(cv, W * 0.5, H * 0.3, 88, (255, 228, 180), 0.75)
    clouds(cv, rng, H * 0.26, 5, (255, 214, 200), 0.18, 1.5)
    ridge(cv, rng, H * 0.58, H * 0.2, (118, 100, 132), seed=14)
    ridge(cv, rng, H * 0.68, H * 0.14, (86, 76, 106), seed=3)
    forest_band(cv, rng, H * 0.78, 300, (56, 52, 78), 18)
    hill(cv, rng, H * 0.88, 34, (60, 66, 66), seed=9)
    ground(cv, H * 0.94, (54, 62, 58), rng, (70, 82, 66), 60)
    # 水晶棺
    cx, cy = W * 0.5, H * 0.86
    cw_, ch_ = 780, 230
    # 台座
    cv.rrect(cx - cw_ * 0.56, cy - 10, cx + cw_ * 0.56, cy + 70, 18, (108, 106, 112))
    cv.rrect(cx - cw_ * 0.56, cy - 18, cx + cw_ * 0.56, cy + 6, 10, (138, 136, 142))
    # 棺体（半透明）
    lay = layer()
    d = ImageDraw.Draw(lay)
    d.rounded_rectangle([(cx - cw_ / 2) * SS, (cy - ch_) * SS, (cx + cw_ / 2) * SS, cy * SS],
                        radius=40 * SS, fill=(198, 226, 236, 92), outline=(236, 248, 252, 210),
                        width=int(4 * SS))
    for i in range(3):
        d.line([((cx - cw_ * 0.34 + i * 90) * SS, (cy - ch_ * 0.86) * SS),
                ((cx - cw_ * 0.16 + i * 90) * SS, (cy - ch_ * 0.1) * SS)],
               fill=(255, 255, 255, 60), width=int(18 * SS))
    cv.paste(lay, blur=1.5)
    # 内躺的公主
    place_princess(cv, cx, cy - 66, 300, rotate=88)
    # 鲜花
    flowers(cv, rng, cx - cw_ * 0.5, cx + cw_ * 0.5, cy - 96, cy - 20, 70, 1.1,
            [(244, 226, 236), (236, 190, 200), (250, 236, 200)])
    # 棺上玻璃再覆一层
    lay = layer()
    d = ImageDraw.Draw(lay)
    d.rounded_rectangle([(cx - cw_ / 2) * SS, (cy - ch_) * SS, (cx + cw_ / 2) * SS, cy * SS],
                        radius=40 * SS, fill=(214, 236, 244, 40), outline=(240, 250, 255, 170),
                        width=int(3 * SS))
    cv.paste(lay, blur=0.6)
    cv.glow(cx, cy - ch_ * 0.5, 640, (255, 244, 226), 0.35, shrink=6)
    # 七个小矮人 + 花
    hats = [(196, 88, 64), (86, 132, 84), (72, 104, 156), (156, 90, 140), (200, 148, 70),
            (108, 112, 136), (168, 70, 70)]
    beards = [(236, 232, 224), (200, 196, 188), (150, 116, 84), (222, 218, 210),
              (184, 180, 172), (120, 96, 76), (226, 222, 214)]
    for i, x in enumerate([0.08, 0.16, 0.24, 0.32]):
        dwarf(cv, W * x, H * 0.99, 300, hats[i], shade(hats[i], 1.2), beards[i], pose="hold")
    for i, x in enumerate([0.68, 0.76, 0.84, 0.93]):
        dwarf(cv, W * x, H * 0.99, 300, hats[i + 3], shade(hats[i + 3], 1.2), beards[i + 3], pose="hold")
    # 小鸟
    for x, y in [(W * 0.62, H * 0.3), (W * 0.68, H * 0.24), (W * 0.36, H * 0.26)]:
        bird(cv, x, y, 1.3, (70, 66, 82))


def s_kiss(cv, rng):
    sky(cv, (74, 92, 146), (255, 214, 176), mid=(186, 152, 164), horizon=0.45)
    sun(cv, W * 0.5, H * 0.34, 130, (255, 232, 186), 1.0)
    clouds(cv, rng, H * 0.2, 6, (255, 226, 210), 0.2, 1.4)
    # 光柱
    lay = layer()
    d = ImageDraw.Draw(lay)
    for i in range(7):
        a = -60 + i * 20
        d.polygon([(W * 0.5, H * 0.34), (W * 0.5 + math.cos(math.radians(a)) * 1400,
                                           H * 0.34 + math.sin(math.radians(a)) * 1400),
                   (W * 0.5 + math.cos(math.radians(a + 6)) * 1400,
                    H * 0.34 + math.sin(math.radians(a + 6)) * 1400)], fill=(255, 240, 200, 22))
    cv.paste(lay, blur=24)
    ridge(cv, rng, H * 0.66, H * 0.16, (152, 128, 146), seed=21)
    forest_band(cv, rng, H * 0.8, 320, (76, 72, 96), 16)
    ground(cv, H * 0.9, (86, 92, 78), rng, (104, 116, 88), 50)
    flowers(cv, rng, 0, W, H * 0.9, H, 120, 1.4)
    # 水晶棺（打开）
    cx, cy = W * 0.5, H * 0.88
    cv.rrect(cx - 470, cy - 6, cx + 470, cy + 54, 16, (116, 112, 118))
    lay = layer()
    d = ImageDraw.Draw(lay)
    d.rounded_rectangle([(cx - 430) * SS, (cy - 230) * SS, (cx + 430) * SS, cy * SS],
                        radius=36 * SS, fill=(206, 230, 240, 60), outline=(240, 250, 255, 190),
                        width=int(3 * SS))
    cv.paste(lay, blur=1)
    # 公主坐起
    place_princess(cv, W * 0.47, H * 0.9, 400)
    # 王子俯身
    prince(cv, W * 0.6, H * 0.95, 470)
    cv.glow(W * 0.53, H * 0.62, 560, (255, 236, 190), 0.5, shrink=6)
    # 苹果掉落
    apple(cv, W * 0.36, H * 0.94, 26)
    # 小矮人背景
    hats = [(196, 88, 64), (86, 132, 84), (72, 104, 156), (156, 90, 140), (200, 148, 70),
            (108, 112, 136), (168, 70, 70)]
    beards = [(236, 232, 224), (200, 196, 188), (150, 116, 84), (222, 218, 210),
              (184, 180, 172), (120, 96, 76), (226, 222, 214)]
    for i, x in enumerate([0.08, 0.15, 0.22, 0.29, 0.83, 0.9, 0.96]):
        dwarf(cv, W * x, H * 0.99, 240, hats[i], shade(hats[i], 1.2), beards[i], pose="up")
    for _ in range(26):
        x, y = rng.uniform(0, W), rng.uniform(H * 0.3, H * 0.85)
        r = rng.uniform(3, 7)
        cv.circle(x, y, r, (255, 250, 220))
        cv.glow(x, y, r * 8, (255, 244, 200), 0.5, shrink=8)


def s_ending(cv, rng):
    sky(cv, (96, 150, 210), (250, 224, 186), mid=(176, 198, 214), horizon=0.42)
    sun(cv, W * 0.18, H * 0.2, 92, (255, 240, 200), 0.9)
    clouds(cv, rng, H * 0.2, 7, (255, 255, 255), 0.22, 1.5)
    ridge(cv, rng, H * 0.56, H * 0.14, (150, 176, 176), seed=31, snow=(244, 248, 244), snow_h=0.55)
    forest_band(cv, rng, H * 0.72, 280, (98, 132, 108), 18)
    castle(cv, W * 0.76, H * 0.74, 0.52, (176, 186, 200), (128, 92, 112), (255, 228, 168), (196, 74, 80), 0.8)
    hill(cv, rng, H * 0.86, 30, (104, 148, 96), seed=11)
    ground(cv, H * 0.92, (110, 158, 96), rng, (132, 178, 108), 90)
    flowers(cv, rng, 0, W, H * 0.9, H, 150, 1.6)
    # 公主与王子
    place_princess(cv, W * 0.42, H * 0.95, 400)
    prince(cv, W * 0.54, H * 0.95, 480)
    cv.glow(W * 0.48, H * 0.8, 620, (255, 240, 200), 0.35, shrink=6)
    # 七个矮人跳舞
    hats = [(196, 88, 64), (86, 132, 84), (72, 104, 156), (156, 90, 140), (200, 148, 70),
            (108, 112, 136), (168, 70, 70)]
    beards = [(236, 232, 224), (200, 196, 188), (150, 116, 84), (222, 218, 210),
              (184, 180, 172), (120, 96, 76), (226, 222, 214)]
    for i, x in enumerate([0.06, 0.13, 0.2, 0.7, 0.78, 0.86, 0.94]):
        dwarf(cv, W * x, H * 0.98 + (10 if i % 2 else 0), 280, hats[i], shade(hats[i], 1.25),
              beards[i], pose="up" if i % 2 == 0 else "walk")
    # 蝴蝶
    for _ in range(10):
        x, y = rng.uniform(0, W), rng.uniform(H * 0.3, H * 0.8)
        c = rng.choice([(246, 180, 120), (236, 150, 170), (250, 226, 140)])
        cv.ellipse(x - 8, y - 4, 8, 5, c)
        cv.ellipse(x + 8, y - 4, 8, 5, c)
        cv.line([(x, y - 4), (x, y + 6)], (90, 70, 60), 1.5)
    for x, y in [(W * 0.3, H * 0.18), (W * 0.36, H * 0.14), (W * 0.64, H * 0.16)]:
        bird(cv, x, y, 1.2, (86, 92, 108))


def s_endcard(cv, rng):
    sky(cv, (16, 22, 48), (64, 56, 86), mid=(36, 34, 66), horizon=0.55)
    stars(cv, rng, 220, 0.7)
    moon(cv, W * 0.8, H * 0.24, 78, (250, 242, 214), 0.6)
    forest_band(cv, rng, H * 0.95, 420, (12, 16, 36), 18)
    hill(cv, rng, H * 0.97, 22, (8, 12, 30), seed=7)
    mist(cv, rng, H * 0.9, 140, (150, 160, 200), 0.12)
    fireflies(cv, rng, 26, 0.6, 0.95)
    # 睡在蘑菇下的小矮人
    dwarf(cv, W * 0.5, H * 0.97, 180, (18, 22, 44), (16, 20, 40), (26, 30, 52),
          skin=(20, 24, 46), shoes=(12, 16, 34), pose="waist")
    cv.glow(W * 0.5, H * 0.36, 760, (120, 140, 200), 0.3, shrink=6)
    cv.text(W * 0.5, H * 0.34, "故事讲完啦", 132, (250, 244, 226), "black", tracking=12)
    cv.text(W * 0.5, H * 0.45, "THE END", 40, (190, 202, 226), "regular", tracking=16)
    cv.line([(W * 0.5 - 260, H * 0.395), (W * 0.5 + 260, H * 0.395)], (198, 172, 122), 2)


PAINTERS = {
    "title": s_title, "mirror": s_mirror, "forest_run": s_forest_run,
    "cottage_dusk": s_cottage_dusk, "cottage_inside": s_cottage_inside,
    "dwarfs_home": s_dwarfs_home, "dwarfs_promise": s_dwarfs_promise,
    "witch_apple": s_witch_apple, "apple_fall": s_apple_fall, "sorrow": s_sorrow,
    "coffin_hill": s_coffin_hill, "kiss": s_kiss, "ending": s_ending, "endcard": s_endcard,
}


def render(name, seed=1, do_finish=True):
    cv = CV()
    rng = random.Random(seed)
    PAINTERS[name](cv, rng)
    if do_finish:
        cv.finish(seed=seed)
    return cv.img
