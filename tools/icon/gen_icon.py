"""굽은 길 + 길가 핀 아이콘을 도형 목록으로 만들고 SVG(미리보기)와 Android VectorDrawable로 내보낸다.

좌표는 108x108 적응형 아이콘 기준. 런처는 가운데 72(18..90)만 보여 주므로 주요 요소는 그 안에 둔다.
VectorDrawable로 옮길 수 있게 path, 선형·원형 그라데이션, 투명도만 쓴다(점선·클립·필터 없음).
"""
import math, sys, os

OUT = sys.argv[1]
VARIANT = sys.argv[2] if len(sys.argv) > 2 else 'v1'
PINS = eval(sys.argv[3]) if len(sys.argv) > 3 else ((35, 84, 0.38), (77, 62, 0.34), (29, 52, 0.3))


# ---- 기하 ----
def bez(p0, p1, p2, p3, t):
    u = 1 - t
    return (u**3*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t**3*p3[0],
            u**3*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t**3*p3[1])

SEGS = [
    ((46, 122), (46, 100), (78, 102), (75, 82)),
    ((75, 82), (72, 66), (33, 74), (35, 59)),
    ((35, 59), (37, 49), (49, 47), (55, 44)),
]

def centerline(n=240):
    pts = []
    for i, s in enumerate(SEGS):
        for k in range(n // len(SEGS)):
            pts.append(bez(*s, k / (n // len(SEGS))))
    pts.append(SEGS[-1][3])
    return pts

C = centerline()
# 누적 길이로 0..1 위치를 정한다
L = [0.0]
for a, b in zip(C, C[1:]):
    L.append(L[-1] + math.dist(a, b))
TOTAL = L[-1]

def at(u):
    """길 시작(0)~끝(1) 위치의 점과 단위 법선."""
    d = u * TOTAL
    for i in range(1, len(L)):
        if L[i] >= d:
            a, b = C[i - 1], C[i]
            f = (d - L[i - 1]) / max(L[i] - L[i - 1], 1e-9)
            x, y = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
            tx, ty = b[0] - a[0], b[1] - a[1]
            m = math.hypot(tx, ty) or 1
            return (x, y), (-ty / m, tx / m)
    a, b = C[-2], C[-1]
    tx, ty = b[0] - a[0], b[1] - a[1]
    m = math.hypot(tx, ty)
    return b, (-ty / m, tx / m)

def width(u, w0=17.0, w1=4.2):
    # 가까울수록 넓게(원근)
    return w0 + (w1 - w0) * (u ** 0.8)

def ribbon(u0, u1, wf, steps=80, dx=0.0, dy=0.0):
    left, right = [], []
    for k in range(steps + 1):
        u = u0 + (u1 - u0) * k / steps
        (x, y), (nx, ny) = at(u)
        w = wf(u) / 2
        left.append((x + nx * w + dx, y + ny * w + dy))
        right.append((x - nx * w + dx, y - ny * w + dy))
    pts = left + right[::-1]
    return 'M' + ' L'.join(f'{x:.2f},{y:.2f}' for x, y in pts) + 'z'

def round_cap(u, wf, dx=0.0, dy=0.0):
    (x, y), _ = at(u)
    return circle(x + dx, y + dy, wf(u) / 2)

def circle(cx, cy, r):
    return (f'M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0'
            f'a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0z')

def ellipse(cx, cy, rx, ry):
    return (f'M{cx - rx:.2f},{cy:.2f}a{rx:.2f},{ry:.2f} 0 1,0 {2 * rx:.2f},0'
            f'a{rx:.2f},{ry:.2f} 0 1,0 {-2 * rx:.2f},0z')

PIN = 'M0,-32C-6.6,-32 -12,-26.6 -12,-20c0,9 12,20 12,20s12,-11 12,-20c0,-6.6 -5.4,-12 -12,-12z'

def pin_path(tipx, tipy, s):
    """끝이 (tipx, tipy)인 핀. s=1이면 높이 32."""
    # PIN 좌표를 직접 변환해 path 하나로 만든다(그룹 변환 없이).
    import re
    out = []
    tokens = re.findall(r'[MCcsz]|-?\d+\.?\d*', PIN)
    i = 0
    cmd = None
    rel = False
    nums = []
    def fmt(v):
        return f'{v:.2f}'
    res = ''
    while i < len(tokens):
        t = tokens[i]
        if t in 'MCcsz':
            cmd = t
            res += t
            i += 1
            if t == 'z':
                continue
            continue
        # 숫자 쌍
        x = float(tokens[i]); y = float(tokens[i + 1]); i += 2
        if cmd in 'MC':
            res += f'{tipx + x * s:.2f},{tipy + y * s:.2f} '
        else:
            res += f'{x * s:.2f},{y * s:.2f} '
    return res.replace(' c', 'c').replace(' s', 's').replace(' z', 'z').strip()


# ---- 도형 목록: (path, fill) ; fill = '#RRGGBB' | ('lin', x1,y1,x2,y2,[(off,color,alpha)]) | ('rad', cx,cy,r,[...]) ; alpha
shapes = []
def add(path, fill, alpha=1.0, layer='fg'):
    shapes.append((layer, path, fill, alpha))

FULL = 'M0,0h108v108h-108z'

# 배경: 하늘
add(FULL, ('lin', 0, 0, 0, 108, [(0, '#7FD6FF', 1), (0.55, '#3D86F5', 1), (1, '#2347D6', 1)]), layer='bg')
# 해(길 끝 뒤쪽)
add(circle(60, 40, 26), ('rad', 60, 40, 26, [(0, '#FFF3C4', 0.95), (0.35, '#FFD27A', 0.55), (1, '#FFD27A', 0)]), layer='bg')
# 먼 산·언덕(뒤에서 앞으로 진해짐)
add('M0,56 C12,48 22,46 32,50 C42,54 50,44 62,42 C76,40 86,50 108,46 L108,108 L0,108z', '#7FB2FF', 0.55, layer='bg')
add('M0,64 C16,56 30,58 44,62 C60,67 76,56 108,58 L108,108 L0,108z', '#3E72E6', 0.9, layer='bg')
add('M0,78 C20,70 40,74 58,78 C78,83 94,74 108,72 L108,108 L0,108z', '#2A52C9', 1, layer='bg')
add('M0,94 C22,86 46,90 66,94 C84,97 98,92 108,90 L108,108 L0,108z', '#1C3A9E', 1, layer='bg')

# 길 그림자·길·가장자리 빛·차선
wf = width
add(ribbon(0, 1, wf, dx=1.0, dy=1.8), '#0B1E5C', 0.28)
add(round_cap(1, wf, 1.0, 1.8), '#0B1E5C', 0.28)
add(ribbon(0, 1, wf), ('lin', 0, 108, 0, 30, [(0, '#FFFFFF', 1), (1, '#E6EEFF', 1)]))
add(round_cap(1, wf), '#E6EEFF')
# 차선(점선): 짧은 리본 조각들. 멀수록 짧고 촘촘하게
u = 0.04
while u < 0.93:
    seg = 0.045 * (1 - 0.55 * u)
    gap = 0.035 * (1 - 0.55 * u)
    add(ribbon(u, min(u + seg, 0.95), lambda v: 1.9 - 1.1 * v, steps=8), '#3D6BF0')
    u += seg + gap

# 길가 핀: (길 위치 u, 바깥쪽 방향 부호, 크기)
PIN_FILL = ('lin', 0, 0, 0, 1, [(0, '#FFB45A', 1), (1, '#FF6A3D', 1)])
def add_pin(tipx, tipy, s, ground=True, halo=False):
    top = tipy - 32 * s
    grad = ('lin', 0, top, 0, tipy, [(0, '#FFC062', 1), (1, '#FF6B3D', 1)])
    if ground:
        add(ellipse(tipx, tipy + 0.6, 3.6 * s / 0.4, 1.2 * s / 0.4), '#0B1E5C', 0.3)
    if halo:
        add(circle(tipx, tipy - 20 * s, 15 * s), ('rad', tipx, tipy - 20 * s, 15 * s,
                                                     [(0, '#FFFFFF', 0.55), (1, '#FFFFFF', 0)]))
    add(pin_path(tipx + 0.6 * s / 0.4, tipy + 0.9 * s / 0.4, s), '#0B1E5C', 0.25)
    add(pin_path(tipx, tipy, s), grad)
    add(circle(tipx, tipy - 20 * s, 4.8 * s), '#FFFFFF')

# 길가 핀: 끝 좌표를 직접 정한다(길 양쪽에 번갈아, 아래에서 위로 갈수록 작게)
for tx, ty, s in PINS:
    add_pin(tx, ty, s)
(ex, ey), _ = at(1)
add_pin(ex, ey + 1.5, 0.5, ground=False, halo=True)


# ---- 내보내기 ----
def svg_fill(fill, idx):
    if isinstance(fill, str):
        return f'fill="{fill}"', ''
    kind = fill[0]
    gid = f'g{idx}'
    if kind == 'lin':
        _, x1, y1, x2, y2, stops = fill
        st = ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops)
        return f'fill="url(#{gid})"', f'<linearGradient id="{gid}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" gradientUnits="userSpaceOnUse">{st}</linearGradient>'
    _, cx, cy, r, stops = fill
    st = ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops)
    return f'fill="url(#{gid})"', f'<radialGradient id="{gid}" cx="{cx}" cy="{cy}" r="{r}" gradientUnits="userSpaceOnUse">{st}</radialGradient>'

def to_svg(layers):
    defs, body = [], []
    for i, (layer, path, fill, alpha) in enumerate(shapes):
        if layer not in layers:
            continue
        f, d = svg_fill(fill, i)
        if d:
            defs.append(d)
        body.append(f'<path d="{path}" {f} fill-opacity="{alpha}"/>')
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">'
            f'<defs>{"".join(defs)}</defs>{"".join(body)}</svg>')

def argb(c, a):
    return f'#{int(round(a * 255)):02X}{c[1:]}'

def to_vd(layer, comment):
    out = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {comment} (tools/icon/gen_icon.py로 생성) -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for (lay, path, fill, alpha) in shapes:
        if lay != layer:
            continue
        if isinstance(fill, str):
            a = f' android:fillAlpha="{alpha}"' if alpha != 1 else ''
            out.append(f'    <path android:pathData="{path}" android:fillColor="{fill}"{a} />')
            continue
        kind = fill[0]
        if kind == 'lin':
            _, x1, y1, x2, y2, stops = fill
            g = f'android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"'
        else:
            _, cx, cy, r, stops = fill
            g = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}"'
        items = ''.join(f'<item android:offset="{o}" android:color="{argb(c, a)}" />' for o, c, a in stops)
        a = f' android:fillAlpha="{alpha}"' if alpha != 1 else ''
        out.append(f'    <path android:pathData="{path}"{a}><aapt:attr name="android:fillColor"><gradient {g}>{items}</gradient></aapt:attr></path>')
    out.append('</vector>')
    return '\n'.join(out) + '\n'

os.makedirs(OUT, exist_ok=True)
open(os.path.join(OUT, f'{VARIANT}.svg'), 'w').write(to_svg({'bg', 'fg'}))
open(os.path.join(OUT, f'{VARIANT}_bg.xml'), 'w').write(to_vd('bg', '앱 아이콘 배경: 하늘과 해, 겹겹의 언덕'))
open(os.path.join(OUT, f'{VARIANT}_fg.xml'), 'w').write(to_vd('fg', '앱 아이콘 전경: 굽은 길과 길가의 추천 장소 핀, 길 끝 목적지 핀'))
print('ok', len(shapes))
