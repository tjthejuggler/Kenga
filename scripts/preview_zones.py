#!/usr/bin/env python3
"""Render every zone exactly as Compose does (1000x1500 space, same rotate(-a)
draw convention) as semi-transparent red overlays on the art. Ground truth
without reinstalling the app."""
import sys
import numpy as np
from PIL import Image
from kenga_zones import parse_zones, shape_mask

ART = {
    "front": "app/src/main/res/drawable-nodpi/kenga_body_front.png",
    "back": "app/src/main/res/drawable-nodpi/kenga_body_back.png",
}
OUT = {"front": "shots/preview_front.png", "back": "shots/preview_back.png"}

for side, art_path in ART.items():
    art = Image.open(art_path).convert("RGBA")
    overlay = np.zeros((1500, 1000, 4), dtype=np.uint8)
    for zone in parse_zones()[side]:
        for shape in zone["shapes"]:
            m = np.array(shape_mask(shape), dtype=np.float32) / 255.0
            overlay[..., 0] += (m * 255).astype(np.uint8)
            overlay[..., 3] = np.maximum(overlay[..., 3], (m * 160).astype(np.uint8))
    ov = Image.fromarray(overlay, "RGBA")
    comp = Image.alpha_composite(art, ov)
    comp.save(OUT[side])
    print("wrote", OUT[side])
