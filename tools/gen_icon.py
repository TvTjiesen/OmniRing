#!/usr/bin/env python3
"""
生成 RingCrown 的自适应启动图标（VectorDrawable，任意密度都锐利）。

图层：
  ic_launcher_background.xml  石墨底 + 顶部一团冷暖交界的微光
  ic_launcher_foreground.xml  表冠本体：滚花齿 + 外圈高光 + 暗玻璃面 + 内对焦环 + 红点 + 玻璃反光弧
  ic_launcher_monochrome.xml  同一套几何的单色版（Android 13+ 主题图标 / 抽屉里的圆形蒙版）

安全区：中心 66dp 直径。滚花齿画到 r=27.5，正好贴着安全圆，蒙成圆形也不会被裁掉。
改完直接 `python3 tools/gen_icon.py` 重新生成，产物在 app/src/main/res 下。
"""
import math
import os

CX, CY = 54.0, 54.0
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

METAL = "#F1F5FB"      # 齿尖高光
RIM = "#8FA0B8"        # 外圈
DIAL = "#0B0F18"       # 玻璃面
INNER = "#DCE6F5"      # 内对焦环
RED = "#E5322B"        # 红点（致敬徕卡标）
GLOSS = "#FFFFFF"


def n(v: float) -> str:
    return ("%.2f" % v).rstrip("0").rstrip(".")


def circle(r: float, cx: float = CX, cy: float = CY) -> str:
    """整圆路径：两段弧拼起来，VectorDrawable 的 arc 命令支持良好。"""
    return (
        f"M {n(cx - r)},{n(cy)} "
        f"a {n(r)},{n(r)} 0 1,1 {n(2 * r)},0 "
        f"a {n(r)},{n(r)} 0 1,1 {n(-2 * r)},0 Z"
    )


def arc(r: float, a0: float, a1: float, cx: float = CX, cy: float = CY) -> str:
    """从角度 a0 到 a1（度，逆时针为正，0 度指向 +x）画一段弧。"""
    p0 = (cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0)))
    p1 = (cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1)))
    large = 1 if abs(a1 - a0) > 180 else 0
    sweep = 1 if a1 > a0 else 0
    return (
        f"M {n(p0[0])},{n(p0[1])} "
        f"A {n(r)},{n(r)} 0 {large},{sweep} {n(p1[0])},{n(p1[1])}"
    )


def knurl(count: int, r0: float, r1: float) -> str:
    """滚花：每条一个从 r0 到 r1 的径向短线，圆头。"""
    parts = []
    for i in range(count):
        a = math.radians(360.0 * i / count)
        dx, dy = math.cos(a), math.sin(a)
        parts.append(
            f"M {n(CX + r0 * dx)},{n(CY + r0 * dy)} L {n(CX + r1 * dx)},{n(CY + r1 * dy)}"
        )
    return " ".join(parts)


HEADER = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!-- 由 tools/gen_icon.py 生成；手改会被覆盖 -->\n'
)


def vector(body: str) -> str:
    return (
        HEADER
        + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        + '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        + '    android:width="108dp"\n'
        + '    android:height="108dp"\n'
        + '    android:viewportWidth="108"\n'
        + '    android:viewportHeight="108">\n'
        + body
        + "</vector>\n"
    )


def stroke_path(data: str, color: str, width: float, cap: str = "round") -> str:
    return (
        f'    <path android:pathData="{data}"\n'
        f'        android:strokeColor="{color}"\n'
        f'        android:strokeWidth="{n(width)}"\n'
        f'        android:strokeLineCap="{cap}" />\n'
    )


def fill_path(data: str, color: str) -> str:
    return f'    <path android:pathData="{data}" android:fillColor="{color}" />\n'


def background() -> str:
    return vector(
        # 底层：斜向石墨渐变
        '    <path android:pathData="M0,0h108v108h-108z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient\n'
        '                android:type="linear"\n'
        '                android:startX="0" android:startY="0"\n'
        '                android:endX="108" android:endY="108"\n'
        '                android:startColor="#232B3D" android:endColor="#0A0D14" />\n'
        '        </aapt:attr>\n'
        '    </path>\n'
        # 顶部一团冷光，让整块底不至于死黑（玻璃质感要有反射源）
        '    <path android:pathData="M0,0h108v108h-108z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient\n'
        '                android:type="radial"\n'
        '                android:centerX="54" android:centerY="34"\n'
        '                android:gradientRadius="64"\n'
        '                android:startColor="#33FFFFFF" android:endColor="#00FFFFFF" />\n'
        '        </aapt:attr>\n'
        '    </path>\n'
    )


def foreground(monochrome: bool = False) -> str:
    body = ""
    if monochrome:
        # 单色版只留三个元素：滚花 + 内环 + 红点位置。
        # 之前还画了一圈外圈，结果和齿尖叠成两层线，小尺寸下发糊。
        body += stroke_path(knurl(30, 21.8, 27.6), "#FFFFFFFF", 2.0)
        body += stroke_path(arc(13.4, 20, 340), "#FFFFFFFF", 2.4)
        body += fill_path(circle(2.2), "#FFFFFFFF")  # 单色版放中心：齿是白的，边上再点一颗白点会糊在一起
        return vector(body)
    # 玻璃面
    body += fill_path(circle(19.2), DIAL)
    # 外圈
    body += stroke_path(circle(28.6), RIM, 1.1)
    # 滚花齿
    body += stroke_path(knurl(30, 21.8, 27.6), METAL, 2.05)
    # 内环右侧留缺口：整读起来是个 "C"（Crown），比同心圆有识别度
    body += stroke_path(arc(13.4, 20, 340), INNER, 2.3)
    # 红点：贴在滚花带上，代替刻度起点
    body += fill_path(circle(2.2, CX, CY - 24.7), RED)
    return vector(body)


def main() -> None:
    for name, text in [
        ("drawable/ic_launcher_background.xml", background()),
        ("drawable/ic_launcher_foreground.xml", foreground(False)),
        ("drawable/ic_launcher_monochrome.xml", foreground(True)),
    ]:
        path = os.path.join(OUT, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote", path)

    adaptive = (
        HEADER
        + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        + '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        + '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        + '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        + "</adaptive-icon>\n"
    )
    for name in ("mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml"):
        path = os.path.join(OUT, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(adaptive)
        print("wrote", path)


if __name__ == "__main__":
    main()
