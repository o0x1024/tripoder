# -*- coding: utf-8 -*-
"""并行渲染全部 14 张分镜插画。"""
import os
import sys
from multiprocessing import Pool

import art
from story import SCENES

OUT = "frames"


def job(args):
    i, name = args
    seed = 100 + i * 7
    img = art.render(name, seed=seed)
    path = os.path.join(OUT, f"scene_{i:02d}_{name}.png")
    img.save(path)
    return path


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    only = sys.argv[1:]
    tasks = [(i + 1, s["painter"]) for i, s in enumerate(SCENES)]
    if only:
        tasks = [t for t in tasks if t[1] in only]
    with Pool(4) as p:
        for path in p.imap_unordered(job, tasks):
            print("done", path, flush=True)
