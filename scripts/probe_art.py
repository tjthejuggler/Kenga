#!/usr/bin/env python3
"""Probe art alpha structure in problem regions."""
import numpy as np
from PIL import Image

for side in ("front", "back"):
    art = np.array(Image.open(
        f"app/src/main/res/drawable-nodpi/kenga_body_{side}.png").convert("RGBA"))[..., 3] > 60
    print(f"\n===== {side} =====")
    # 1. pelvic midline column x=400..430 at y 900-1200 every 10
    print("-- col x=415, y900-1200 (on-art?)")
    print(" ".join(f"{y}:{int(art[y,415])}" for y in range(900, 1201, 10)))
    # 2. row spans in bands
    for label, y0, y1 in [("obliques band", 720, 920), ("hips band", 950, 1080),
                          ("feet", 1330, 1470), ("shoulders/back", 500, 640),
                          ("lats", 580, 760), ("arms", 620, 920)]:
        print(f"-- {label} spans")
        for y in range(y0, y1, 20):
            row = art[y]
            sp, x = [], 0
            while x < 1000:
                if row[x]:
                    s = x
                    while x < 1000 and row[x]:
                        x += 1
                    if x - s > 6:
                        sp.append(f"[{s}-{x-1}]")
                else:
                    x += 1
            print(f"  y={y}: {' '.join(sp)}")
