#!/usr/bin/env python3
"""Register zone geometry to a screenshot's red overlay blob and check
per-limb alignment against measured art limb centerlines.

Usage: verify_align.py shot.png
"""
import sys, math
from PIL import Image

# --- geometry port (cx, cy, l, w, angle) / ellipse (cx, cy, rx, ry, angle) ---
CAPS = {
 ("front","quads"): [("c",316,1100,200,90,0),("c",537,1095,180,90,13)],
 ("front","obliques"): [("c",288,822,182,66,4),("c",544,822,182,66,-4)],
 ("front","biceps"): [("c",211,712,160,86,-21),("c",617,712,160,86,21)],
 ("front","wrists"): [("c",176,862,140,62,-11),("c",652,862,140,62,11)],
 ("front","shoulders"): [("e",293,543,74,52,-18),("e",534,543,74,52,18)],
 ("front","ankles"): [("e",252,1405,112,46,-16),("e",578,1398,118,46,13)],
 ("back","biceps"): [("c",251,690,150,60,-13),("c",546,690,150,60,14)],
 ("back","wrists"): [("c",223,862,130,46,-2),("c",578,862,130,46,1)],
 ("back","shoulders"): [("e",258,585,60,48,-13),("e",540,585,60,48,14)],
 ("back","hamstrings"): [("c",316,1100,210,84,0),("c",490,1100,220,84,16)],
 ("back","calves"): [("c",320,1302,158,62,0),("c",487,1332,168,62,2)],
}

# art limb centerlines measured via alpha profiles: (view, limb, side) -> list of (x,y)
ART = {
 ("front","biceps","L"): [(234,680),(214,720),(203,760),(188,800)],
 ("front","biceps","R"): [(595,680),(614,720),(625,760),(640,800),(653,840)],
 ("front","wrists","L"): [(188,800),(176,880),(165,920)],
 ("front","wrists","R"): [(640,800),(653,880),(664,920)],
 ("front","quads","L"): [(320,1000),(317,1040),(314,1080),(313,1120),(317,1160),(317,1200)],
 ("front","quads","R"): [(513,1000),(524,1040),(539,1080),(551,1120),(558,1160),(514,1200)],
 ("back","biceps","L"): [(270,600),(254,640),(247,680),(241,720),(232,760)],
 ("back","biceps","R"): [(531,600),(546,640),(555,680),(561,720),(570,760)],
 ("back","wrists","L"): [(225,800),(219,840),(219,880),(222,920)],
 ("back","wrists","R"): [(577,800),(582,840),(583,880),(579,920)],
 ("back","hamstrings","L"): [(314,1000),(311,1040),(313,1080),(317,1120),(317,1160),(318,1200)],
 ("back","hamstrings","R"): [(467,1000),(472,1040),(484,1080),(498,1120),(513,1160),(525,1200)],
 ("back","calves","L"): [(321,1240),(319,1280),(322,1320),(317,1360)],
 ("back","calves","R"): [(482,1240),(481,1280),(480,1320),(485,1360)],
}

def in_shape(s, x, y):
    if s[0]=="c":
        _,cx,cy,l,w,a = s
    else:
        _,cx,cy,rx,ry,a = s; l=ry*2; w=rx*2
    rad=math.radians(a); si,c0=math.sin(rad),math.cos(rad)
    dx,dy=x-cx,y-cy
    u=dx*si+dy*c0; v=dx*c0-dy*si
    hw=w/2; straight=l/2-hw
    if abs(u)<=straight: return abs(v)<=hw
    ux=abs(u)-straight
    return ux*ux+v*v<=hw*hw

def bbox(shapes):
    xs=[];ys=[]
    for s in shapes:
        _,cx,cy = s[0],s[1],s[2]
        r = max(s[3],s[4])/2 + (s[3] if False else 0)
        # sample extent
        for dx in range(-140,141,2):
            for dy in range(-200,201,2):
                if in_shape(s,cx+dx,cy+dy): xs.append(cx+dx); ys.append(cy+dy)
    return min(xs),min(ys),max(xs),max(ys)

def main(path):
    im=Image.open(path).convert("RGB"); W,H=im.size; px=im.load()
    pts=[]
    for y in range(0,H,3):
        for x in range(0,W,3):
            r,g,b=px[x,y]
            if r>50 and r-g>22 and r-b>22 and g<120 and b<120: pts.append((x,y))
    if len(pts)<500: print("no overlay"); return
    # keep only the largest connected component (kills UI noise)
    cell=24; grid={}
    for x,y in pts: grid.setdefault((x//cell,y//cell),[]).append((x,y))
    seen=set(); best=[]
    def flood(k):
        st=[k]; comp=[]
        while st:
            c=st.pop()
            if c in seen or c not in grid: continue
            seen.add(c); comp+=grid[c]
            for dx in(-1,0,1):
                for dy in(-1,0,1): st.append((c[0]+dx,c[1]+dy))
        return comp
    for k in list(grid):
        if k in seen: continue
        comp=flood(k)
        if len(comp)>len(best): best=comp
    pts=best
    bx=(min(p[0] for p in pts),min(p[1] for p in pts),max(p[0] for p in pts),max(p[1] for p in pts))
    print(f"screen blob bbox {bx} n={len(pts)}")
    side = sys.argv[2] if len(sys.argv)>2 else "front"
    allshapes=[s for k,v in CAPS.items() if k[0]==side for s in v]
    gx=bbox(allshapes)
    print(f"geom bbox {gx}")
    sxx=(bx[2]-bx[0])/(gx[2]-gx[0]); syy=(bx[3]-bx[1])/(gx[3]-gx[1])
    ox=bx[0]-gx[0]*sxx; oy=bx[1]-gx[1]*syy
    print(f"scale {sxx:.3f},{syy:.3f} off {ox:.0f},{oy:.0f}")
    # per key: fraction of art centerline points inside corresponding zone shape(s)
    for (v,limb),shapes in CAPS.items():
        if v!=side: continue
        for s in ("L","R"):
            cl=ART.get((v,limb,s))
            if not cl: continue
            # skip foot-flare rows (feet): use only rows >= 2 from end when limb in (quads,hamstrings,calves) last point
            hit=sum(1 for (ax,ay) in cl if any(in_shape(s_,ax,ay) for s_ in shapes if (s_[1]<400)==(s=="L")))
            # angle check: art tilt vs shape tilt
            sh=[s_ for s_ in shapes if (s_[1]<400)==(s=="L")][0]
            ax=[p[0] for p in cl]; ay=[p[1] for p in cl]
            n=len(cl); mx=sum(ax)/n; my=sum(ay)/n
            sxy=sum((ax[i]-mx)*(ay[i]-my) for i in range(n)); sxx2=sum((a-mx)**2 for a in ax); syy2=sum((a-my)**2 for a in ay)
            th=math.degrees(0.5*math.atan2(2*sxy,sxx2-syy2))
            lean = th-90 if th>0 else th+90  # from vertical, + = leans right going down
            print(f"{v}/{limb}/{s}: artline hit {hit}/{n}, art lean {lean:+.1f} vs zone {sh[5]:+.0f}")

main(sys.argv[1])
