"""Regenerates launcher icon foreground, monochrome icon and widget preview. Needs Pillow."""
import os, math
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res')
GRAY, LAV = (112, 112, 112), (232, 218, 242)

# (x, trackTop, trackBottom, innerTop, innerBottom, glowEnd, strength) in 0..1 icon space
BARS = [
    (.152, .408, .594, .408, .594, 'b', .55),
    (.268, .347, .652, .380, .581, 't', .60),
    (.383, .247, .753, .331, .665, 'b', 1.0),
    (.498, .291, .708, .321, .567, 't', 1.0),
    (.614, .347, .652, .369, .552, 'b', .65),
    (.730, .305, .693, .414, .632, 'b', 1.0),
    (.847, .434, .565, .434, .565, 'b', .35),
]
BW = .074

def vgrad(w, h, top, bottom, power=1.0):
    g = Image.linear_gradient('L').resize((max(w, 1), max(h, 1)))
    g = g.point(lambda v: int(255 * (v / 255) ** power))
    return Image.composite(Image.new('RGB', (w, h), bottom), Image.new('RGB', (w, h), top), g)

def mix(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def make_icon(N=864):
    img = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    halo = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    c = lambda u: (1 / 6 + u * 2 / 3) * N
    bw = BW * 2 / 3 * N
    for x, t0, t1, i0, i1, ge, s in BARS:
        cx = c(x); r = int(bw / 2)
        x0, x1 = int(cx - bw / 2), int(cx + bw / 2)
        ty0, ty1 = int(c(t0)), int(c(t1))
        w, h = x1 - x0, ty1 - ty0
        track = vgrad(w, h, (58, 58, 58), (38, 38, 38))
        m = Image.new('L', (w, h), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
        img.paste(track, (x0, ty0), m)
        iy0, iy1 = int(c(i0)), int(c(i1)); ih = iy1 - iy0
        glow = mix(GRAY, LAV, s)
        top, bot = (GRAY, glow) if ge == 'b' else (glow, GRAY)
        inner = vgrad(w, ih, top, bot, 2.2 if ge == 'b' else 0.45)
        m2 = Image.new('L', (w, ih), 0); ImageDraw.Draw(m2).rounded_rectangle((0, 0, w - 1, ih - 1), r, fill=255)
        img.paste(inner, (x0, iy0), m2)
        gy = iy1 - bw * .45 if ge == 'b' else iy0 + bw * .45
        ImageDraw.Draw(halo).ellipse((cx - bw * .75, gy - bw * .75, cx + bw * .75, gy + bw * .75), fill=LAV + (int(190 * s),))
    halo = halo.filter(ImageFilter.GaussianBlur(bw * .38))
    img = Image.alpha_composite(img, halo)
    return img.resize((432, 432), Image.LANCZOS)

def mono_xml():
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    c = lambda u: (1 / 6 + u * 2 / 3) * 108
    bw = BW * 2 / 3 * 108
    for x, t0, t1, *_ in BARS:
        cx = c(x); y0 = c(t0) + bw / 2; y1 = c(t1) - bw / 2
        out.append(f'    <path android:strokeColor="#FFFFFF" android:strokeWidth="{bw:.2f}" android:strokeLineCap="round" android:pathData="M{cx:.2f},{y0:.2f}L{cx:.2f},{y1:.2f}"/>')
    out.append('</vector>')
    return '\n'.join(out)

def font(sz):
    for p in ['/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', '/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf']:
        if os.path.exists(p): return ImageFont.truetype(p, sz)
    return ImageFont.load_default(sz)

def make_widget_preview(S=600):
    k = 4; N = S * k
    img = Image.new('RGBA', (N, N), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, N - 1, N - 1), int(28 / 160 * N), fill=(0, 0, 0, 255))
    m = N
    d.text((.09 * N, .175 * N), "12", font=font(int(.09 * m)), fill='white', anchor='ls')
    d.text((.09 * N, .255 * N), "Recordings", font=font(int(.072 * m)), fill=(102, 102, 102), anchor='ls')
    d.text((.09 * N, .35 * N), "47.5", font=font(int(.09 * m)), fill='white', anchor='ls')
    d.text((.09 * N, .43 * N), "min", font=font(int(.072 * m)), fill=(102, 102, 102), anchor='ls')
    cx, cy = .80 * N, .19 * N
    for r, p in [(.095, .7), (.07, .5), (.045, .8)]:
        R = r * m; sw = int(.011 * m)
        d.ellipse((cx - R, cy - R, cx + R, cy + R), outline=(61, 61, 61), width=sw)
        d.arc((cx - R, cy - R, cx + R, cy + R), -90, -90 + 360 * p, fill='white', width=int(.016 * m))
    base = .80 * N; hmax = .29 * N
    vals = [0]*3+[.5,.9,.3,.4]+[0]*8+[.6,.8,.5,.7,.4]+[0]*2+[.3,.7,1,.9,.5,.6,.35]+[0]*4+[.2,.5,.4]+[0]*5+[.3,.6,.5,.4]
    vals = (vals + [0] * 48)[:48]
    sw = max(2, int(.0055 * m)); step = N / 48
    for i, v in enumerate(vals):
        x = (i + .5) * step
        if v > 0: d.line((x, base, x, base - hmax * math.sqrt(v)), fill='white', width=sw)
        else: d.line((x, base, x, base - .02 * N), fill=(74, 74, 74), width=max(1, sw // 2))
    d.line((0, base, N, base), fill='white', width=int(.006 * m))
    for lx, t in [(.1, "12:00"), (.33, "6:00"), (.56, "12:00"), (.78, "6:00")]:
        d.text((lx * N, .875 * N), t, font=font(int(.062 * m)), fill=(90, 90, 90), anchor='mm')
    return img.resize((S, S), Image.LANCZOS)

make_icon().save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_fg.png'))
make_widget_preview().save(os.path.join(RES, 'drawable-nodpi', 'widget_preview.png'))
open(os.path.join(RES, 'drawable', 'ic_launcher_monochrome.xml'), 'w').write(mono_xml())
# Preview of the icon on its dark tile (not shipped)
tile = Image.new('RGBA', (432, 432), (20, 20, 20, 255)); tile.alpha_composite(make_icon())
tile.save('/home/claude/icon_preview.png')
