# Kenga art (Art Upgrade v5 — Vector Figure)

**2026-11-16:** The Body figure is now a **pure vector kangaroo** drawn with
Compose Canvas — no AI bitmap body art anymore. Every one of the 15 tappable
muscle zones IS a real body part filled with its own tier color
([`todayTierColor`](../app/src/main/java/com/example/kenga/ui/body/BodyTierColors.kt)),
outlined, highlighted, and BULK-SHAPED by tier (width ×1.00 → ×1.42 from
0 → 6+ sets, `KangarooBodyGeometry.bulkWidth`), so worked muscles visibly
bulge and grow detail (split lines → striations → full six-pack) as the day
progresses. Fur-colored neutral anatomy (head, ears, tail, paws, shins,
silhouette) sits under the muscles so the animal still reads as a kangaroo.

- Geometry + hit-testing: `app/src/main/java/com/example/kenga/ui/body/KangarooBodyMap.kt`
  (`zoneAt` applies the same bulk scaling as the painter, so taps land where
  the muscle visibly bulges).
- Painter (shared by dashboard AND widget renderer):
  `app/src/main/java/com/example/kenga/ui/body/KangarooFigurePainter.kt`.
- **Flip centering:** the tail makes the figure's bbox asymmetric; since the
  `rotationY` flip mirrors around the canvas center, all geometry is
  translated by `KangarooBodyGeometry.centeringOffsetX()` so the UNION
  horizontal bbox (both views, max bulk) is centered — front and back occupy
  the identical band and he stays put across swipes (hit-testing undoes the
  offset in `zoneAt`).
- Off-device preview: `scripts/preview_vector_figure.py` → `shots/vector_*.png`
  (Python port of the same constants).
- The confidence metric `KangarooBodyGeometry.coloredZoneCount` (distinct
  areas with sets today) is served by `FigureSnapshotProvider` in the new
  `zones` column; Tail moves him IN FRONT of the lizard on the scene widget
  once ≥4 areas are colored (`CONFIDENT_ZONE_COUNT`).
- `kenga_body_{front,back}.png` are no longer referenced by code and were
  removed from `drawable-nodpi/`.

The launcher icon is still AI-generated. No network calls happen in the app.

## Pipeline (manual, one-off)

1. **Generate** — `scripts/gen_kanga_art.py` (front + back body) and
   `scripts/gen_kanga_icon.py` via the PPQ Grok-Imagine API
   (`https://api.ppq.ai/v1/images/generations`, model `grok-imagine-image-2`,
   quality `medium`). Body: resolution `2k`, aspect `2:3` (matches the
   1000×1500 zone canvas); icon: `1k`, `1:1`. Raw plates are archived in
   `docs/art/` (`kenga_body_{front,back}_raw.png`, `kenga_icon_raw.png`).
   Cost at generation time: $0.132 per body plate (2k), $0.099 for the icon.
   API key is read at runtime from
   `/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt` — never copied
   into this repo.

2. **Process** — `scripts/make_kenga_assets.py` (PIL + numpy + scipy):
   - **Background removal (body):** flood-fill-from-edges chroma key. The
     flood tolerance is deliberately STRICT (`BG_TOL = 12`): the generated
     pale-sand background has corner std ≈ 1, but the kangaroo's cream belly
     is only color-distance ≈ 17 from it — a looser tolerance eats the belly
     through the A-pose arm gaps (belly ↔ outside background are connected).
     A 3px halo band within distance 34 of true background is eroded to kill
     the anti-aliased rim; enclosed background-colored pockets between
     legs/tail are punched back out; off-figure specks < 4000 px dropped.
   - **Front/back alignment (the flip):** both cutouts are trimmed to their
     alpha bounding boxes; the BACK figure is rescaled (LANCZOS) so its
     bounding-box height matches the FRONT's (scale ≈ 1.036 this generation),
     then both are composited onto identical 1000×1500 transparent canvases,
     each filling 94% of the height and centered on its bbox center. Both
     final figures span y=45–1454 with head centers 410 (front) / 401 (back)
     — the figure stays in the same position across the `rotationY` flip
     because the bitmaps are the same size drawn in the same centered box.
   - **Icon:** full-bleed ochre plate → 432px adaptive foreground PNG at
     `drawable-nodpi/ic_launcher_foreground.png` (the old vector
     `drawable/ic_launcher_foreground.xml` is deleted — Octo's exact
     structure), plus legacy square + circle-masked round WebP mipmaps for
     all densities. `ic_launcher_background.xml` stays (fully covered).

3. **Assets in app:**
   - `app/src/main/res/drawable-nodpi/kenga_body_front.png` (1000×1500)
   - `app/src/main/res/drawable-nodpi/kenga_body_back.png` (1000×1500)
   - `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` (432px)
   - `app/src/main/res/mipmap-*/ic_launcher{,_round}.webp`
   - In-app brand marks (`KengaHeader`, Stats empty-state) draw
     `ic_launcher_foreground.png` — the placeholder vector
     `drawable/kenga_mascot.xml` was deleted (2026-10-24).

## Zone tuning (v4 — zone-perfection pass, deterministic scoring harness)

**v4 (2026-10-24)** — the user reported back-leg asymmetry and back-arm
misalignment. Since vision was unavailable, this pass is fully deterministic:

- **`scripts/kenga_zones.py`** parses the live zone geometry out of
  `KangarooBodyMap.kt` (no duplicated constants) and rasterizes every shape
  exactly as Compose draws it (upright shape rotated −angle around its
  center → PIL `rotate(angle)` CCW).
- **`scripts/preview_zones.py`** composites all zones as red overlays onto
  the art → `shots/preview_{front,back}.png` (ground truth, no reinstall).
- **`scripts/score_zones.py`** scores every shape on both views:
  *coverage* (overlay pixels on art α>0; ≥0.95), *centerline* (fraction of
  the 13 axis samples whose pixel is on art, using a **windowed local
  centroid** measurement — plain span centers break where arm+torso merge
  into one α-span; ≥5/6), *angle fit* (linear fit of measured centerline vs
  shape angle, ≤3°), and *pair symmetry* (equal dims ±3 px, |lateral
  residual L−R| ≤6 px, |angle-residual-to-art L−R| ≤3° — symmetric around
  each leg's own axis, since the pose has one splayed leg).
  **Final state: ALL PASS — every zone, both views, all four metrics.**

Key v4 geometry findings/changes (all in `KangarooBodyMap.kt`):

- **Back legs symmetric:** hamstrings = identical capsules
  (L `cap(316,1100,190,70,+1°)` on a near-vertical leg, R
  `cap(490,1100,190,70,+16°)` on the splayed leg — angles are each limb's
  measured axis); calves likewise (150×44, L 0° / R +3°). Back feet are
  **tilted ellipses** (∓25°/+14° fit to the measured foot blobs), not the
  old axis-aligned ovals.
- **Back arms:** shoulders re-centered on the actual arm tops
  (ell 30×42 at ±15°), biceps follow the measured upper-arm axes
  (∓13°, 140×48), forearms vertical (126×34) — all with mirrored dims.
- **Back lats/obliques were sitting in the arm–torso background gap**
  (the art has separated arms leaving a diagonal α-gap beside the torso):
  lats moved fully inside the torso flank (110×36 at ∓5°), obliques hug the
  inside of the widening flank (120×42-44, front ∓20°, back ∓16°).
- **Front feet:** big rotated ellipses (46×68) at the measured foot angles
  (∓41°/+36°) — the feet flare much more than the old ±16° guessed.
- **Pelvic split:** front hips narrowed to a band ellipse (158×30 @ y972)
  and groin shrunk to a small pouch marker (20×14 @ y986) — the old shapes
  dipped into the leg-split background gap below y≈1000.
- Quads equalized to identical 200×90 capsules (0°/+13°); back glutes
  lobes equal 110×96 with per-lobe fit angles (−3°/−8°).

Final scores (coverage / centerline / |angle fit| / pair symmetry — all
within targets):

| View | Zone | cov | cline | \|Δang\| | sym |
|---|---|---|---|---|---|
| front | quads | 1.000/0.998 | 1.00 | 0.3° | dims=, 0.3px, 0.3° |
| front | hips_glutes | 0.993 | 1.00 | 0° | — |
| front | groin | 0.963 | 1.00 | 0° | — |
| front | core | 0.999 | 1.00 | 0° | — |
| front | chest | 1.000 | 1.00 | 0° | — |
| front | obliques | 0.968 | 1.00 | ≤0.5° | dims=, 0.4px, 0.3° |
| front | shoulders | 0.984/0.990 | 1.00 | ≤0.6° | dims=, 0.1px, 0.3° |
| front | biceps | 0.971/0.980 | 1.00 | ≤0.7° | dims=, 0.0px, 0.2° |
| front | wrists | 0.960/0.955 | 1.00 | ≤1.6° | dims=, 0.2px, 0.1° |
| front | ankles/feet | 0.958/0.980 | 1.00 | ≤0.3° | dims=, 0.0px, 0.3° |
| back | hamstrings | 0.965/1.000 | 1.00 | ≤0.4° | dims=, 0.0px, 0.4° |
| back | calves | 0.980/0.952 | 1.00 | ≤1.0° | dims=, 1.2px, 0.9° |
| back | hips_glutes | 0.980/1.000 | 1.00 | ≤2.7° | dims=, lobes per-leg |
| back | lower_back | 1.000 | 1.00 | 0° | — |
| back | lats | 0.987/1.000 | 1.00 | ≤0.3° | dims=, 0.0px, 0.2° |
| back | upper_back | 1.000 | 1.00 | 0° | — |
| back | obliques | 1.000 | 1.00 | ≤0.5° | dims=, 0.0px, 0.1° |
| back | shoulders | 0.993/0.990 | 1.00 | ≤0.9° | dims=, 0.3px, 0.7° |
| back | biceps | 1.000/0.990 | 1.00 | ≤0.3° | dims=, 0.1px, 0.2° |
| back | wrists | 0.958/0.960 | 1.00 | ≤1.2° | dims=, 0.0px, 0.1° |
| back | ankles/feet | 0.951/0.983 | 1.00 | ≤0.9° | dims=, 0.3px, 0.1° |

On-device: `assembleDebug`+`installDebug` OK; adb screencaps
(`shots/final_{front,back,front2}.png`) show the overlays rendering, and
two front captures differ by mean 0.066/255 (flip is deterministic).

## Zone tuning (v3 — rotation-sign fix + pose-corrected symmetry)

**v3 (2026-10-24)** — empirical re-measurement via `scripts/measure_limbs.py`
(per-row alpha runs) + on-device screenshot verification
(`scripts/verify_align.py`; adb screencap, 3 iterate-and-inspect cycles):

- **Rotation-sign bug fixed (the "arms angled the wrong way" report):**
  Compose `rotate(+θ)` is clockwise on screen (leans LEFT going down) while
  the hit-test frame treats +θ as leaning RIGHT going down — every drawn
  zone was tilt-mirrored vs. its data. The draw path now rotates by
  `-angleDeg`, so the documented convention (+ = leans right going down)
  holds for both draw and hit-test.
- **biceps_triceps front:** raised cy 722→712, length 140→160, angles
  re-fit to the measured arm axes: L (234,680)→(188,800) = −21°,
  R (595,680)→(640,800) = +21°.
- **quads:** pose-corrected mirror symmetry — identical capsule dims
  (L: cap(316,1100,200,90,0°); R: cap(537,1095,180,90,+13°)) so the two
  zones mirror each other around each leg's own axis (left leg vertical,
  right thigh splaying +13–16°), not the naive figure centerline.
  Hamstrings got the same treatment (L 210 l / R 220 l at +16°).
- **back arms:** shoulders raised to the actual arm-tops (cy 528→585,
  ±13–14°), biceps re-fit to upper-arm axes L −13°/R +14° at cy 690
  (l 150), forearms nearly vertical (±1–2°) at cy 862.

## Zone tuning (v2 — rotated limb-hugging geometry)

`KangarooBodyMap` keeps the fixed 1000×1500 normalized zone space; the bitmap
is drawn into that exact space. After user feedback on the first axis-aligned
rects, every zone is now a **rotated capsule or ellipse** whose angle follows
the actual limb of the generated art. Angles/positions were measured from the
cutouts' per-row alpha runs (`scripts/measure_landmarks.py`):

- arms slope outward-down: front ≈ ∓20° (shoulder→paw), back ≈ ∓10°;
  forearms flatten to ≈ ∓8° near the paws
- left leg runs vertical; right thigh splays +15–16° (top (513,1000) →
  bottom (560,1160) front); shins vertical below y≈1200
- front feet flare outward at ≈ ±15°; back feet stay axis-aligned
- torso panels (chest/core/upper_back/lower_back) are upright capsules sized
  to the measured torso runs (y≈470–950, x≈240–600)

The **head/ears/neck zone is removed** — `neck` stays in the muscle-group
taxonomy (logging, stats, settings, and the Body summary all still count it)
but has no tappable region on the map. The tier legend (0–6+ color key) was
also removed from the Body screen per the same feedback round.

Each shape carries an `angleDeg`; drawing wraps the shape in a `rotate()` at
its pivot, and hit-testing maps the tap into the shape's rotated frame
(capsule = stadium test incl. end caps, ellipse = normalized-radius test).
Zones are drawn at 62% alpha over the art (`KangarooBodyGeometry.ZONE_ALPHA`).

## Bottom-nav icons (nav-icon upgrade)

The five bottom-bar emoji were replaced with AI-generated outback-themed
glyphs (`scripts/gen_nav_icons.py`, same PPQ Grok-Imagine API, 1k/1:1,
$0.099 each; raw plates archived in `docs/art/nav/`):

- Body = jumping kangaroo silhouette · Library = library card + outback sun
- Log = dumbbell with kangaroo-tail curve · Stats = boomerang rising-bar
  chart · Settings = gear-rimmed compass with gumnut

`scripts/make_nav_icons.py` (PIL + numpy + scipy) thresholds each raw plate
(glyph is the only bright thing on the near-black background), keeps the
large connected components, trims/pads to square, flattens RGB to pure white
and uses the mask as alpha → a **tintable monochrome** 96×96 PNG in
`app/src/main/res/drawable/ic_nav_{body,library,log,stats,settings}.png`.
`KengaBottomBar` draws them as 24dp `Image`s with a Material3
`ColorFilter.tint`, so selected/unselected tinting keeps working. Note: PPQ's
auth endpoint proved intermittently flaky during the batch run (401
"Invalid API key" for a key that works seconds later); generating the icons
one request per shell invocation worked reliably.
