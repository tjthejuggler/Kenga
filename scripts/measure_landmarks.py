#!/usr/bin/env python3
"""One-off: measure limb landmarks (alpha runs per row) from the body cutouts.

Prints, for selected rows, the opaque x-runs so arm/torso/leg/tail extents and
angles can be derived for the rotated zone overlays in KangarooBodyMap.kt.
Run manually; no API, no build integration.
"""
from __future__ import annotations

import numpy as np
from PIL import Image

ROOT = __import__("pathlib").Path(__file__).resolve().parent.parent

def runs(mask_row: np.ndarray, min_gap: int = 8, min_len: int = 12):
    xs = np.where(mask_row)[0]
    if xs.size == 0:
        return []
    out = []
    start = prev = xs[0]
    for x in xs[1:]:
        if x - prev > min_gap:
            if prev - start >= min_len:
                out.append((int(start), int(prev)))
            start = x
        prev = x
    if prev - start >= min_len:
        out.append((int(start), int(prev)))
    return out

def analyze(path, label):
    img = Image.open(path).convert("RGBA")
    a = np.array(img)[:, :, 3] > 40
    print(f"== {label} {img.size} ==")
    for y in range(40, 1500, 40):
        rr = runs(a[y])
        desc = " ".join(f"[{s}-{e}]" for s, e in rr)
        print(f"y={y:4d}  {desc}")

analyze(ROOT / "app/src/main/res/drawable-nodpi/kenga_body_front.png", "FRONT")
analyze(ROOT / "app/src/main/res/drawable-nodpi/kenga_body_back.png", "BACK")
