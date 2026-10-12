"""DuoBeam artwork in a violet-galaxy style: app icon (TV + Android) and the TV waiting-screen background.

Usage: python make_art.py   (writes ../icon.png, ../img/bg.jpg and the Thor app mipmaps)
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
TV = os.path.normpath(os.path.join(HERE, '..'))
ANDROID_RES = os.path.normpath(os.path.join(TV, '..', 'thor-app', 'android', 'app', 'src', 'main', 'res'))


def fbm(w, h, rng, octaves=6, base=3):
    """Fractal noise in 0..1 (sum of upscaled random grids)."""
    acc = np.zeros((h, w), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        n = base * 2 ** o
        grid = rng.random((n + 1, int(n * w / h) + 2)).astype(np.float32)
        layer = np.array(Image.fromarray((grid * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC), np.float32) / 255
        acc += layer * amp
        total += amp
        amp *= 0.55
    acc /= total
    return (acc - acc.min()) / (np.ptp(acc) + 1e-6)


def nebula(w, h, seed=7, stars=True):
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    nx, ny = xx / w, yy / h
    # diagonal, slightly wavy nebula band (Milky Way-like)
    band_c = 0.78 - 0.55 * nx + 0.06 * np.sin(nx * 7 + seed)
    band = np.exp(-((ny - band_c) ** 2) / (2 * 0.16 ** 2))
    gas = fbm(w, h, rng)
    detail = fbm(w, h, rng, octaves=5, base=6)
    dens = np.clip((gas * 1.35 - 0.38), 0, None) ** 1.6 * band * (0.6 + 0.8 * detail)
    hue = fbm(w, h, rng, octaves=3, base=2)  # 0 = violet/blue, 1 = magenta/pink
    c_blue = np.array([70, 70, 255], np.float32)
    c_violet = np.array([150, 60, 255], np.float32)
    c_pink = np.array([255, 70, 210], np.float32)
    h3 = hue[..., None]
    col = np.where(h3 < 0.5, c_blue + (c_violet - c_blue) * (h3 * 2), c_violet + (c_pink - c_violet) * ((h3 - 0.5) * 2))
    light = col * dens[..., None] * 2.2
    # bright core inside the band
    core = np.exp(-(((nx - 0.56) * w) ** 2 + ((ny - band_c) * h) ** 2) / (2 * (0.09 * w) ** 2))
    light += np.array([255, 170, 255], np.float32) * (core * dens * 2.5)[..., None]
    # dark space background (navy -> violet)
    bg = np.stack([8 + 18 * nx, 4 + 6 * ny, 22 + 30 * nx], -1)
    # tone mapping: brightness saturates instead of clipping
    img = 255 * (1 - np.exp(-(bg + light) / 170))
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(max(w, h) / 900))
    if stars:
        d = ImageDraw.Draw(im)
        n = int(w * h / 2500)
        for _ in range(n):
            x, y = rng.uniform(0, w), rng.uniform(0, h)
            b = rng.uniform(0.3, 1.0) ** 3
            s = 0.6 + b * max(w, h) / 900
            c = int(160 + 95 * b)
            d.ellipse([x - s, y - s, x + s, y + s], fill=(c, c, 255))
        glow = im.filter(ImageFilter.GaussianBlur(max(w, h) / 300))
        im = Image.blend(im, Image.eval(glow, lambda v: min(255, v * 2)), 0.25)
    return im


def font(size):
    for f in ('C:/Windows/Fonts/segoeuib.ttf', 'C:/Windows/Fonts/arialbd.ttf'):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            pass
    return ImageFont.load_default()


def icon(size=512):
    S = 4 * size  # draw at 4x and downscale for smooth edges
    base = nebula(S, S, seed=3)
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], int(S * 0.22), fill=255)
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    w = int(S * 0.035)
    # Thor's two screens: wide top, smaller bottom
    top = [S * 0.17, S * 0.16, S * 0.83, S * 0.53]
    bot = [S * 0.29, S * 0.58, S * 0.71, S * 0.86]
    for r in (top, bot):
        d.rounded_rectangle(r, int(S * 0.05), fill=(15, 8, 40, 150), outline=(255, 255, 255, 235), width=w)
    # play triangle with glow
    cx, cy, k = S * 0.5, S * 0.345, S * 0.10
    tri = [(cx - k * 0.75, cy - k), (cx - k * 0.75, cy + k), (cx + k * 0.95, cy)]
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).polygon(tri, fill=(255, 120, 255, 255))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.03))
    layer = Image.alpha_composite(glow, layer)
    d = ImageDraw.Draw(layer)
    d.polygon(tri, fill=(255, 255, 255, 255))
    # "broadcast" waves at the bottom screen
    for i, r in enumerate((0.07, 0.12)):
        bb = [S * 0.5 - S * r, S * 0.72 - S * r, S * 0.5 + S * r, S * 0.72 + S * r]
        d.arc(bb, 200, 340, fill=(190, 160, 255, 230 - i * 70), width=int(S * 0.022))
    out = Image.alpha_composite(base.convert('RGBA'), layer)
    out.putalpha(mask)
    return out.resize((size, size), Image.LANCZOS)


def main():
    ic = icon(512)
    ic.save(os.path.join(TV, 'icon.png'))
    for name, s in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        dst = os.path.join(ANDROID_RES, 'mipmap-' + name)
        os.makedirs(dst, exist_ok=True)
        ic.resize((s, s), Image.LANCZOS).save(os.path.join(dst, 'ic_launcher.png'))
    os.makedirs(os.path.join(TV, 'img'), exist_ok=True)
    nebula(1920, 1080, seed=11).save(os.path.join(TV, 'img', 'bg.jpg'), quality=88)
    # side-by-side preview image
    prev = Image.new('RGB', (1920 + 40 + 512, 1080), (0, 0, 0))
    prev.paste(Image.open(os.path.join(TV, 'img', 'bg.jpg')), (0, 0))
    prev.paste(ic, (1960, 284), ic)
    prev.resize((prev.width // 2, prev.height // 2)).save(os.path.join(TV, 'art_preview.jpg'), quality=85)
    print('ok')


if __name__ == '__main__':
    main()
