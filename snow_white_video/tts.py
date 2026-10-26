# -*- coding: utf-8 -*-
"""逐句合成旁白（macOS say / Tingting），拼接成每场配音并输出时间轴。"""
import json
import os
import re
import subprocess
import wave

import numpy as np

from story import SCENES

SR = 44100
VOICE = "Tingting"
RATE_NARR = 152
RATE_QUOTE = 140
LEAD = 1.0     # 场景开始到人声
TAIL = 1.0     # 人声结束到场景切换
GAP = 0.34     # 句间停顿
MIN_DUR = 6.0

OUT = "audio"
os.makedirs(OUT, exist_ok=True)


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getnchannels() == 1, w.getnchannels()
        n = w.getnframes()
        data = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768.0
        return data


def synth(text, rate, path):
    subprocess.run(["say", "-v", VOICE, "-r", str(rate), "--data-format=LEI16@44100",
                    "-o", path, text], check=True)
    return read_wav(path)


def split_sentences(text):
    parts = re.split(r"(?<=[。！？])", text)
    return [p.strip() for p in parts if p.strip()]


def db(x):
    return 20 * np.log10(max(1e-9, float(np.sqrt(np.mean(x ** 2)))))


def main():
    timeline = []
    t = 0.0
    tmpdir = os.path.join(OUT, "sent")
    os.makedirs(tmpdir, exist_ok=True)

    for i, sc in enumerate(SCENES):
        key = sc["key"]
        narration = sc.get("narration", "").strip()
        fade_in = 1.6 if i == 0 else 0.0
        if not narration:
            dur = max(MIN_DUR, sc.get("hold", 5.0) + fade_in)
            timeline.append(dict(index=i, key=key, kind=sc["painter"], motion=sc["motion"],
                                 subtitle=sc["subtitle"], start=round(t, 3),
                                 duration=round(dur, 3), voice=None, voice_at=0.0))
            t += dur
            continue

        sents = split_sentences(narration)
        chunks = []
        for j, s in enumerate(sents):
            rate = RATE_QUOTE if ("：" in s or s.startswith("「")) else RATE_NARR
            p = os.path.join(tmpdir, f"{key}_{j:02d}.wav")
            a = synth(s, rate, p)
            # 去除首尾静音
            thr = 0.004
            idx = np.where(np.abs(a) > thr)[0]
            if len(idx) > 0:
                a = a[max(0, idx[0] - 1000): min(len(a), idx[-1] + 1500)]
            chunks.append(a)
            chunks.append(np.zeros(int(GAP * SR), np.float32) if j < len(sents) - 1 else None)
        voice = np.concatenate([c for c in chunks if c is not None])
        voice = voice / max(1e-6, np.abs(voice).max()) * 0.82
        vp = os.path.join(OUT, f"narr_{i:02d}_{key}.wav")
        with wave.open(vp, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((voice * 32767).astype(np.int16).tobytes())
        vdur = len(voice) / SR
        dur = max(MIN_DUR, LEAD + vdur + TAIL)
        timeline.append(dict(index=i, key=key, kind=sc["painter"], motion=sc["motion"],
                             subtitle=sc["subtitle"], start=round(t, 3),
                             duration=round(dur, 3), voice=vp, voice_at=round(LEAD, 3),
                             voice_dur=round(vdur, 3)))
        print(f"{i:02d} {key:22s} voice={vdur:5.2f}s scene={dur:5.2f}s")
        t += dur

    with open(os.path.join(OUT, "timeline.json"), "w") as f:
        json.dump(dict(total=round(t, 3), scenes=timeline), f, ensure_ascii=False, indent=1)
    print("total", round(t, 2), "s")


if __name__ == "__main__":
    main()
