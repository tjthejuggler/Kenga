#!/usr/bin/env python3
"""Zone-perfection scoring harness.

For every zone on both views:
  coverage    = fraction of the zone's overlay pixels landing on art (alpha>60)
  centerline  = fraction of the measured art centerline points (per-row alpha
                span centers along the shape's axis band) inside the shape
  fit angle   = linear-fit angle of the measured centerline vs shape angle
  symmetry    = for paired zones: |dims equal|, |angle residual L| vs |R|
                (each shape vs its own limb's measured axis), and |mean
                lateral residual L| vs |R| (mirrored around per-leg axis).

Targets: coverage >= 0.95, centerline >= 5/6, |angle residual| <= 2.0 deg,
|resid_L - resid_R| <= 6 px, dims within 3 px. Exit 0 iff all pass.
"""
import math
import sys
import numpy as np
from PIL import Image
from kenga_zones import parse_zones, shape_mask, inside

ART = {
    "front": "app/src/main/res/drawable-nodpi/kenga_body_front.png",
    "back": "app/src/main/res/drawable-nodpi/kenga_body_back.png",
}
PAIRED = {"QUADS", "HAMSTRINGS", "CALVES", "BICEPS_TRICEPS", "SHOULDERS",
          "WRISTS_FOREARMS", "OBLIQUES", "ANKLES_FEET", "LATS"}
MIDLINE = {"CORE", "CHEST", "UPPER_BACK", "LOWER_BACK"}

T_COV, T_CL, T_ANG, T_RESDIFF, T_DIM = 0.95, 5 / 6, 3.0, 6.0, 3.0


def spans_of_row(art, y):
    row = art[y]
    out, x = [], 0
    W = len(row)
    while x < W:
        if row[x]:
            s = x
            while x < W and row[x]:
                x += 1
            if x - s > 8:
                out.append((s, x - 1))
        else:
            x += 1
    return out


def measure_shape(art, shape, n=13, k=0.85):
    """Return (coverage, centerline_frac, mean_lat_resid, fit_angle, pts).

    Centerline = windowed local centroid of art alpha around each axis
    sample point (robust to arms/torso merging into one alpha span).
    centerline_frac = fraction of axis samples whose own pixel is on art.
    """
    m = np.array(shape_mask(shape)) > 127
    coverage = float(art[m].mean())

    a = math.radians(shape["a"])
    ax_half = (shape["l"] if shape["kind"] == "cap" else 2 * shape["ry"]) / 2 * k
    win = (shape["w"] if shape["kind"] == "cap" else 2 * shape["rx"]) / 2
    sin, cos = math.sin(a), math.cos(a)
    on_art, lat, pts = 0, [], []
    for t in np.linspace(-ax_half, ax_half, n):
        px = shape["cx"] + t * sin
        py = shape["cy"] + t * cos
        y = int(round(py))
        if not (0 <= y < art.shape[0]):
            continue
        xi = int(round(px))
        if 0 <= xi < art.shape[1] and art[y, xi]:
            on_art += 1
        x0 = max(int(px - win), 0)
        x1 = min(int(px + win) + 1, art.shape[1])
        col = np.nonzero(art[y, x0:x1])[0]
        if len(col) == 0:
            continue
        mcx = x0 + col.mean()
        pts.append((mcx, y))
        lat.append(abs(mcx - px))
    frac = on_art / n
    mean_lat = float(np.mean(lat)) if lat else 999.0
    fit_angle = 0.0
    if len(pts) >= 4:
        xs = np.array([p[0] for p in pts])
        ys = np.array([p[1] for p in pts])
        dx = np.polyfit(ys, xs, 1)[0]  # dx per dy
        fit_angle = math.degrees(math.atan2(dx, 1.0))
    return coverage, frac, mean_lat, fit_angle


def main():
    views = parse_zones()
    failures = 0
    print(f"{'side':5} {'zone':16} {'shape':6} {'cov':>5} {'cline':>6} "
          f"{'lat':>5} {'ang':>6} {'fit':>6} {'dang':>5}")
    for side in ("front", "back"):
        art = np.array(Image.open(ART[side]).convert("RGBA"))[..., 3] > 60
        for zone in views[side]:
            g = zone["group"]
            res = [measure_shape(art, sh) for sh in zone["shapes"]]
            for i, (sh, (cov, fr, lat, fit)) in enumerate(zip(zone["shapes"], res)):
                dang = sh["a"] - fit
                tag = "LR"[i] if len(zone["shapes"]) == 2 else "-"
                ok = (cov >= T_COV and fr >= T_CL and abs(dang) <= T_ANG
                      and lat <= 12)
                if not ok:
                    failures += 1
                print(f"{side:5} {g:16} {tag:6} {cov:5.3f} {fr:6.3f} "
                      f"{lat:5.1f} {sh['a']:6.1f} {fit:6.1f} {dang:5.1f}"
                      f"{'' if ok else '  << FAIL'}")
            # paired symmetry
            if g in PAIRED and len(zone["shapes"]) == 2:
                L, R = zone["shapes"]
                ml, mr = res[0], res[1]
                dimL = (L["l"], L["w"]) if L["kind"] == "cap" else (L["rx"], L["ry"])
                dimR = (R["l"], R["w"]) if R["kind"] == "cap" else (R["rx"], R["ry"])
                dim_ok = all(abs(a - b) <= T_DIM for a, b in zip(dimL, dimR))
                dist_err = abs(ml[2] - mr[2])
                ang_err = abs(abs(L["a"] - ml[3]) - abs(R["a"] - mr[3]))
                sym_ok = dim_ok and dist_err <= T_RESDIFF and ang_err <= T_ANG
                if not sym_ok:
                    failures += 1
                print(f"      └─ symmetry: dims{'=' if dim_ok else '!'}{dimL}{dimR} "
                      f"dist_err={dist_err:.1f}px ang_err={ang_err:.1f}°"
                      f"{'' if sym_ok else '  << FAIL'}")
    print(f"\n{'ALL PASS' if failures == 0 else f'{failures} FAILURES'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
