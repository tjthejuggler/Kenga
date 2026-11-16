#!/usr/bin/env python3
"""Turn the raw PPQ nav-glyph plates into tintable 96px Android drawables.

Each raw plate is a white glyph on a near-black background (per the prompt).
Post-pass:
  1. luminance threshold → binary glyph mask (kills the dark background and
     any faint texture; the glyph is the ONLY bright thing in frame)
  2. keep the largest connected component + anything >2% of it (drops specks)
  3. trim to the mask bbox, pad to square
  4. set every opaque pixel to pure WHITE RGB, keep the mask as alpha → a
     monochrome glyph the Compose bottom bar tints via Material3 colors
  5. resize (LANCZOS) to 96×96 PNG into app/src/main/res/drawable/

Run manually after scripts/gen_nav_icons.py; never part of any build.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

KENGA_ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = KENGA_ROOT / "docs" / "art" / "nav"
OUT_DIR = KENGA_ROOT / "app" / "src" / "main" / "res" / "drawable"

OUT_SIZE = 96
LUMA_T = 110  # glyph is white-on-black; anything this bright is glyph


def largest_components(mask: np.ndarray) -> np.ndarray:
    lbl, n = ndimage.label(mask)
    if n == 0:
        return mask
    sizes = ndimage.sum(mask, lbl, range(1, n + 1))
    keep = sizes >= max(sizes) * 0.02
    out = np.zeros_like(mask)
    for i, k in enumerate(keep, start=1):
        if k:
            out |= lbl == i
    return out


def process(name: str) -> None:
    raw_path = RAW_DIR / f"{name}_raw.png"
    img = Image.open(raw_path).convert("RGB")
    a = np.asarray(img).astype(np.int16)
    luma = a @ [299, 587, 114] // 1000
    mask = luma > LUMA_T
    mask = largest_components(mask)

    ys, xs = np.where(mask)
    if xs.size == 0:
        raise RuntimeError(f"{name}: empty mask after threshold")
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    m = mask[y0 : y1 + 1, x0 : x1 + 1]

    # Pad to square with a 6% margin so the glyph never touches the edge.
    h, w = m.shape
    side = int(max(h, w) * 1.12) + 2
    sq = np.zeros((side, side), dtype=bool)
    sq[(side - h) // 2 : (side - h) // 2 + h, (side - w) // 2 : (side - w) // 2 + w] = m

    # Tintable monochrome: white RGB, mask → alpha.
    out = np.zeros((side, side, 4), dtype=np.uint8)
    out[..., 0:3] = 255
    out[..., 3] = sq.astype(np.uint8) * 255
    png = Image.fromarray(out, "RGBA").resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)
    out_path = OUT_DIR / f"ic_{name.replace('nav_', 'nav_')}.png"
    png.save(out_path)
    print(f"{name}: glyph {w}x{h} -> {out_path}")


def main() -> int:
    for name in ("nav_body", "nav_library", "nav_log", "nav_stats", "nav_settings"):
        process(name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
