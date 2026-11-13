"""사진이 없는 장소에 보여 줄 종류별 일러스트(16:9)를 VectorDrawable로 만든다.

사용: python3 tools/icon/gen_placeholder.py <출력 폴더> [svg]
  - placeholder_attraction.xml : 하늘·해·겹겹의 언덕, 언덕 위 장소 핀
  - placeholder_course.xml     : 같은 풍경에 언덕 사이로 굽은 길과 길가 핀
  - svg를 주면 미리보기용 .svg도 함께 쓴다.
앱 아이콘(gen_icon.py)과 같은 화풍이고, 사진처럼 보이지 않도록 단순한 면으로만 그린다.
카드는 centerCrop으로 그리므로 주요 요소는 가운데에 둔다.
"""
import math, os, sys

OUT = sys.argv[1]
WITH_SVG = len(sys.argv) > 2 and sys.argv[2] == 'svg'
W, H = 160, 90


def circle(cx, cy, r):
    return (f'M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0'
            f'a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0z')

def ellipse(cx, cy, rx, ry):
    return (f'M{cx - rx:.2f},{cy:.2f}a{rx:.2f},{ry:.2f} 0 1,0 {2 * rx:.2f},0'
            f'a{rx:.2f},{ry:.2f} 0 1,0 {-2 * rx:.2f},0z')

def pin(tipx, tipy, s):
    """끝이 (tipx, tipy)인 핀 경로. s=1이면 높이 32."""
    def p(x, y):
        return f'{tipx + x * s:.2f},{tipy + y * s:.2f}'
    def r(x, y):
        return f'{x * s:.2f},{y * s:.2f}'
    return (f'M{p(0, -32)}C{p(-6.6, -32)} {p(-12, -26.6)} {p(-12, -20)}'
            f'c{r(0, 9)} {r(12, 20)} {r(12, 20)}s{r(12, -11)} {r(12, -20)}'
            f'c{r(0, -6.6)} {r(-5.4, -12)} {r(-12, -12)}z')

def bez(p0, p1, p2, p3, t):
    u = 1 - t
    return (u**3*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t**3*p3[0],
            u**3*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t**3*p3[1])


def scene(shapes, road=None):
    add = lambda path, fill, alpha=1.0: shapes.append((path, fill, alpha))
    add(f'M0,0h{W}v{H}h-{W}z', ('lin', 0, 0, 0, H, [(0, '#9AD8FF', 1), (0.6, '#5E9DF2', 1), (1, '#3D6FDC', 1)]))
    add(circle(104, 34, 30), ('rad', 104, 34, 30, [(0, '#FFF3C4', 0.9), (0.4, '#FFD27A', 0.45), (1, '#FFD27A', 0)]))
    add('M0,46 C18,38 34,36 50,40 C66,44 78,34 96,33 C116,32 134,42 160,38 L160,90 L0,90z', '#8DBBFF', 0.6)
    add('M0,56 C24,48 46,50 66,54 C90,59 112,48 136,49 C146,49 154,51 160,52 L160,90 L0,90z', '#4F82E6', 0.95)
    add('M0,68 C26,61 52,64 76,68 C100,72 126,64 160,62 L160,90 L0,90z', '#3463CF', 1)
    add('M0,80 C30,74 60,77 88,80 C114,83 138,78 160,76 L160,90 L0,90z', '#2549AE', 1)
    # 나무
    for x, y, s in ((26, 66, 1.0), (33, 67, 0.8), (136, 61, 0.9), (128, 63, 0.7)):
        add(f'M{x - 3.2 * s:.2f},{y:.2f} C{x - 3.2 * s:.2f},{y - 5 * s:.2f} {x - 1 * s:.2f},{y - 8 * s:.2f} {x:.2f},{y - 8 * s:.2f} '
            f'C{x + 1 * s:.2f},{y - 8 * s:.2f} {x + 3.2 * s:.2f},{y - 5 * s:.2f} {x + 3.2 * s:.2f},{y:.2f}z', '#2A9A6E')


def add_pin(shapes, tipx, tipy, s, halo=False):
    add = lambda path, fill, alpha=1.0: shapes.append((path, fill, alpha))
    add(ellipse(tipx, tipy + 0.5, 4.2 * s / 0.4, 1.3 * s / 0.4), '#0B1E5C', 0.28)
    if halo:
        add(circle(tipx, tipy - 20 * s, 15 * s), ('rad', tipx, tipy - 20 * s, 15 * s, [(0, '#FFFFFF', 0.5), (1, '#FFFFFF', 0)]))
    add(pin(tipx + 0.6 * s / 0.4, tipy + 0.9 * s / 0.4, s), '#0B1E5C', 0.22)
    add(pin(tipx, tipy, s), ('lin', 0, tipy - 32 * s, 0, tipy, [(0, '#FFC062', 1), (1, '#FF6B3D', 1)]))
    add(circle(tipx, tipy - 20 * s, 4.8 * s), '#FFFFFF')


def road_shapes(shapes):
    add = lambda path, fill, alpha=1.0: shapes.append((path, fill, alpha))
    segs = [((60, 96), (62, 80), (104, 80), (100, 68)), ((100, 68), (97, 59), (72, 60), (76, 52))]
    pts = []
    for s in segs:
        for k in range(40):
            pts.append(bez(*s, k / 40))
    pts.append(segs[-1][3])
    L = [0.0]
    for a, b in zip(pts, pts[1:]):
        L.append(L[-1] + math.dist(a, b))
    T = L[-1]
    def at(u):
        d = u * T
        for i in range(1, len(L)):
            if L[i] >= d:
                a, b = pts[i - 1], pts[i]
                f = (d - L[i - 1]) / max(L[i] - L[i - 1], 1e-9)
                tx, ty = b[0] - a[0], b[1] - a[1]
                m = math.hypot(tx, ty) or 1
                return (a[0] + tx * f, a[1] + ty * f), (-ty / m, tx / m)
        a, b = pts[-2], pts[-1]
        m = math.dist(a, b)
        return b, (-(b[1] - a[1]) / m, (b[0] - a[0]) / m)
    def ribbon(u0, u1, wf, steps=60, dx=0, dy=0):
        left, right = [], []
        for k in range(steps + 1):
            u = u0 + (u1 - u0) * k / steps
            (x, y), (nx, ny) = at(u)
            w = wf(u) / 2
            left.append((x + nx * w + dx, y + ny * w + dy))
            right.append((x - nx * w + dx, y - ny * w + dy))
        return 'M' + ' L'.join(f'{x:.2f},{y:.2f}' for x, y in left + right[::-1]) + 'z'
    wf = lambda u: 13 + (2.6 - 13) * u ** 0.8
    add(ribbon(0, 1, wf, dx=0.8, dy=1.4), '#0B1E5C', 0.25)
    add(ribbon(0, 1, wf), ('lin', 0, 90, 0, 50, [(0, '#FFFFFF', 1), (1, '#E6EEFF', 1)]))
    u = 0.05
    while u < 0.9:
        seg = 0.06 * (1 - 0.5 * u); gap = 0.045 * (1 - 0.5 * u)
        add(ribbon(u, min(u + seg, 0.92), lambda v: 1.5 - 0.9 * v, steps=6), '#3D6BF0')
        u += seg + gap
    return at


def export(shapes, name, comment):
    def argb(c, a):
        return f'#{int(round(a * 255)):02X}{c[1:]}'
    out = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {comment} (tools/icon/gen_placeholder.py로 생성) -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           f'    android:width="{W * 4}dp"', f'    android:height="{H * 4}dp"',
           f'    android:viewportWidth="{W}"', f'    android:viewportHeight="{H}">']
    svg_defs, svg_body = [], []
    for i, (path, fill, alpha) in enumerate(shapes):
        a = f' android:fillAlpha="{alpha}"' if alpha != 1 else ''
        if isinstance(fill, str):
            out.append(f'    <path android:pathData="{path}" android:fillColor="{fill}"{a} />')
            svg_body.append(f'<path d="{path}" fill="{fill}" fill-opacity="{alpha}"/>')
            continue
        stops = fill[-1]
        items = ''.join(f'<item android:offset="{o}" android:color="{argb(c, al)}" />' for o, c, al in stops)
        sst = ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{al}"/>' for o, c, al in stops)
        if fill[0] == 'lin':
            _, x1, y1, x2, y2, _ = fill
            g = f'android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"'
            svg_defs.append(f'<linearGradient id="g{i}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" gradientUnits="userSpaceOnUse">{sst}</linearGradient>')
        else:
            _, cx, cy, r, _ = fill
            g = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}"'
            svg_defs.append(f'<radialGradient id="g{i}" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">{sst}</radialGradient>')
        out.append(f'    <path android:pathData="{path}"{a}><aapt:attr name="android:fillColor"><gradient {g}>{items}</gradient></aapt:attr></path>')
        svg_body.append(f'<path d="{path}" fill="url(#g{i})" fill-opacity="{alpha}"/>')
    out.append('</vector>')
    os.makedirs(OUT, exist_ok=True)
    open(os.path.join(OUT, f'{name}.xml'), 'w').write('\n'.join(out) + '\n')
    if WITH_SVG:
        open(os.path.join(OUT, f'{name}.svg'), 'w').write(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W * 4}" height="{H * 4}">'
            f'<defs>{"".join(svg_defs)}</defs>{"".join(svg_body)}</svg>')


# 관광지: 언덕 위 장소 핀 하나
s = []
scene(s)
add_pin(s, 80, 56, 0.62, halo=True)
export(s, 'placeholder_attraction', '사진이 없는 관광지: 하늘과 언덕, 장소 핀')

# 코스: 굽은 길과 길가 핀, 길 끝 핀
s = []
scene(s)
at = road_shapes(s)
for u, side, sz in ((0.3, -1, 0.34), (0.62, 1, 0.3)):
    (x, y), (nx, ny) = at(u)
    off = (13 + (2.6 - 13) * u ** 0.8) / 2 + 5
    add_pin(s, x + nx * off * side, y + ny * off * side + 2, sz)
(ex, ey), _ = at(1)
add_pin(s, ex, ey + 1, 0.45, halo=True)
export(s, 'placeholder_course', '사진이 없는 여행코스: 언덕 사이로 굽은 길과 길가 핀')
print('ok')
