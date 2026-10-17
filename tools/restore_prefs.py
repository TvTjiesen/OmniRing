#!/usr/bin/env python3
"""把设备那份 prefs xml 恢复成"他原来的映射"，并去掉测试期间塞进去的键。

为什么在 PC 上生成整份再推过去：这份 xml 里全是引号，用 sed 就地改要穿
PC shell → adb → su -c → sh 四层引号，迟早写坏一个正在被读写的配置文件。
"""
import io
import re
import sys

# 定方向的 scroll_up/scroll_down 已按用户要求删除，四个槽位统一给"滚动"
# （方向本来就跟表冠实际转向走：顺时针往下、逆时针往上）。
MAPPING = {
    "gesture_CW_SLOW_action_id": "scroll",
    "gesture_CW_FAST_action_id": "scroll",
    "gesture_CCW_SLOW_action_id": "scroll",
    "gesture_CCW_FAST_action_id": "scroll",
}
# 测试期间塞进去的探针键，一并删掉（只删 gesture_*，其余键保留）
DROP = re.compile(r'^\s*<(string|int|long|float|boolean) name="gesture_[A-Z_]*_action_id".*?</\1>\s*$')
SELF_CLOSING = re.compile(r'^\s*<(string|int|long|float|boolean) name="gesture_[A-Z_]*_action_id"[^>]*/>\s*$')


def restore(text: str) -> str:
    kept = [l for l in text.split("\n") if not DROP.match(l) and not SELF_CLOSING.match(l)]
    body = "\n".join(kept)
    if "</map>" not in body:
        raise SystemExit("这份 xml 里没有 </map>，不敢动它")
    ins = "".join('    <string name="%s">%s</string>\n' % (k, v) for k, v in MAPPING.items())
    return body.replace("</map>", ins + "</map>", 1)


if __name__ == "__main__":
    path = sys.argv[1]
    src = io.open(path, encoding="utf-8", errors="replace").read()
    out = restore(src)
    io.open(path, "w", encoding="utf-8", newline="\n").write(out)
    print("restored:", ", ".join(MAPPING.values()))
