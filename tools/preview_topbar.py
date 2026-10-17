#!/usr/bin/env python3
"""
顶栏模糊方向的 PC 预演：把三种"渐变模糊"画在同一个模拟内容背景上，
让你直接选，而不是我猜一版你否一版。

A = 现在装机这版（均匀模糊 + 不透明色渐变遮罩）—— 你说"雷霆大模糊、把 logo 全遮住"
B = 自上而下 由浅到深（你字面描述的那个方向）
C = 自上而下 由深到浅（iOS 导航条的标准做法：内容往上走时逐渐化开）

每种都叠上"悬浮液态玻璃胶囊"（标题一颗、右上角动作一颗），
因为无论选哪个方向，文字和按钮都不再躺在一条色带上。

用法：python3 tools/preview_topbar.py  → 生成 _topbar_preview.png
"""
import os
from PIL import Image, ImageDraw, ImageFilter

W, H = 1200, 700          # 只画顶部这一段
STATUS, BAR, TAIL = 100, 156, 200
BAND = STATUS + BAR + TAIL
STEPS = 6                 # 渐变模糊的分层数
MAX_R = 26


def content_bg():
    """模拟一屏浅色+莫奈粉的内容：卡片、文字条、大标题、一个圆 logo"""
    img = Image.new('RGB', (W, H), (250, 246, 247))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, H], fill=(250, 246, 247))
    # 大标题
    d.ellipse([60, 150, 190, 280], fill=(120, 60, 90))
    d.rectangle([220, 180, 700, 250], fill=(60, 40, 50))
    y = 320
    for i, (h, col) in enumerate([(60, (190, 150, 170)), (150, (247, 224, 232)),
                                  (60, (190, 150, 170)), (150, (247, 224, 232)),
                                  (60, (190, 150, 170)), (150, (247, 224, 232))]):
        d.rounded_rectangle([40, y, W - 40, y + h], radius=26,
                            fill=col if i % 2 else (255, 255, 255),
                            outline=(230, 215, 220))
        if i % 2 == 0:
            d.rectangle([70, y + 18, y and 420, y + 40], fill=(120, 100, 110))
        else:
            d.rectangle([70, y + 26, 520, y + 52], fill=(90, 70, 80))
            d.rectangle([70, y + 66, 760, y + 86], fill=(170, 150, 160))
            d.ellipse([W - 180, y + 30, W - 90, y + 120], fill=(150, 60, 100))
        y += h + 28
    return img


def gradient_blur(bg, top_shallow, strips=STEPS, max_r=MAX_R):
    """
    分层近似渐变模糊：把条带切成 strips 段，每段各自高斯模糊，
    再用竖向 alpha 渐变交叉淡入，避免出现"一条硬边"。
    """
    out = bg.copy()
    layer = Image.new('RGBA', bg.size, (0, 0, 0, 0))
    for i in range(strips):
        t0 = i / strips
        t1 = (i + 1) / strips
        y0 = int(BAND * t0) - 40          # 上下各留一点重叠区，交叉淡化用
        y1 = int(BAND * t1) + 40
        r = (t0 if top_shallow else 1 - t1)
        radius = max(0.5, max_r * (abs(r) ** 1.3))
        crop = bg.crop((0, max(0, y0), W, min(H, y1))).filter(ImageFilter.GaussianBlur(radius))
        a = Image.new('L', crop.size, 0)
        ad = ImageDraw.Draw(a)
        # 段内两端 alpha 收零 → 与相邻段交叉淡入
        n = crop.size[1]
        for yy in range(n):
            k = min(yy / (n * 0.42), 1.0) * min((n - yy) / (n * 0.42), 1.0)
            ad.line([(0, yy), (W, yy)], fill=int(255 * max(0.0, min(1.0, k))))
        rgba = crop.convert('RGBA')
        rgba.putalpha(a)
        layer.paste(rgba, (0, max(0, y0)), rgba)
    out.paste(Image.alpha_composite(out.convert('RGBA'), layer).convert('RGB'), (0, 0))
    return out


def scrim(bg, opaque_from_top):
    """A 方案那种不透明色渐变（问题所在：整块被奶白盖住）"""
    d = ImageDraw.Draw(bg, 'RGBA')
    for y in range(BAND):
        t = y / BAND
        a = int(235 * (1 - t) ** 1.2) if not opaque_from_top else int(235 * t)
        d.line([(0, y), (W, y)], fill=(252, 248, 249, a))
    return bg


def glass_pill(img, box, radius=None, text=None, tint=(255, 255, 255)):
    """悬浮液态玻璃胶囊：底下内容模糊 + 半透白 + 上缘高光 + 下缘内阴影"""
    x0, y0, x1, y1 = box
    r = radius if radius is not None else (y1 - y0) // 2
    crop = img.crop(box).filter(ImageFilter.GaussianBlur(9))
    mask = Image.new('L', crop.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, crop.size[0] - 1, crop.size[1] - 1], radius=r, fill=255)
    rgba = crop.convert('RGBA')
    d = ImageDraw.Draw(rgba, 'RGBA')
    d.rounded_rectangle([0, 0, crop.size[0] - 1, crop.size[1] - 1], radius=r, fill=(*tint, 66))
    # 上缘高光、下缘暗边（玻璃的厚度感）
    d.arc([0, 0, crop.size[0] - 1, crop.size[1] - 1], 200, 340, fill=(255, 255, 255, 150), width=3)
    rgba.putalpha(Image.composite(mask, Image.new('L', mask.size, 0), mask))
    img.paste(rgba, (x0, y0), rgba)
    d2 = ImageDraw.Draw(img, 'RGBA')
    d2.rounded_rectangle([x0, y0, x1, y1], radius=r, outline=(255, 255, 255, 120), width=2)
    if text:
        d2.text((x0 + 34, y0 + 26), text, fill=(60, 40, 50))
    return img


def build():
    rows = []
    # A：装机这版
    a = gradient_blur(content_bg(), top_shallow=False, max_r=MAX_R)
    a = scrim(a, opaque_from_top=False)
    rows.append(('A  现在这版：均匀模糊 + 不透明遮罩（你说的问题）', a))
    # B：自上而下 由浅到深
    b = gradient_blur(content_bg(), top_shallow=True)
    glass_pill(b, (40, 150, 470, 250), text='万能变焦环')
    glass_pill(b, (1060, 160, 1160, 260), text=None)
    rows.append(('B  自上而下 由浅到深（你字面说的方向）+ 悬浮玻璃胶囊', b))
    # C：自上而下 由深到浅（iOS 标准）
    c = gradient_blur(content_bg(), top_shallow=False)
    glass_pill(c, (40, 150, 470, 250), text='万能变焦环')
    glass_pill(c, (1060, 160, 1160, 260), text=None)
    rows.append(('C  自上而下 由深到浅（iOS 导航条标准）+ 悬浮玻璃胶囊', c))

    label_h = 46
    out = Image.new('RGB', (W, (H + label_h) * len(rows) + 20 * (len(rows) - 1)), (24, 24, 28))
    d = ImageDraw.Draw(out)
    y = 0
    for text, img in rows:
        d.rectangle([0, y, W, y + label_h], fill=(12, 12, 16))
        d.text((14, y + 16), text, fill=(235, 235, 240))
        out.paste(img, (0, y + label_h))
        y += H + label_h + 20
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '_topbar_preview.png')
    out.save(path)
    print('preview:', os.path.abspath(path))


if __name__ == '__main__':
    build()
