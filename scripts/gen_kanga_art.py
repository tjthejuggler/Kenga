#!/usr/bin/env python3
"""Generate the FRONT and BACK kangaroo body-map art via the PPQ Grok-Imagine API.

Art Upgrade (supersedes the old single-mascot generator): the Body screen's
Canvas kangaroo is replaced by AI art. This script generates BOTH views of the
same athletic kangaroo — front (face visible) and back (mirrored same pose) —
on a plain solid background so scripts/make_kenga_assets.py can cut the figure
out and pixel-align the two views for the front/back flip.

Outputs (raw API results, archived for reference):
  - docs/art/kenga_body_front_raw.png
  - docs/art/kenga_body_back_raw.png

API key is READ from /home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt
(read-only; never copied into the repo, never printed). Stdlib only (urllib).
Run manually by hand only — never as part of any build.
"""
from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

KENGA_ROOT = Path(__file__).resolve().parent.parent
KEY_PATH = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
ART_DIR = KENGA_ROOT / "docs" / "art"

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

# Shared style core: flat vector anatomy-figure look, symmetric standing pose,
# plain solid background (so chroma-key cutout works), no text — modeled on
# Octo's octopus-man prompts, retuned for an anatomy-map kangaroo.
STYLE = """Flat comic-vector illustration of an athletic cartoon kangaroo, \
simple stylized anatomy figure, standing perfectly upright and symmetric, \
front-facing, feet shoulder-width apart, arms hanging straight down at the \
sides, big tapering tail resting on the ground beside the right leg, two tall \
upright ears, simple friendly face, clean uniform color regions, absolutely no \
shading gradients, neutral even flat lighting, desert tan fur with cream belly, \
centered full-body figure occupying most of the frame with generous margin, \
plain solid uniform background of one single flat pale sand color, no text, \
no letters, no words."""

PROMPTS = {
    "front": (
        "FRONT view: " + STYLE + " Viewed straight on from the front: chest, "
        "cream belly pouch, face and both eyes fully visible, quads facing the "
        "viewer, symmetric A-pose with arms slightly away from the torso."
    ),
    "back": (
        "BACK view, exact mirrored same pose: " + STYLE + " Viewed straight on "
        "from BEHIND: no face, back of the head with ears, upper back and lower "
        "back facing the viewer, glutes and hamstrings and calves facing the "
        "viewer, tail visible behind, arms hanging straight down at the sides "
        "in the SAME pose as the front view, figure centered in the exact same "
        "position and scale as a front-view sheet drawing."
    ),
}


def http_post_json(url: str, key: str, body: dict, timeout: int = 300) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get_bytes(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "kenga-art-gen/2.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def generate(key: str, side: str) -> int:
    out = ART_DIR / f"kenga_body_{side}_raw.png"
    payload = {
        "model": MODEL,
        "prompt": PROMPTS[side],
        "quality": "medium",
        "resolution": "2k",       # body map needs crisp edges for cutout
        "aspect_ratio": "2:3",    # matches the 1000x1500 zone canvas exactly
        "n": 1,
    }

    last_err: Exception | None = None
    for attempt in range(1, 4):
        try:
            print(f"[{side} {attempt}/3] POST {URL} model={MODEL} ...")
            data = http_post_json(URL, key, payload)
            items = data.get("data") or []
            image_url = (items[0] or {}).get("url") if items else None
            if not image_url:
                b64 = (items[0] or {}).get("b64_json") if items else None
                if b64:
                    import base64

                    png = base64.b64decode(b64)
                else:
                    raise RuntimeError(f"no image in response: {str(data)[:300]}")
            else:
                print(f"downloading {image_url}")
                png = http_get_bytes(image_url)
            if len(png) < 1024:
                raise RuntimeError(f"suspiciously small image ({len(png)} bytes)")

            out.write_bytes(png)
            print(f"OK {side}: {len(png) / 1024:.0f} KiB -> {out}")
            if "cost" in data:
                print(f"cost {side}: {data['cost']}")
            return 0
        except Exception as err:  # noqa: BLE001 — graceful skip after tries
            last_err = err
            print(f"{side} attempt {attempt} failed: {err}", file=sys.stderr)

    print(f"SKIPPED {side} after 3 attempts: {last_err}", file=sys.stderr)
    return 1


def main() -> int:
    if not KEY_PATH.exists():
        print(f"ABORT: key file missing: {KEY_PATH}", file=sys.stderr)
        return 2
    key = KEY_PATH.read_text(encoding="utf-8").strip()
    if not key:
        print("ABORT: key file empty", file=sys.stderr)
        return 2

    ART_DIR.mkdir(parents=True, exist_ok=True)
    rc = 0
    rc |= generate(key, "front")
    rc |= generate(key, "back")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
