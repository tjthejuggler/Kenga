#!/usr/bin/env python3
"""Empirically verify zone overlay placement from an adb screenshot.

Finds reddish overlay pixels (tier-0 dull red at ~0.62 alpha over the tan
kangaroo), clusters them, and prints each cluster's centroid + principal-axis
angle (deg from vertical, + = leans right going down) so placement can be
compared against the art limb measurements.
"""
import sys, math
from PIL import Image

im = Image.open(sys.argv[1]).convert("RGB")
W, H = im.size
px = im.load()
step = 4
pts = []
for y in range(0, H, step):
    for x in range(0, W, step):
        r, g, b = px[x, y]
        # dull red overlay: red dominant, muted
        if r > 50 and r - g > 22 and r - b > 22 and g < 120 and b < 120:
            pts.append((x, y))
print(f"{sys.argv[1]} {W}x{H}: {len(pts)} overlay px")

# grid cluster
cell = 30
grid = {}
for x, y in pts:
    grid.setdefault((x // cell, y // cell), []).append((x, y))
seen, clusters = set(), []
def flood(k):
    stack, comp = [k], []
    while stack:
        c = stack.pop()
        if c in seen or c not in grid: continue
        seen.add(c); comp += grid[c]
        for dx in (-1,0,1):
            for dy in (-1,0,1):
                stack.append((c[0]+dx, c[1]+dy))
    return comp
for k in list(grid):
    if k in seen: continue
    comp = flood(k)
    if len(comp) < 30: continue
    n = len(comp)
    mx = sum(p[0] for p in comp)/n; my = sum(p[1] for p in comp)/n
    sxx = sum((p[0]-mx)**2 for p in comp)/n
    syy = sum((p[1]-my)**2 for p in comp)/n
    sxy = sum((p[0]-mx)*(p[1]-my) for p in comp)/n
    # principal axis angle from vertical: covariance eigenvector
    theta = 0.5 * math.atan2(2*sxy, sxx - syy)  # axis angle from x-axis
    ang_from_vert = math.degrees(theta)  # convert: lean right going down => theta>0 => +
    print(f"cluster n={n:5d} c=({mx:.0f},{my:.0f}) w={math.sqrt(sxx)*2:.0f} h={math.sqrt(syy)*2:.0f} lean={ang_from_vert:+.1f}deg")
