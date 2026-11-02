#!/usr/bin/env python3
"""
出一张 512×512 的商店用图标 PNG（LSPosed 仓库里没有单独传图标的字段，但问元数据规范 /
发 issue / 商店详情页经常要一张方图，这里按 APK 里那套自适应图标**同样的几何**烘一张）。

为什么不是简单把 `ic_launcher_foreground.xml` 拉满：自适应图标是 108dp 图层、只露中间 72dp，
系统会按 108/72 = 1.5 放大再裁圆。所以这里也按"可见区 72dp 居中、铺满画布"来画，
出来的就是用户在桌面上真正看到的那张图；直接按 108 画会小一圈。

几何与配色全部照抄 `tools/gen_icon.py`（同一份设计，改那边记得同步这边）：
  background  斜向石墨渐变 #232B3D → #0A0D14 + 顶部冷光径向 #33FFFFFF → #00FFFFFF
  foreground  玻璃面 r19.2 #0B0F18 / 外圈 r28.6 #8FA0B8 w1.1 / 滚花 30 齿 r21.8→27.6 #F1F5FB w2.05
              / 内环弧 r13.4 20°→340° #DCE6F5 w2.3 / 红点 r2.2 贴 CY-24.7 #E5322B

用法：`python tools/gen_store_icon.py`（默认出 build/store_icon_512.png；`--size` 改边长）
"""
import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw

CX, CY = 54.0, 54.0          # 图层坐标系中心
VIEWPORT = 108.0             # 自适应图标图层边长
VISIBLE = 72.0               # 真正露出来的安全区（系统放大 108/72 后裁切）
SS = 4                       # 超采样倍率，最后缩回去当抗锯齿

METAL = "#F1F5FB"
RIM = "#8FA0B8"
DIAL = "#0B0F18"
INNER = "#DCE6F5"
RED = "#E5322B"
BG_FROM = (0x23, 0x2B, 0x3D)
BG_TO = (0x0A, 0x0D, 0x14)
GLOW = (255, 255, 255)


def _rgba(hex_color: str) -> tuple[int, int, int, int]:
    h = hex_color.lstrip("#")
    if len(h) == 8:  # #AARRGGBB
        a, r, g, b = h[0:2], h[2:4], h[4:6], h[6:8]
        return int(r, 16), int(g, 16), int(b, 16), int(a, 16)
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255


def render(size: int = 512) -> Image.Image:
    canvas = size * SS
    scale = canvas / VISIBLE                       # 可见区 72dp 铺满画布
    # 图层坐标 → 画布像素
    def px(v: float) -> float:
        return (v - CX) * scale + canvas / 2.0

    # ---- 背景：整张图画布按图层坐标算渐变，再裁到画布 ----
    ys, xs = np.mgrid[0:canvas, 0:canvas].astype(np.float32)
    lx = (xs - canvas / 2.0) / scale + CX
    ly = (ys - canvas / 2.0) / scale + CY
    t = (lx + ly) / (2.0 * VIEWPORT)               # 斜向 0..1，#000 起 #FFF 止
    t = np.clip(t, 0.0, 1.0)[..., None]
    rgb = np.array(BG_FROM, np.float32) * (1.0 - t) + np.array(BG_TO, np.float32) * t
    # 顶部冷光：center (54,34) r=64 线性衰减
    d = np.sqrt((lx - CX) ** 2 + (ly - (CY - 20.0)) ** 2)
    glow = np.clip(1.0 - d / 64.0, 0.0, 1.0) * (0x33 / 255.0)
    rgb = rgb * (1.0 - glow[..., None]) + np.array(GLOW, np.float32) * glow[..., None]
    bg = Image.fromarray(np.dstack([rgb, np.full_like(rgb[..., :1], 255.0)]).astype(np.uint8), "RGBA")
    img = bg

    layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    d_ = ImageDraw.Draw(layer)

    def circle(r: float, cx: float = CX, cy: float = CY, **kw) -> None:
        box = [px(cx - r), px(cy - r), px(cx + r), px(cy + r)]
        d_.ellipse(box, **kw)

    # 玻璃面
    circle(19.2, fill=_rgba(DIAL))
    # 外圈
    circle(28.6, outline=_rgba(RIM), width=max(1, round(1.1 * scale)))
    # 滚花齿（30 条径向短线，圆头）
    w = max(1, round(2.05 * scale))
    cap = w / 2.0
    for i in range(30):
        a = math.radians(360.0 * i / 30)
        dx, dy = math.cos(a), math.sin(a)
        x0, y0 = px(CX + 21.8 * dx), px(CY + 21.8 * dy)
        x1, y1 = px(CX + 27.6 * dx), px(CY + 27.6 * dy)
        d_.line([x0, y0, x1, y1], fill=_rgba(METAL), width=w)
        for (x, y) in ((x0, y0), (x1, y1)):
            d_.ellipse([x - cap, y - cap, x + cap, y + cap], fill=_rgba(METAL))
    # 内环：右侧留缺口 = "C"（Crown）。PIL 的 arc 角度就是 0° 指向 +x、顺时针为正，
    # 与 VectorDrawable 里 (cos, sin) 的算法一致，所以 20°→340° 直接照抄。
    r13 = 13.4
    d_.arc(
        [px(CX - r13), px(CY - r13), px(CX + r13), px(CY + r13)],
        start=20, end=340, fill=_rgba(INNER), width=max(1, round(2.3 * scale)),
    )
    # 红点
    circle(2.2, CX, CY - 24.7, fill=_rgba(RED))

    img = Image.alpha_composite(img, layer)
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    out = args.out or os.path.join(here, "..", "build", f"store_icon_{args.size}.png")
    out = os.path.normpath(out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    render(args.size).convert("RGB").save(out, "PNG")
    print("wrote", out, os.path.getsize(out), "B")


if __name__ == "__main__":
    main()