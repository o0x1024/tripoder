# -*- coding: utf-8 -*-
"""共用时间轴：给相邻镜头留出交叉溶接的重叠时间。"""
import json

XFADE = 0.9
FPS = 30
OUT_W, OUT_H = 1920, 1080


def load(path="audio/timeline.json"):
    d = json.load(open(path))
    scenes = d["scenes"]
    n = len(scenes)
    starts = []
    for i, s in enumerate(scenes):
        starts.append(max(0.0, s["start"] - XFADE * i))
    total = d["total"] - XFADE * (n - 1)
    for i, s in enumerate(scenes):
        s["vstart"] = starts[i]
        s["vend"] = starts[i] + s["duration"]
        if s.get("voice"):
            s["voice_at"] = s["voice_at"]
    return dict(scenes=scenes, total=total, xfade=XFADE)


if __name__ == "__main__":
    t = load()
    for s in t["scenes"]:
        print(f'{s["index"]:02d} {s["key"]:20s} {s["vstart"]:7.2f} -> {s["vend"]:7.2f}')
    print("total", round(t["total"], 2))
