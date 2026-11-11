"""Pixel-art app logo.
Edit tools/logo/logo_pixel_24.png (24x24, any pixel editor), then run:
  python tools/logo/pixlogo.py app/src/main/res
It writes every launcher icon size (legacy, round, adaptive foreground, monochrome) and
res/drawable-nodpi/logo_pixel.png (the in-app logo). Delete the 24x24 PNG to regenerate the default drawing."""
import math, os, sys
from PIL import Image
RES = sys.argv[1]
N = 24                       # pixel-art grid
BODY = (18, 18, 20, 255)
EDGE = (44, 44, 48, 255)
ARC = (245, 245, 245, 255)
GLOW1 = (110, 110, 114, 255)
GLOW2 = (52, 52, 56, 255)
BAR = (235, 235, 235, 255)

def art(with_body):
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = im.load()
    if with_body:
        r = 5  # pixel rounded corners
        for y in range(N):
            for x in range(N):
                cx = min(x, N - 1 - x); cy = min(y, N - 1 - y)
                if cx < r and cy < r and (r - cx - 0.5) ** 2 + (r - cy - 0.5) ** 2 > r * r:
                    continue
                edge = cx == 0 or cy == 0 or (cx < r and cy < r and (r - cx + 0.5) ** 2 + (r - cy + 0.5) ** 2 > r * r)
                px[x, y] = EDGE if edge else BODY
    # arc: quarter circle around the bottom-right corner, like the logo's edge shape
    ox, oy, R = N - 0.5, N - 0.5, 15.6
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - ox, y - oy)
            if abs(d - R) <= 0.55:
                px[x, y] = ARC
            elif with_body and R + 0.55 < d <= R + 1.6 and px[x, y][3]:
                px[x, y] = GLOW1
            elif with_body and R + 1.6 < d <= R + 2.7 and px[x, y][3]:
                px[x, y] = GLOW2
    # four music bars, short / mid / tall / mid
    for i, h in enumerate((2, 3, 4, 3)):
        x = 15 + i * 2
        for k in range(h):
            px[x, 19 - k] = BAR
    return im

def up(im, s):
    return im.resize((im.width * s, im.height * s), Image.NEAREST)

dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logo_pixel_24.png")
if os.path.exists(SRC):
    full = Image.open(SRC).convert("RGBA")
    assert full.size == (N, N), "logo_pixel_24.png must be 24x24"
    # monochrome (themed) layer: the launcher tints it with the theme colour, so its alpha follows
    # brightness - light pixels (arc + bars) get the full tint, the grey glow a partial tint,
    # the dark body stays clear and shows the theme background
    fg_art = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    for y in range(N):
        for x in range(N):
            r, g, b, a = full.getpixel((x, y))
            lum = (r + g + b) / 3
            if a and lum > 200:
                al = 255
            elif a and lum > 90:
                al = 120
            else:
                al = 0
            if al:
                fg_art.putpixel((x, y), (255, 255, 255, al))
else:
    full = art(True)
    fg_art = art(False)
    full.save(SRC)
for d, k in dens.items():
    leg = int(48 * k)
    s = leg // N
    icon = up(full, s)
    if icon.width != leg:
        c = Image.new("RGBA", (leg, leg), (0, 0, 0, 0)); c.paste(icon, ((leg - icon.width) // 2,) * 2); icon = c
    icon.save(f"{RES}/mipmap-{d}/ic_launcher.png")
    icon.save(f"{RES}/mipmap-{d}/ic_launcher_round.png")
    # adaptive foreground: 108dp canvas, art in the central 72dp safe zone (+ small margin)
    fgsz = int(108 * k)
    # body + art scaled to ~66dp so the mask never clips the corners of the pixel square
    s2 = max(1, int(66 * k) // N)
    a = up(full, s2)
    c = Image.new("RGBA", (fgsz, fgsz), (0, 0, 0, 0)); c.paste(a, ((fgsz - a.width) // 2,) * 2)
    c.save(f"{RES}/mipmap-{d}/ic_launcher_foreground.png")
    m = up(fg_art, s2)
    mono = Image.new("RGBA", (fgsz, fgsz), (0, 0, 0, 0))
    white = Image.new("RGBA", m.size, (255, 255, 255, 255))
    mono.paste(white, ((fgsz - m.width) // 2,) * 2, m)
    mono.save(f"{RES}/mipmap-{d}/ic_launcher_monochrome.png")
os.makedirs(f"{RES}/drawable-nodpi", exist_ok=True)
up(full, 8).save(f"{RES}/drawable-nodpi/logo_pixel.png")
up(full, 16).save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "logo_preview_16x.png"))
print("ok")
