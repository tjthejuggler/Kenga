#!/usr/bin/env python3
"""Build the Kenga in-app body assets + launcher icon from the PPQ raw art.

Art Upgrade pipeline (Octo make_icon_art.py / make_head_back_cutout.py pattern):

  1. BODY (docs/art/kenga_body_{front,back}_raw.png):
     - flood-fill-from-edges chroma key removes the flat pale-sand background
       (flood fill can't leak into the cream belly — it's enclosed by tan fur)
     - 2px feathered rim, drop stray off-figure specks
     - ALIGNMENT: scale the back figure so its alpha-bbox height matches the
       front's, then place both figures on one identical 2:3 canvas centered
       on each bbox's center → the figure stays in the EXACT same position
       across the front/back flip in KangarooBodyMap.
     - prints a per-row width profile + landmarks as fractions of the canvas
       so the Kotlin zone rects can be retuned to the real art proportions.

  2. ICON (docs/art/kenga_icon_raw.png, full-bleed ochre plate):
     - adaptive foreground 432px full-bleed PNG (Octo structure:
       drawable-nodpi/ic_launcher_foreground.png; the old vector
       drawable/ic_launcher_foreground.xml is deleted)
     - legacy mipmaps: square = full-bleed plate, round = circle mask
       (webp, all densities)

Outputs:
  - app/src/main/res/drawable-nodpi/kenga_body_front.png
  - app/src/main/res/drawable-nodpi/kenga_body_back.png
  - app/src/main/res/drawable-nodpi/ic_launcher_foreground.png
  - app/src/main/res/mipmap-*/ic_launcher{,_round}.webp
  - docs/art/kenga_body_{front,back}_cutout.png (transparent masters)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
ART = ROOT / "docs" / "art"

FG_SIZE = 432                        # 108dp adaptive canvas @ 4x
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG_TOL = 12.0                        # STRICT flood tolerance: bg corner std ≈1,
                                     # but the front cream belly is only dist
                                     # ≈17 from the sand bg — 12 keeps the belly
HALO_DIST = 34.0                     # pixels within HALO_BAND of true bg whose
HALO_BAND_PX = 3                     # color is still bg-ish get eroded (rim)
EDGE_BAND_PX = 2
EDGE_LO, EDGE_HI = 8.0, 34.0
KEEP_MIN_SIZE = 4000                 # px — figure ≫, specks ≪
BODY_W, BODY_H = 1000, 1500          # final canvas = Kotlin zone space exactly
FIG_FRAC = 0.94                      # figure height as fraction of canvas H


def corner_bg(a: np.ndarray) -> np.ndarray:
    corners = np.concatenate([
        a[:40, :40].reshape(-1, 3), a[:40, -40:].reshape(-1, 3),
        a[-40:, :40].reshape(-1, 3), a[-40:, -40:].reshape(-1, 3),
    ])
    return np.median(corners, axis=0)


def cutout(raw_path: Path, cut_path: Path) -> Image.Image:
    """Flood-fill-from-edges chroma key + feathered rim (Octo-derived)."""
    a = np.asarray(Image.open(raw_path).convert("RGB")).astype(np.float64)
    bg = corner_bg(a)
    dist = np.sqrt(((a - bg) ** 2).sum(axis=2))
    near_bg = dist < BG_TOL
    # Flood from the border with the STRICT tolerance: only bg CONNECTED to the
    # outside is removed — the cream belly (dist ≈17 > 12) survives even though
    # the A-pose arm gaps connect its surroundings to the outside background.
    outside = ndimage.binary_propagation(
        np.pad(near_bg[1:-1, 1:-1], 1, mode="constant", constant_values=True),
        mask=near_bg,
    )
    # Erode the anti-alias halo: pixels right next to true bg whose color is
    # still close to bg (blended rim) are background too.
    halo = ndimage.binary_dilation(outside, iterations=HALO_BAND_PX) & ~outside
    outside = outside | (halo & (dist < HALO_DIST))
    fg_seed = ~outside
    flabels, fn = ndimage.label(fg_seed)
    if fn > 1:
        sizes = ndimage.sum(fg_seed, flabels, index=range(1, fn + 1))
        keep = sizes >= KEEP_MIN_SIZE
        fg_seed = np.concatenate([[False], keep])[flabels]
    # Enclosed bg-colored pockets between limbs/tail: punch back out.
    solid = ndimage.binary_fill_holes(fg_seed)
    holes = solid & ~fg_seed
    hlabels, hn = ndimage.label(holes)
    removed = np.zeros_like(outside)
    if hn:
        hsizes = ndimage.sum(holes, hlabels, index=range(1, hn + 1))
        hmeans = ndimage.mean(dist, hlabels, index=range(1, hn + 1))
        for j in range(1, hn + 1):
            if hsizes[j - 1] >= 300 and hmeans[j - 1] < BG_TOL:
                removed |= hlabels == j
    opaque = solid & ~removed
    alpha = np.where(opaque, 255.0, 0.0)
    cut = outside | removed
    near = ndimage.binary_dilation(cut, iterations=EDGE_BAND_PX) & ~cut & opaque
    ramp = np.clip((dist - EDGE_LO) / (EDGE_HI - EDGE_LO), 0.0, 1.0)
    alpha[near] = 255.0 * ramp[near] ** 1.5
    out = Image.fromarray(np.dstack([a, alpha]).astype(np.uint8), "RGBA")
    out.save(cut_path)
    print(f"cutout {raw_path.name}: bg={bg.round(0)} -> {cut_path.name} "
          f"({out.width}x{out.height}, opaque={int((alpha > 128).sum())} px)")
    return out


def bbox_of(img: Image.Image) -> tuple[int, int, int, int]:
    b = img.split()[3].getbbox()
    if b is None:
        sys.exit(f"cutout of is empty — background removal failed")
    return b


def align_pair(front: Image.Image, back: Image.Image):
    """Scale the BACK figure to the front's bbox height, then composite both
    centered on identical BODY_W x BODY_H canvases."""
    fb = bbox_of(front)
    bb = bbox_of(back)
    fh = fb[3] - fb[1]
    bw = bb[2] - bb[0]
    bh = bb[3] - bb[1]
    target_h = int(fh * FIG_FRAC / FIG_FRAC)  # keep front's pixel height
    scale = fh / bh
    nw, nh = max(1, round(bw * scale)), max(1, round(bh * scale))
    back = back.crop(bb).resize((nw, nh), Image.LANCZOS)
    front_c = front.crop(fb)
    print(f"front bbox={fb} h={fh}; back bbox={bb} h={bh} -> resized {nw}x{nh} "
          f"(scale {scale:.3f})")

    def place(fig: Image.Image) -> Image.Image:
        canvas = Image.new("RGBA", (BODY_W, BODY_H), (0, 0, 0, 0))
        # Vertical: fill FIG_FRAC of the height, tiny top bias for the ears.
        th = int(BODY_H * FIG_FRAC)
        s = th / fig.height
        fig = fig.resize((max(1, round(fig.width * s)), th), Image.LANCZOS)
        canvas.alpha_composite(fig, ((BODY_W - fig.width) // 2,
                                     int((BODY_H - th) * 0.5)))
        return canvas

    return place(front_c), place(back)


def profile(canvas: Image.Image, name: str) -> None:
    """Print a per-row width profile + landmarks as canvas fractions so the
    Kotlin zone rects can be tuned to the real art."""
    a = np.asarray(canvas)
    alpha = a[..., 3] > 128
    rows = np.where(alpha.any(axis=1))[0]
    cols = np.where(alpha.any(axis=0))[0]
    print(f"\n── {name}: bbox x=[{cols.min()},{cols.max()}] "
          f"y=[{rows.min()},{rows.max()}] (canvas {BODY_W}x{BODY_H}) ──")
    # per-100px-row width + center
    for y in range(0, BODY_H, 100):
        band = alpha[y:y + 100]
        xs = np.where(band.any(axis=0))[0]
        if len(xs):
            w = xs.max() - xs.min()
            cx = (xs.min() + xs.max()) / 2
            print(f"  y {y:4d}-{y+99:4d} (frac {y/BODY_H:.2f}): width={w:4d} "
                  f"center_x={cx:6.0f}")


def make_icon() -> None:
    raw = Image.open(ART / "kenga_icon_raw.png").convert("RGB")
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)

    square = raw.resize((FG_SIZE, FG_SIZE), Image.LANCZOS)
    fg = square.copy().convert("RGBA")
    fg.save(nodpi / "ic_launcher_foreground.png")
    print(f"wrote {nodpi}/ic_launcher_foreground.png ({FG_SIZE}px full-bleed)")

    # Remove the old vector foreground so the PNG wins resource resolution.
    old = RES / "drawable" / "ic_launcher_foreground.xml"
    if old.exists():
        old.unlink()
        print(f"removed {old}")

    m = Image.new("L", square.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, FG_SIZE - 1, FG_SIZE - 1), fill=255)
    round_img = Image.new("RGBA", square.size, (0, 0, 0, 0))
    round_img.paste(square, (0, 0), m)
    for dpi, size in DENSITIES.items():
        folder = RES / f"mipmap-{dpi}"
        folder.mkdir(exist_ok=True)
        square.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher.webp")
        round_img.resize((size, size), Image.LANCZOS).save(
            folder / "ic_launcher_round.webp")
    print("wrote mipmaps:", ", ".join(sorted(DENSITIES)))


def main() -> int:
    front_raw = ART / "kenga_body_front_raw.png"
    back_raw = ART / "kenga_body_back_raw.png"
    icon_raw = ART / "kenga_icon_raw.png"
    for p in (front_raw, back_raw, icon_raw):
        if not p.exists():
            print(f"missing {p} — run gen_kanga_art.py / gen_kanga_icon.py first",
                  file=sys.stderr)
            return 2

    front = cutout(front_raw, ART / "kenga_body_front_cutout.png")
    back = cutout(back_raw, ART / "kenga_body_back_cutout.png")
    fc, bc = align_pair(front, back)

    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    fc.save(nodpi / "kenga_body_front.png")
    bc.save(nodpi / "kenga_body_back.png")
    print(f"wrote {nodpi}/kenga_body_front.png and kenga_body_back.png "
          f"({BODY_W}x{BODY_H}, pixel-aligned)")
    profile(fc, "FRONT")
    profile(bc, "BACK")

    make_icon()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
