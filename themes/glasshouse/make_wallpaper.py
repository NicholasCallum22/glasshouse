#!/usr/bin/env python3
"""Draw the Glasshouse wallpaper: a soft, out-of-focus warm grey like an iPhone's blurred photo,
with sage on the right and an umber glow at the bottom, darkened where the clock and dock sit.

    python3 themes/glasshouse/make_wallpaper.py      # writes themes/glasshouse/wallpaper.jpg
"""
import os
import numpy as np
from PIL import Image

W, H = 1920, 1080
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wallpaper.jpg")

y, x = np.mgrid[0:H, 0:W].astype(np.float32)


def rgb(h):
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], np.float32) / 255.0


def light(cx, cy, rx, ry, color, fade):
    """A radial light like CSS radial-gradient(rx ry at cx cy, color 0%, transparent fade)."""
    d = np.sqrt(((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2)
    a = np.exp(-(d / (fade * 0.55)) ** 2) * 0.85   # gaussian: no visible edge
    return a[..., None], rgb(color)


t = (y / H)[..., None]
img = rgb("#77736a") * (1 - t) + rgb("#3c3830") * t
for args in [(0.60 * W, 0.55 * H, 700, 500, "#7d7b85", 0.70),
             (0.46 * W, 1.04 * H, 1300, 620, "#5a4632", 0.70),
             (0.86 * W, 0.26 * H, 900, 640, "#66736b", 0.60),
             (0.18 * W, 0.10 * H, 1100, 680, "#9a9484", 0.62)]:
    a, c = light(*args)
    img = img * (1 - a) + c * a

# the same shade the design uses: darker at the top for the clock, darker at the bottom for the dock
shade = np.interp(y / H, [0, 0.4, 1], [0.30, 0.12, 0.38])[..., None]
img = img * (1 - shade)

rng = np.random.default_rng(7)
img = img + (rng.random((H, W, 1)) - rng.random((H, W, 1))) * (2.2 / 255)   # dither against banding
img = img + rng.normal(0, 0.0015, (H, W, 3))   # a whisper of grain, not visible noise
Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)).save(
    OUT, "JPEG", quality=88, optimize=True, progressive=True, subsampling=0)
print(f"wallpaper.jpg {os.path.getsize(OUT) // 1024} KB")
