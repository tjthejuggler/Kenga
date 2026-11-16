#!/usr/bin/env python3
"""Measure limb axes (upper arm, forearm, thigh) from the body art alpha channel.

Art is 1000x1500 (check actual size; normalize if not). Prints per-row
left/right alpha spans in given y-bands so limb centerlines and angles can be
derived: for a limb band, the centerline x at each row -> linear fit angle.
"""
import sys
from PIL import Image

def analyze(path):
    im = Image.open(path).convert("RGBA")
    W, H = im.size
    px = im.load()
    print(f"== {path} {W}x{H}")
    sx, sy = 1000 / W, 1500 / H
    def spans(y):
        row = [1 if px[x, y][3] > 60 else 0 for x in range(W)]
        out = []
        x = 0
        while x < W:
            if row[x]:
                s = x
                while x < W and row[x]:
                    x += 1
                if x - s > 12:
                    out.append((s, x - 1))
            else:
                x += 1
        return out
    # sample rows every 40px
    for y in range(0, H, 40):
        sp = spans(y)
        desc = " ".join(f"[{s}-{e}]c{(s+e)//2}w{e-s}" for s, e in sp)
        print(f"y={int(y*sy):4d}: {desc}")

if __name__ == "__main__":
    for p in sys.argv[1:]:
        analyze(p)
