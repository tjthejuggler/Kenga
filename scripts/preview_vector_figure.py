#!/usr/bin/env python3
"""Preview the VECTOR kangaroo figure (Art Upgrade v5) off-device.

Ports KangarooBodyMap.kt's geometry + KangarooFigurePainter.kt's paint model
1:1 (same constants) and renders PNG previews to shots/. Pure iteration aid —
the Kotlin code stays the single source of truth; if you change geometry
there, mirror it here (constants are grouped identically).

Usage: python3 scripts/preview_vector_figure.py
Outputs: shots/vector_front_t{0,3,6}.png, shots/vector_back_t{0,6}.png
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "shots"

W, H = 1000, 1500
FUR = (199, 154, 102)
FUR_DARK = (93, 70, 50)
CREAM = (242, 228, 200)
INNER_EAR = (232, 178, 154)
FACE_DARK = (58, 42, 30)
OUTLINE = (74, 55, 40)
OUTLINE_W = 7

TIER_COLORS = {
    0: (156, 65, 65), 1: (255, 140, 59), 2: (79, 193, 92), 3: (77, 141, 247),
    4: (255, 110, 199), 5: (247, 201, 72), 6: (242, 242, 242),
}


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def cap(cx, cy, l, w, a=0.0):
    return ("cap", cx, cy, l, w, a)


def ell(cx, cy, rx, ry, a=0.0):
    return ("ell", cx, cy, rx, ry, a)


def scaled(s, ws, ls):
    if s[0] == "cap":
        return ("cap", s[1], s[2], s[3] * ls, s[4] * ws, s[5])
    return ("ell", s[1], s[2], s[3] * ws, s[4] * ws, s[5])


def shape_bbox(draw_scale, s):
    """Bounding box of the upright shape before rotation."""
    if s[0] == "cap":
        _, cx, cy, l, w, a = s
        return cx - w / 2, cy - l / 2, w, l
    _, cx, cy, rx, ry, a = s
    return cx - rx, cy - ry, rx * 2, ry * 2


class Fig:
    """Draws rotated shapes by rendering upright then rotating the patch."""

    def __init__(self):
        self.im = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    def _patch(self, s, fill, outline=None):
        kind, cx, cy = s[0], s[1], s[2]
        if kind == "cap":
            l, w = s[3], s[4]
        else:
            rx, ry = s[3], s[4]
            l, w = ry * 2, rx * 2
        ang = s[5]
        pad = OUTLINE_W * 2 + 4
        pw, ph = int(w + pad * 2), int(l + pad * 2)
        patch = Image.new("RGBA", (pw, ph), (0, 0, 0, 0))
        d = ImageDraw.Draw(patch)
        box = (pad, pad, pad + w - 1, pad + l - 1)
        if kind == "cap":
            d.rounded_rectangle(box, radius=w / 2, fill=fill)
            if outline:
                d.rounded_rectangle(box, radius=w / 2, outline=outline, width=OUTLINE_W)
        else:
            d.ellipse(box, fill=fill)
            if outline:
                d.ellipse(box, outline=outline, width=OUTLINE_W)
        patch = patch.rotate(-ang, resample=Image.BICUBIC, expand=True)
        self.im.alpha_composite(patch, (int(cx - patch.width / 2), int(cy - patch.height / 2)))

    def line(self, s, p1, p2, color, width):
        """Line in the shape's rotated frame around its center."""
        _, cx, cy = s[0], s[1], s[2]
        ang = s[5]
        rad = math.radians(-ang)
        cos_a, sin_a = math.cos(rad), math.sin(rad)

        def rot(x, y):
            dx, dy = x - cx, y - cy
            return (cx + dx * cos_a - dy * sin_a, cy + dx * sin_a + dy * cos_a)

        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        d.line([rot(*p1), rot(*p2)], fill=color + (255,), width=width)
        # clip to the shape
        mask = Image.new("L", (W, H), 0)
        md = ImageDraw.Draw(mask)
        self._mask_shape(md, s)
        self.im.paste(layer, (0, 0), Image.composite(mask, Image.new("L", (W, H), 0), layer.split()[3]))

    def _mask_shape(self, d, s):
        if s[0] == "cap":
            _, cx, cy, l, w, a = s
            # approximate: upright mask rotated via patch is complex; use
            # upright mask at angle by drawing rotated rounded rect is not
            # supported -> skip precise clip (preview aid only).
            d.rounded_rectangle([cx -w/2, cy-l/2, cx+w/2, cy+l/2], radius=w/2, fill=255)
        else:
            _, cx, cy, rx, ry, a = s
            d.ellipse([cx-rx, cy-ry, cx+rx, cy+ry], fill=255)

    def hline(self, s, x1, x2, y, color, width):
        self.line(s, (x1, y), (x2, y), color, width)


def detail_color(base):
    return mix(base, (58, 42, 30), 0.45)


def highlight_color(base, count):
    return mix(base, (255, 255, 255), 0.30 + 0.04 * min(count, 6))


def tail_shapes():
    p0, p1, p2 = (592, 1005), (795, 1150), (872, 1425)
    out = []
    for i in range(15):
        t = i / 14
        u = 1 - t
        x = u*u*p0[0] + 2*u*t*p1[0] + t*t*p2[0]
        y = u*u*p0[1] + 2*u*t*p1[1] + t*t*p2[1]
        r = 50 + (15 - 50) * t
        out.append(ell(x, y, r, r))
    return out


BODY_SILHOUETTE = [
    cap(500, 730, 560, 215, 0), ell(500, 990, 130, 120),
    cap(416, 1150, 365, 118, -4), cap(584, 1150, 365, 118, 4),
    cap(322, 650, 340, 92, -17), cap(678, 650, 340, 92, 17),
]

FRONT_ZONES = [
    ("quads", [cap(418, 1078, 210, 92, -6), cap(578, 1078, 210, 92, 5)], "stripes"),
    ("ankles_feet", [ell(360, 1370, 62, 36, -28), ell(640, 1370, 62, 36, 28)], None),
    ("hips_glutes", [cap(500, 990, 240, 90, 0)], None),
    ("obliques", [cap(432, 745, 122, 40, -10), cap(568, 745, 122, 40, 10)], "split"),
    ("core", [cap(500, 750, 172, 132, 0)], "six"),
    ("groin_adductors", [ell(500, 982, 26, 20, 0)], None),
    ("chest", [cap(500, 565, 225, 185, 0)], "pec"),
    ("shoulders", [ell(395, 478, 56, 48, -16), ell(605, 478, 56, 48, 16)], "split"),
    ("biceps_triceps", [cap(342, 562, 180, 58, -21), cap(658, 562, 180, 58, 21)], "stripes"),
    ("wrists_forearms", [cap(291, 720, 155, 48, -14), cap(709, 720, 155, 48, 14)], "split"),
]

BACK_ZONES = [
    ("hamstrings", [cap(422, 1130, 200, 88, -5), cap(578, 1130, 200, 88, 5)], "stripes"),
    ("calves", [cap(412, 1285, 140, 58, -3), cap(588, 1287, 140, 58, 4)], "stripes"),
    ("ankles_feet", [ell(383, 1380, 55, 36, -20), ell(617, 1380, 55, 36, 20)], None),
    ("hips_glutes", [ell(438, 995, 64, 72, 0), ell(562, 995, 64, 72, 0)], None),
    ("obliques", [cap(432, 760, 120, 40, -10), cap(568, 760, 120, 40, 10)], "split"),
    ("lower_back", [cap(500, 810, 160, 140, 0)], "split"),
    ("lats", [cap(430, 690, 140, 72, -6), cap(570, 690, 140, 72, 6)], "split"),
    ("upper_back", [cap(500, 560, 210, 190, 0)], "split"),
    ("shoulders", [ell(392, 478, 55, 48, -16), ell(608, 478, 55, 48, 16)], "split"),
    ("biceps_triceps", [cap(342, 562, 180, 58, -21), cap(658, 562, 180, 58, 21)], "stripes"),
    ("wrists_forearms", [cap(291, 720, 155, 48, -14), cap(709, 720, 155, 48, 14)], "split"),
]

FRONT_NEUTRALS = [
    (tail_shapes(), FUR, True),
    (BODY_SILHOUETTE, FUR, True),
    ([cap(404, 1252, 150, 54, -3), cap(596, 1254, 150, 54, 4)], FUR, True),
    ([ell(268, 826, 27, 25), ell(732, 826, 27, 25)], FUR, True),
    ([cap(436, 168, 185, 46, -10), cap(564, 168, 185, 46, 10)], FUR, True),
    ([cap(438, 172, 135, 22, -10), cap(562, 172, 135, 22, 10)], INNER_EAR, False),
    ([cap(500, 402, 110, 84)], FUR, True),
    ([ell(500, 298, 92, 88)], FUR, True),
    ([ell(500, 350, 58, 50)], CREAM, True),
    ([ell(500, 316, 15, 11)], FACE_DARK, False),
    ([ell(464, 276, 11, 11), ell(536, 276, 11, 11)], FACE_DARK, False),
    ([ell(468, 271, 4, 4), ell(540, 271, 4, 4)], (255, 255, 255), False),
    ([cap(500, 357, 30, 7)], FACE_DARK, False),
]

BACK_NEUTRALS = [
    (tail_shapes(), FUR, True),
    (BODY_SILHOUETTE, FUR, True),
    ([ell(268, 826, 27, 25), ell(732, 826, 27, 25)], FUR, True),
    ([cap(436, 168, 185, 46, -10), cap(564, 168, 185, 46, 10)], FUR, True),
    ([cap(500, 402, 110, 84)], FUR, True),
    ([ell(500, 298, 92, 88)], FUR, True),
]


def half_extent_x(s):
    """Horizontal half-extent of a (possibly rotated) shape (mirrors Kotlin)."""
    a = math.radians(s[5])
    sn, cs = abs(math.sin(a)), abs(math.cos(a))
    if s[0] == "cap":
        l, w = s[3], s[4]
        return (l - w) / 2 * sn + w / 2
    rx, ry = s[3], s[4]
    return math.sqrt((rx * cs) ** 2 + (ry * sn) ** 2)


def centering_offset():
    """X translation centering the figure's union bbox (mirrors Kotlin:
    keeps him in the SAME spot across the rotationY front/back flip)."""
    ws, ls = 1 + 0.07 * 6, 1 + 0.015 * 6
    shapes = []
    for zones_ in (FRONT_ZONES, BACK_ZONES):
        for _, ss, _st in zones_:
            shapes += [scaled(s, ws, ls) for s in ss]
    for neutrals_ in (FRONT_NEUTRALS, BACK_NEUTRALS):
        for ss, _f, _o in neutrals_:
            shapes += ss
    lo = min(s[1] - half_extent_x(s) for s in shapes)
    hi = max(s[1] + half_extent_x(s) for s in shapes)
    return W / 2 - (lo + hi) / 2


def render(side: str, counts: dict, path: Path):
    fig = Fig()
    fig.dx = centering_offset()
    zones = FRONT_ZONES if side == "front" else BACK_ZONES
    neutrals = FRONT_NEUTRALS if side == "front" else BACK_NEUTRALS

    for shapes, fill, outlined in neutrals:
        for s in shapes:
            s = (s[0], s[1] + fig.dx, s[2], s[3], s[4], s[5])
            fig._patch(s, fill + (255,), OUTLINE + (255,) if outlined else None)

    for group, shapes, style in zones:
        count = counts.get(group, 0)
        base = TIER_COLORS[min(count, 6)]
        ws = 1 + 0.07 * min(count, 6)
        ls = 1 + 0.015 * min(count, 6)
        for s in shapes:
            s = (s[0], s[1] + fig.dx, s[2], s[3], s[4], s[5])
            s2 = scaled(s, ws, ls)
            fig._patch(s2, base + (255,), OUTLINE + (255,))
            if count > 0:
                # highlight
                if s2[0] == "cap":
                    _, cx, cy, l, w, a = s2
                    hw = w * 0.34
                    hl = cap(cx - w * 0.08, cy - l / 2 + w * 0.18 - (l - w * 0.42) / 2 + (l - w * 0.42) / 2,
                             l - w * 0.42, hw, a)
                    # center the highlight capsule at (cx - w*0.08, cy) approx
                    hl = cap(cx - w * 0.08, cy - l * 0.02, l - w * 0.42, hw, a)
                    fig._patch(hl, highlight_color(base, count) + (255,), None)
                else:
                    _, cx, cy, rx, ry, a = s2
                    fig._patch(ell(cx - rx * 0.31, cy - ry * 0.36, rx * 0.31, ry * 0.30, a),
                               highlight_color(base, count) + (255,), None)
                # detail
                level = 3 if count >= 5 else 2 if count >= 3 else 1 if count >= 1 else 0
                if style and level > 0:
                    crease = detail_color(base)
                    if s2[0] == "cap":
                        _, cx, cy, l, w, a = s2
                        halfW, halfL = w / 2, l / 2
                        if style in ("split", "stripes"):
                            lines = 2 if level >= 2 else 1
                            for i in range(1, lines + 1):
                                x = cx + (0 if lines == 1 else (-halfW * 0.45 if i == 1 else halfW * 0.45))
                                fig.line(s2, (x, cy - halfL + halfW * 0.7),
                                         (x, cy + halfL - halfW * 0.7), crease, 5)
                            if level >= 3 and style == "stripes":
                                y = cy - halfL + halfW
                                while y < cy + halfL - halfW * 0.6:
                                    fig.hline(s2, cx - halfW * 0.72, cx + halfW * 0.72, y,
                                              mix(crease, (255, 255, 255), 0.0), 4)
                                    y += 38
                        elif style == "six":
                            rows = 3 if level >= 3 else 2 if level >= 2 else 1
                            for r in range(rows):
                                for c in range(2):
                                    cw = w * 0.36
                                    cl = (l * 0.72) / rows
                                    x = cx + (-cw * 0.55 if c == 0 else cw * 0.55)
                                    y = cy - l * 0.36 + r * cl + cl / 2
                                    fig._patch_inner_rect(s2, x, y, cw, cl * 0.76, crease)
                        elif style == "pec":
                            fig.line(s2, (cx, cy - halfL * 0.7), (cx, cy + halfL * 0.5), crease, 5)
                            if level >= 2:
                                for d2 in (-1, 1):
                                    fig.line(s2, (cx + d2 * halfW * 0.5, cy - halfL * 0.5),
                                             (cx + d2 * halfW * 0.5, cy + halfL * 0.3), crease, 5)
                    else:
                        _, cx, cy, rx, ry, a = s2
                        if level >= 2:
                            fig.line(s2, (cx - rx * 0.6, cy), (cx + rx * 0.6, cy), crease, 4)

    fig.im.save(path)
    print("wrote", path)


def _patch_inner_rect(self, s, x, y, w, h, color):
    """Rounded-rect outline inside the shape's rotated frame (six-pack cells)."""
    _, cx, cy = s[0], s[1], s[2]
    ang = s[5]
    rad = math.radians(-ang)
    cos_a, sin_a = math.cos(rad), math.sin(rad)

    def rot(px, py):
        dx, dy = px - cx, py - cy
        return (cx + dx * cos_a - dy * sin_a, cy + dx * sin_a + dy * cos_a)

    corners = [(x - w / 2, y - h / 2), (x + w / 2, y - h / 2),
               (x + w / 2, y + h / 2), (x - w / 2, y + h / 2)]
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.polygon([rot(*c) for c in corners], outline=color + (255,), width=5)
    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    self._mask_shape(md, s)
    self.im.paste(layer, (0, 0), Image.composite(mask, Image.new("L", (W, H), 0), layer.split()[3]))


Fig._patch_inner_rect = _patch_inner_rect


def main():
    OUT.mkdir(exist_ok=True)
    render("front", {}, OUT / "vector_front_t0.png")
    render("front", {"chest": 3, "core": 3, "quads": 3, "biceps_triceps": 3}, OUT / "vector_front_t3.png")
    render("front", {z[0]: 6 for z in FRONT_ZONES}, OUT / "vector_front_t6.png")
    render("back", {}, OUT / "vector_back_t0.png")
    render("back", {z[0]: 6 for z in BACK_ZONES}, OUT / "vector_back_t6.png")


if __name__ == "__main__":
    main()
