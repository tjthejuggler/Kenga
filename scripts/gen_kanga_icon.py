#!/usr/bin/env python3
"""Generate the Kenga launcher-icon artwork via the PPQ Grok-Imagine API.

Art Upgrade: flat vector app icon — kangaroo head + dumbbell on an outback
ochre/teal background (JJR icon precedent wording).

Output (raw API result, archived):
  - docs/art/kenga_icon_raw.png

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
RAW_OUT = KENGA_ROOT / "docs" / "art" / "kenga_icon_raw.png"

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

PROMPT = """Flat vector Android app icon, centered composition on a deep \
outback ochre-brown background with a subtle solid teal accent shape. A \
stylized friendly cartoon kangaroo head facing forward — two tall upright \
ears, simple cute face, desert tan fur — with a small gray dumbbell held \
crosswise in front of the chin. Clean modern minimal vector style, bold \
shapes, high contrast, warm outback palette (ochre, teal, tan), no text, no \
letters, no words, generous margin around the subject, entire background one \
single flat color with no gradients."""


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
    req = urllib.request.Request(url, headers={"User-Agent": "kenga-icon-gen/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def main() -> int:
    if not KEY_PATH.exists():
        print(f"ABORT: key file missing: {KEY_PATH}", file=sys.stderr)
        return 2
    key = KEY_PATH.read_text(encoding="utf-8").strip()
    if not key:
        print("ABORT: key file empty", file=sys.stderr)
        return 2

    RAW_OUT.parent.mkdir(parents=True, exist_ok=True)

    payload = {
        "model": MODEL,
        "prompt": PROMPT,
        "quality": "medium",
        "resolution": "1k",
        "aspect_ratio": "1:1",
        "n": 1,
    }

    last_err: Exception | None = None
    for attempt in range(1, 4):
        try:
            print(f"[{attempt}/3] POST {URL} model={MODEL} ...")
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

            RAW_OUT.write_bytes(png)
            print(f"OK: {len(png) / 1024:.0f} KiB -> {RAW_OUT}")
            if "cost" in data:
                print(f"cost: {data['cost']}")
            return 0
        except Exception as err:  # noqa: BLE001 — graceful skip after tries
            last_err = err
            print(f"attempt {attempt} failed: {err}", file=sys.stderr)

    print(f"SKIPPED after 3 attempts: {last_err}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
