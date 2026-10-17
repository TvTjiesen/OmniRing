#!/usr/bin/env python3
"""
在 PC 上预览启动图标：用和 tools/gen_icon.py 完全相同的几何画一遍，
套上主流桌面的蒙版（圆形 / 超椭圆 squircle / 圆角方），再贴到一张壁纸上看整体效果。

只用于肉眼验收，不进 APK。真机效果以桌面截图为准。
"""
import math
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_icon import CX, CY, METAL, RIM, DIAL, INNER, RED, knurl  # noqa: E402

SIZE = 432                      # 108dp * 4
SCALE = SIZE / 108.0
SS = 4                          # 超采样，抗锯齿


def px(v: float) -> float:
    return v * SCALE


def hexrgb(s: str):
    s = s.lstrip("#")
    a = int(s[0:2], 16) if len(s) == 8 else 255
    if len(s) == 8:
        s = s[2:]
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def new_layer():
    return Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))


def draw_background():
    img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    top, bot = hexrgb("#232B3D"), hexrgb("#0A0D14")
    w, h = img.size
    base = Image.new("RGB", (w, h))
    for y in range(h):
        # 斜向：把 (x+y)/(2h) 当进度
        pass
    px_data = []
    for y in range(h):
        for x in range(0, w, 8):
            t = (x + y) / (2.0 * (w - 1))
            c = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3))
            px_data.extend([c] * 8)
    # 逐行拼接（够用，预览不必极致）
    row_y = 0
    for i in range(0, len(px_data), w):
        row = Image.new("RGB", (w, 1))
        row.putdata(px_data[i:i + w])
        base.paste(row, (0, row_y))
        row_y += 1
    img = base.convert("RGBA")

    # 顶部一团冷光
    glow = Image.new("L", (w, h), 0)
    gd = ImageDraw.Draw(glow)
    cx, cy, rad = px(54) * SS, px(34) * SS, px(64) * SS
    for r in range(int(rad), 0, -2):
        v = int(255 * 0.20 * (1.0 - r / rad) ** 2)
        gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=v)
    white = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    white.putalpha(glow)
    img.alpha_composite(white)
    return img


def draw_foreground(monochrome=False):
    img = new_layer()
    d = ImageDraw.Draw(img)
    s = SS

    def P(v):
        return px(v) * s

    def C(cx=CX, cy=CY):
        return P(cx), P(cy)

    def line(p0, p1, color, width):
        rgb = hexrgb(color)
        w = max(1, int(round(P(width) * s / SS)))
        d.line([p0, p1], fill=rgb, width=w)
        # 圆头
        for p in (p0, p1):
            d.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill=rgb)

    def ring(r, color, width, a0=None, a1=None):
        rgb = hexrgb(color)
        bb = [C()[0] - P(r), C()[1] - P(r), C()[0] + P(r), C()[1] + P(r)]
        w = max(1, int(round(P(width) * s / SS)))
        if a0 is None:
            d.ellipse(bb, outline=rgb, width=w)
        else:
            d.arc(bb, start=a0, end=a1, fill=rgb, width=w)

    if monochrome:
        for k in range(30):
            a = math.radians(360.0 * k / 30)
            dx, dy = math.cos(a), math.sin(a)
            line((C()[0] + P(21.8) * dx, C()[1] + P(21.8) * dy),
                 (C()[0] + P(27.6) * dx, C()[1] + P(27.6) * dy), "#FFFFFFFF", 2.0)
        ring(13.4, "#FFFFFFFF", 2.4, a0=20, a1=340)
        cx, cy = C(CX, CY - 24.7)
        d.ellipse([cx - P(2.9), cy - P(2.9), cx + P(2.9), cy + P(2.9)], fill=(255, 255, 255, 255))
        return img

    # 玻璃面
    cx, cy = C()
    d.ellipse([cx - P(19.2), cy - P(19.2), cx + P(19.2), cy + P(19.2)], fill=hexrgb(DIAL))
    # 外圈
    ring(28.6, RIM, 1.1)
    # 滚花
    for k in range(30):
        a = math.radians(360.0 * k / 30)
        dx, dy = math.cos(a), math.sin(a)
        line((cx + P(21.8) * dx, cy + P(21.8) * dy), (cx + P(27.6) * dx, cy + P(27.6) * dy), METAL, 2.05)
    # 内对焦环（右侧留缺口）
    ring(13.4, INNER, 2.3, a0=20, a1=340)
    # 红点
    rcx, rcy = C(CX, CY - 24.7)
    d.ellipse([rcx - P(2.2), rcy - P(2.2), rcx + P(2.2), rcy + P(2.2)], fill=hexrgb(RED))
    return img


def mask(img, kind):
    w, h = img.size
    m = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(m)
    if kind == "circle":
        md.ellipse([0, 0, w - 1, h - 1], fill=255)
    elif kind == "square":
        r = int(w * 0.22)
        md.rounded_rectangle([0, 0, w - 1, h - 1], radius=r, fill=255)
    else:  # squircle（超椭圆，近似鸿蒙/MIUI 的圆角矩形）
        n = 6.0
        for x in range(w):
            t = abs((x - w / 2) / (w / 2))
            if t > 1:
                continue
            y = (h / 2) * (1 - t ** n) ** (1 / n)
            md.line([(x, h / 2 - y), (x, h / 2 + y)], fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


def main():
    bg = draw_background()
    fg = draw_foreground()
    mono = draw_foreground(monochrome=True)
    full = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    full.alpha_composite(bg)
    full.alpha_composite(fg)
    full_mono = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    full_mono.alpha_composite(bg)
    full_mono.alpha_composite(mono)

    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_icon_preview.png")
    disp = 168                      # 屏幕上每个图标多大
    gap = 24
    canvas = Image.new("RGB", (disp * 3 + gap * 4, disp * 2 + gap * 3), (233, 235, 240))
    cd = ImageDraw.Draw(canvas)

    def paste(im, x, y):
        small = im.resize((disp, disp), Image.LANCZOS)   # 超采样图缩到显示尺寸
        canvas.paste(small, (x, y), small)

    for i, kind in enumerate(["circle", "squircle", "square"]):
        paste(mask(full, kind), gap + i * (disp + gap), gap)
    paste(mask(full_mono, "circle"), gap, gap * 2 + disp)
    cd.text((gap * 2 + disp, gap * 2 + disp + 6), "monochrome", fill=(40, 44, 56))
    canvas.save(root)
    print("preview:", os.path.abspath(root))


if __name__ == "__main__":
    main()
