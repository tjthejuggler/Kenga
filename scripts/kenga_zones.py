#!/usr/bin/env python3
"""Shared: parse zone geometry out of KangarooBodyMap.kt and rasterize masks
exactly as Compose draws them (upright shape rotated by angleDeg CCW around
its center — Compose draws rotate(-angleDeg), and Compose +rotation is
clockwise, so -angleDeg(clockwise) == +angleDeg CCW == PIL rotate(angle)).
"""
import math
import re
from PIL import Image, ImageDraw

KT_PATH = "app/src/main/java/com/example/kenga/ui/body/KangarooBodyMap.kt"

NUM = r"(-?[\d.]+)f"


def _shape_args(m, kind):
    vals = [float(g) for g in m.groups()[1:]]
    if kind == "cap":
        cx, cy, l, w = vals[:4]
        a = vals[4] if len(vals) > 4 else 0.0
        return {"kind": "cap", "cx": cx, "cy": cy, "l": l, "w": w, "a": a}
    cx, cy, rx, ry = vals[:4]
    a = vals[4] if len(vals) > 4 else 0.0
    return {"kind": "ell", "cx": cx, "cy": cy, "rx": rx, "ry": ry, "a": a}


SHAPE_RE = re.compile(rf"\b(cap|ell)\(\s*{NUM}\s*,\s*{NUM}\s*,\s*{NUM}\s*,\s*{NUM}(?:\s*,\s*{NUM})?\s*\)")
ZONE_HEAD_RE = re.compile(r"Zone\(\s*MuscleGroup\.(\w+)\s*,")


def _balanced(src, start):
    """Return substring of src starting at index of 'Zone(' through its
    matching close paren."""
    i = src.index("(", start)
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "(":
            depth += 1
        elif src[j] == ")":
            depth -= 1
            if depth == 0:
                return src[start:j + 1]
    raise ValueError("unbalanced")


def parse_zones():
    src = open(KT_PATH).read()

    def zones_in(text):
        zones = []
        for m in ZONE_HEAD_RE.finditer(text):
            zones.append(_parse_zone_body(_balanced(text, m.start())))
        return zones

    named = {}
    for m in re.finditer(r"private val (\w+)\s*=\s*", src):
        body = _balanced(src, m.end())
        named[m.group(1)] = zones_in(body)[0]

    views = {}
    for vm in re.finditer(r"val (frontZones|backZones): List<Zone> = listOf\(", src):
        side = "front" if "front" in vm.group(1) else "back"
        i = vm.end() - 1
        depth = 0
        for j in range(i, len(src)):
            if src[j] == "(":
                depth += 1
            elif src[j] == ")":
                depth -= 1
                if depth == 0:
                    break
        body = src[vm.end():j]
        zones = []
        for item in re.finditer(r"\b([A-Za-z]\w*)\b(?=\s*[,)\n])", body):
            tok = item.group(1)
            if tok in named:
                zones.append(named[tok])
        zones.extend(z for z in zones_in(body) if z not in named.values())
        views[side] = zones
    return views


def _parse_zone_body(body):
    group = ZONE_HEAD_RE.search(body).group(1)
    shapes = [_shape_args(sm, sm.group(1)) for sm in SHAPE_RE.finditer(body)]
    return {"group": group, "shapes": shapes}


def shape_mask(shape, size=(1000, 1500), scale=1.0):
    """RGBA overlay layer (red) with the shape drawn exactly like Compose."""
    W, H = size
    layer = Image.new("L", (int(W * scale), int(H * scale)), 0)
    d = ImageDraw.Draw(layer)
    cx, cy, a = shape["cx"] * scale, shape["cy"] * scale, shape["a"]
    if shape["kind"] == "cap":
        w, l = shape["w"] * scale, shape["l"] * scale
        d.rounded_rectangle(
            [cx - w / 2, cy - l / 2, cx + w / 2, cy + l / 2],
            radius=w / 2, fill=255)
    else:
        rx, ry = shape["rx"] * scale, shape["ry"] * scale
        d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=255)
    if abs(a) > 1e-6:
        layer = layer.rotate(a, center=(cx, cy), resample=Image.BILINEAR)
    return layer


def inside(shape, x, y):
    """Same rotated-frame test as KangarooBodyMap.kt ZoneShape.contains."""
    rad = math.radians(shape["a"])
    s, c = math.sin(rad), math.cos(rad)
    dx, dy = x - shape["cx"], y - shape["cy"]
    u = dx * s + dy * c
    v = dx * c - dy * s
    if shape["kind"] == "cap":
        hw = shape["w"] / 2
        straight = shape["l"] / 2 - hw
        if abs(u) <= straight:
            return abs(v) <= hw
        ux = max(abs(u) - straight, 0.0)
        return ux * ux + v * v <= hw * hw
    du, dv = u / shape["ry"], v / shape["rx"]
    return du * du + dv * dv <= 1.0


def art_arrays(path):
    im = Image.open(path).convert("RGBA")
    return im


def row_spans(alpha_row, thresh=60, min_w=8):
    out, x, W = [], 0, len(alpha_row)
    while x < W:
        if alpha_row[x] > thresh:
            s = x
            while x < W and alpha_row[x] > thresh:
                x += 1
            if x - s > min_w:
                out.append((s, x - 1))
        else:
            x += 1
    return out


if __name__ == "__main__":
    views = parse_zones()
    for side, zs in views.items():
        print(f"== {side}: {len(zs)} zones")
        for z in zs:
            print(" ", z["group"], z["shapes"])
