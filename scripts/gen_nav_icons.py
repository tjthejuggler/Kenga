#!/usr/bin/env python3
"""Generate the 5 outback-themed bottom-nav icon glyphs via PPQ Grok-Imagine.

Nav-icon upgrade (replaces emoji): each glyph is prompted as a simple flat
MONOCHROME white symbol on a pure black background so the PIL post-pass can
threshold it into a tintable alpha mask. Raw plates archived under docs/art/.

API key is READ from /home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt
(read-only; never copied into the repo, never printed). Stdlib only (urllib).
Run manually by hand only — never as part of any build.
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

KENGA_ROOT = Path(__file__).resolve().parent.parent
KEY_PATH = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
RAW_DIR = KENGA_ROOT / "docs" / "art" / "nav"

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

STYLE = (
    "Minimalist flat vector icon glyph, single solid pure WHITE symbol "
    "perfectly centered on a solid pure BLACK background, one color only, "
    "bold simple shapes, thick clean silhouette, high contrast, no text, no "
    "letters, no border, no gradient, generous margin around the symbol."
)

ICONS = {
    "nav_body": STYLE + " The symbol is a kangaroo in mid-jump silhouette, "
        "side profile, big tail sweeping behind, simplified geometric shapes.",
    "nav_library": STYLE + " The symbol is a stylized film/media library card "
        "with a small outback sun disc rising behind its top edge.",
    "nav_log": STYLE + " The symbol is a dumbbell with a gentle kangaroo-tail "
        "curve sweeping under it, fitness journal feel.",
    "nav_stats": STYLE + " The symbol is a boomerang shape doubling as a "
        "rising bar chart, three ascending bars under a boomerang arc.",
    "nav_settings": STYLE + " The symbol is a compass with a gear-toothed "
        "rim and a small gumnut (eucalyptus seed pod) at its center.",
}


class HttpError(Exception):
    """HTTP error with status + response body (body never contains the key)."""

    def __init__(self, code: int, body: bytes, url_hint: str):
        super().__init__(f"{url_hint}: HTTP {code}: {body.decode('utf-8', 'replace')[:300]}")


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
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, e.read(), "generations") from e


def fetch_bytes(url: str, label: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "kenga-nav-gen/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, e.read(), label) from e


def gen_one(key: str, prompt: str) -> bool:
    payload = {
        "model": MODEL,
        "prompt": prompt,
        "quality": "medium",
        "resolution": "1k",
        "aspect_ratio": "1:1",
        "n": 1,
    }
    for attempt in range(1, 7):
        try:
            print(f"[{key}] attempt {attempt} ...")
            data = http_post_json(URL, key, payload)
            items = data.get("data") or []
            url = (items[0] or {}).get("url") if items else None
            if url:
                png = fetch_bytes(url, "media")
            else:
                import base64
                b64 = (items[0] or {}).get("b64_json") if items else None
                if not b64:
                    raise RuntimeError(f"no image: {str(data)[:200]}")
                png = base64.b64decode(b64)
            if len(png) < 1024:
                raise RuntimeError(f"suspiciously small ({len(png)} B)")
            out = RAW_DIR / f"{key}_raw.png"
            out.write_bytes(png)
            print(f"  OK {len(png) / 1024:.0f} KiB -> {out}")
            return True
        except Exception as err:  # noqa: BLE001
            print(f"  failed: {err}", file=sys.stderr)
            # PPQ auth has proven intermittently flaky (401 "Invalid API key"
            # on the very key that succeeds seconds later) — back off hard.
            time.sleep(15 + 10 * attempt)
    return False


def main() -> int:
    key = KEY_PATH.read_text(encoding="utf-8").strip()
    if not key:
        print("ABORT: key file empty/missing", file=sys.stderr)
        return 2
    print(f"key loaded: len={len(key)} prefix={key[:3]}")
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    failed = [k for k, p in ICONS.items() if not gen_one(k, p)]
    if failed:
        print(f"FAILED: {failed}", file=sys.stderr)
        return 1
    print("all 5 glyphs generated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
