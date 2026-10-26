# -*- coding: utf-8 -*-
"""生成八音盒风格的配乐，并与旁白混音输出 audio/final.wav。"""
import json
import wave

import numpy as np

SR = 44100
rng = np.random.default_rng(20240521)

# ---------- 音色 ----------
def pluck(f, dur, amp=1.0, tau=0.95):
    n = int(dur * SR)
    t = np.arange(n) / SR
    env = np.exp(-t / tau)
    a = np.sin(2 * np.pi * f * t) * env
    a += 0.42 * np.sin(2 * np.pi * 2 * f * t + 0.3) * np.exp(-t / (tau * 0.55))
    a += 0.16 * np.sin(2 * np.pi * 3.01 * f * t + 0.9) * np.exp(-t / (tau * 0.32))
    a += 0.05 * np.sin(2 * np.pi * 4.98 * f * t) * np.exp(-t / (tau * 0.2))
    atk = int(0.004 * SR)
    a[:atk] *= np.linspace(0, 1, atk)
    return a * amp


def pad(f, dur, amp=1.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    a = np.zeros(n)
    for det, g in [(-0.12, 0.4), (0.0, 0.55), (0.13, 0.4)]:
        a += g * np.sin(2 * np.pi * (f + det) * t)
    a += 0.18 * np.sin(2 * np.pi * 2 * f * t)
    env = np.minimum(1, t / 1.4) * np.minimum(1, (dur - t) / 1.8)
    return a * amp * np.clip(env, 0, 1)


def add_at(buf, x, pos, gain=1.0, pan=0.0):
    """把单声道信号按 pan(-1..1) 写入立体声缓冲。"""
    i = int(pos * SR)
    if i < 0 or i >= buf.shape[1]:
        return
    n = min(len(x), buf.shape[1] - i)
    if n <= 0:
        return
    l = np.sqrt(0.5 * (1 - pan))
    r = np.sqrt(0.5 * (1 + pan))
    buf[0, i:i + n] += x[:n] * gain * l * 1.414
    buf[1, i:i + n] += x[:n] * gain * r * 1.414


def multitap(x, taps, wet=0.3):
    out = np.copy(x)
    for d, g in taps:
        k = int(d * SR)
        if k <= 0 or k >= x.shape[-1]:
            continue
        out[..., k:] += x[..., :-k] * g * wet
    return out


# ---------- 曲式 ----------
NOTE = {"A2": 110.00, "C3": 130.81, "D3": 146.83, "E3": 164.81, "F3": 174.61,
        "G3": 196.00, "A3": 220.00}

CHORDS = [
    # (低音, [琶音音高])
    ("A2", [220.00, 261.63, 329.63, 440.00]),          # Am
    ("F3", [174.61, 220.00, 261.63, 349.23]),          # F
    ("C3", [261.63, 329.63, 392.00, 523.25]),          # C
    ("G3", [196.00, 246.94, 293.66, 392.00]),          # G
]

TEMPO = 62.0
BEAT = 60.0 / TEMPO
BAR = BEAT * 4


def build_music(total):
    n = int((total + 3.0) * SR)
    buf = np.zeros((2, n), np.float32)

    # 琶音：八分音符
    eigth = BEAT / 2
    pattern = [0, 1, 2, 3, 2, 1, 2, 3]
    t = 1.2
    bar_i = 0
    while t < total + 0.5:
        bass, notes = CHORDS[bar_i % len(CHORDS)]
        # 低音
        add_at(buf, pad(NOTE[bass] / 2, BAR * 1.1, 0.30), t, 0.55, 0.0)
        add_at(buf, pad(notes[1] / 2, BAR * 0.9, 0.16), t, 0.5, 0.0)
        for k in range(8):
            idx = pattern[k]
            f = notes[idx]
            vel = 0.30 if k % 4 == 0 else (0.20 if k % 2 == 0 else 0.14)
            vel *= 0.85 + 0.15 * rng.random()
            pan = (-0.35, 0.0, 0.35, 0.15)[k % 4]
            add_at(buf, pluck(f, 2.4, vel, tau=0.85), t + k * eigth, 1.0, pan)
            # 高八度泛音点缀
            if k in (2, 6) and rng.random() < 0.7:
                add_at(buf, pluck(f * 2, 1.8, vel * 0.35, tau=0.55), t + k * eigth + 0.02, 1.0, -pan)
        t += BAR
        bar_i += 1

    # 低音铺底（长音，弱）
    for k in range(int(total / (BAR * 2)) + 1):
        bass = CHORDS[k % len(CHORDS)][0]
        add_at(buf, pad(NOTE[bass] / 4, BAR * 2.2, 0.22), 0.5 + k * BAR * 2, 0.45, 0.0)

    taps = [(0.031, 0.55), (0.047, 0.45), (0.071, 0.36), (0.103, 0.28),
            (0.137, 0.22), (0.191, 0.16), (0.253, 0.11)]
    buf = multitap(buf, taps, wet=0.55)
    # 简易低通，去掉毛刺
    k = 9
    ker = np.ones(k) / k
    for c in range(2):
        buf[c] = np.convolve(buf[c], ker, mode="same").astype(np.float32)

    # 头尾淡入淡出
    fi, fo = int(3.0 * SR), int(5.0 * SR)
    env = np.ones(n, np.float32)
    env[:fi] = np.linspace(0, 1, fi) ** 1.5
    env[-fo:] = np.linspace(1, 0, fo) ** 1.2
    buf *= env

    peak = float(np.abs(buf).max())
    return buf * (0.5 / max(1e-6, peak))


def load_mono(path):
    with wave.open(path, "rb") as w:
        n = w.getnframes()
        a = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768.0
    return a


def main():
    import film
    tl = film.load()
    total = tl["total"]
    scenes = tl["scenes"]
    music = build_music(total)

    voice = np.zeros((2, music.shape[1]), np.float32)
    act = np.zeros(music.shape[1], np.float32)
    for sc in scenes:
        if not sc.get("voice"):
            continue
        a = load_mono(sc["voice"])
        # 轻微空间感
        aa = multitap(a, [(0.021, 0.30), (0.037, 0.22), (0.061, 0.15)], wet=0.5)
        pos = int((sc["vstart"] + sc["voice_at"]) * SR)
        n = min(len(aa), music.shape[1] - pos)
        voice[0, pos:pos + n] += aa[:n]
        voice[1, pos:pos + n] += aa[:n]
        act[pos:pos + n] = 1.0

    # 人声存在时压低音乐（平滑包络）
    k = int(0.35 * SR)
    ker = np.ones(k, np.float32) / k
    act_s = np.convolve(act, ker, mode="same")
    duck = 1.0 - 0.55 * np.clip(act_s * 1.4, 0, 1)

    mix = voice * 0.95 + music * duck * 0.42
    mix = np.tanh(mix * 1.05) * 0.92

    peak = float(np.abs(mix).max())
    mix *= 0.95 / max(1e-6, peak)

    out = (np.clip(mix, -1, 1) * 32767).astype(np.int16).T.copy()
    with wave.open("audio/final.wav", "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(out.tobytes())
    print("final.wav", round(out.shape[0] / SR, 2), "s",
          "peak", round(float(np.abs(mix).max()), 3),
          "rms dB", round(20 * np.log10(np.sqrt(np.mean(mix ** 2)) + 1e-9), 1))


if __name__ == "__main__":
    main()
