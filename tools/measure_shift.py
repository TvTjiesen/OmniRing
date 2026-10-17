"""量两张截图之间内容竖向移动了多少像素（自证注入链路真的滚动了）。

做法：把每张图压成"每行平均亮度"的一维剖面，再在 ±max 里找让两段剖面最吻合的位移。
一维搜索，纯 python 也很快，不需要 numpy。
用法: python measure_shift.py before.png after.png [max_shift_px]
"""
import sys
from PIL import Image, ImageStat


def profile(p, width=8):
    im = Image.open(p).convert('L')
    small = im.resize((width, im.height))
    data = list(small.getdata())          # 行主序，每行 width 个像素
    step = 255.0 * width
    prof = [sum(data[i * width:(i + 1) * width]) / step for i in range(im.height)]
    return prof, im.height


def best_shift(a, b, maxs):
    H = min(len(a), len(b))
    a = a[:H]; b = b[:H]
    # 去掉直流分量，避免整屏变暗被当成"移动"
    ma = sum(a) / H; mb = sum(b) / H
    a = [x - ma for x in a]; b = [x - mb for x in b]
    scale = Image.open(sys.argv[1]).height / float(H)
    step = max(1, int(maxs / 200))
    best = (None, None)
    for s in range(-maxs, maxs + 1, step):
        lo = max(0, s); hi = min(H, H + s)
        if hi - lo < 50:
            continue
        tot = 0.0
        for y in range(lo, hi):
            tot += (a[y] - b[y - s]) ** 2
        d = tot / (hi - lo)
        if best[1] is None or d < best[1]:
            best = (s, d)
    return best[0] * scale, best[1]


def main():
    maxs = int(sys.argv[3]) if len(sys.argv) > 3 else 900
    pa, _ = profile(sys.argv[1])
    pb, _ = profile(sys.argv[2])
    s, d = best_shift(pa, pb, maxs)
    direction = "内容向上走（=往下滚）" if s > 0 else ("内容向下走（=往上滚）" if s < 0 else "没动")
    print(f"{sys.argv[1].split('/')[-1]} -> {sys.argv[2].split('/')[-1]}: 竖向位移 ≈ {s:+.0f}px  残差={d:.5f}  {direction}")


main()
