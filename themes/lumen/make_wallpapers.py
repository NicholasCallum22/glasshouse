#!/usr/bin/env python3
"""Draw the three Lumen wallpapers (morning, evening, night) from code.

Dark, soft light fields: large out-of-focus glows over a deep gradient, kept dim
where the clock (top left) and the dock (lower middle) sit, with fine noise so
the gradients don't band on a TV that decodes wallpapers in RGB_565.

    python3 themes/lumen/make_wallpapers.py      # writes themes/lumen/wallpapers/*.jpg
"""
import os
import numpy as np
from PIL import Image

W, H = 1920, 1080
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wallpapers")

y, x = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = x / W, y / H


def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], np.float32) / 255.0


def glow(cx, cy, rx, ry, color, strength, angle=0.0):
    """An elliptical gaussian light, rotated by angle (radians)."""
    dx, dy = u - cx, (v - cy) * (H / W)
    ca, sa = np.cos(angle), np.sin(angle)
    a, b = dx * ca + dy * sa, -dx * sa + dy * ca
    g = np.exp(-((a / rx) ** 2 + (b / ry) ** 2))
    return g[..., None] * hexrgb(color) * strength


def scene(top, bottom, lights, stars=0, seed=1):
    t = v[..., None]
    img = hexrgb(top) * (1 - t) + hexrgb(bottom) * t
    for l in lights:
        img = img + glow(*l)
    # keep the clock corner and the dock band calm
    clock = np.exp(-(((u - 0.12) / 0.22) ** 2 + ((v - 0.12) / 0.20) ** 2))
    dock = np.exp(-(((v - 0.70) / 0.16) ** 2))
    img = img * (1 - 0.35 * clock[..., None]) * (1 - 0.30 * dock[..., None])
    # gentle vignette
    vig = 1 - 0.45 * (((u - 0.5) / 0.75) ** 2 + ((v - 0.45) / 0.85) ** 2)
    img = img * np.clip(vig, 0.35, 1)[..., None]
    rng = np.random.default_rng(seed)
    if stars:
        sx = rng.integers(0, W, stars)
        sy = rng.integers(0, int(H * 0.55), stars)
        sb = rng.uniform(0.15, 0.55, stars) * (1 - sy / (H * 0.55))
        for px, py, b in zip(sx, sy, sb):
            if (px / W - 0.12) ** 2 + (py / H - 0.12) ** 2 < 0.03:
                continue  # none behind the clock
            img[py, px] += b
            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                qx, qy = px + ox, py + oy
                if 0 <= qx < W and 0 <= qy < H:
                    img[qy, qx] += b * 0.25
    # fine grain + triangular dither against banding
    img = img + (rng.random((H, W, 1)) - rng.random((H, W, 1))) * (2.2 / 255)
    img = img + rng.normal(0, 0.006, (H, W, 3))
    return Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8))


SCENES = {
    # cool dawn: deep teal, a peach sunrise low right, mist across the middle
    "morning": dict(
        top="#081521", bottom="#04080e", seed=11,
        lights=[(0.84, 0.50, 0.30, 0.16, "#ff7a45", 0.34, -0.20),
                (0.38, 0.20, 0.42, 0.18, "#2f86b8", 0.30, 0.15),
                (0.60, 0.62, 0.65, 0.06, "#e0a27a", 0.10, -0.08),
                (0.97, 0.30, 0.18, 0.22, "#ffcf8a", 0.12, 0.0)]),
    # ember dusk: plum sky, a wide amber horizon
    "evening": dict(
        top="#120a1c", bottom="#06040a", seed=23,
        lights=[(0.62, 0.86, 0.70, 0.10, "#ff6a30", 0.26, 0.0),
                (0.80, 0.45, 0.40, 0.25, "#d4507a", 0.24, 0.35),
                (0.35, 0.28, 0.45, 0.22, "#6b3fa0", 0.22, -0.25),
                (0.95, 0.80, 0.25, 0.15, "#ffc070", 0.14, 0.0)]),
    # night: ink navy, an aurora ribbon of teal and violet, a few stars
    "night": dict(
        top="#060a18", bottom="#020308", seed=37, stars=260,
        lights=[(0.70, 0.22, 0.55, 0.07, "#2ee6b8", 0.22, -0.30),
                (0.52, 0.30, 0.50, 0.08, "#4bd6c8", 0.14, -0.18),
                (0.88, 0.18, 0.30, 0.20, "#8a5cf6", 0.22, 0.10),
                (0.40, 0.80, 0.70, 0.18, "#1b2a6b", 0.30, 0.0)]),
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, s in SCENES.items():
        path = os.path.join(OUT, name + ".jpg")
        img = scene(**s)
        q = 88
        while True:  # stay under ~300 KB, the size the theme guide asks for
            img.save(path, "JPEG", quality=q, optimize=True, progressive=True, subsampling=0)
            if os.path.getsize(path) <= 300_000 or q <= 70:
                break
            q -= 2
        print(f"{name}.jpg  q={q}  {os.path.getsize(path) // 1024} KB")
