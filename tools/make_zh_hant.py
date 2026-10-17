#!/usr/bin/env python3
"""从 values/strings.xml 生成 values-b+zh+Hant/strings.xml（繁體中文）。

为什么要脚本而不是手抄一遍：三份语言现在 213 条键，手抄必然漏键、也会和
tools/i18n_check.py 的键数对齐检查打架。新增文案以后重跑一次就行。

两步：
  1. zhconv 做字-level 的简→繁（zh-hant）；
  2. 再按台湾 UI 用词做一次词-level 校正 —— 机器转换只会换字不会换词，
     直接用会得到「滾動／視頻／應用／默認」这种一看就是简体硬转的措辞。
"""
import io
import os
import re
import sys

from zhconv import convert

SRC = "app/src/main/res/values/strings.xml"
DST = "app/src/main/res/values-b+zh+Hant/strings.xml"

# 词-level 校正：键是转换后的繁体写法
TERM = [
    ("設置", "設定"),
    ("模塊", "模組"),
    ("重啓", "重啟"),
    ("啓", "啟"),
    ("啓動", "啟動"),
    ("掛鈎", "掛鉤"),
    ("窗口", "視窗"),
    ("前臺", "前景"),
    ("信息", "資訊"),
    ("界面", "介面"),
    ("壁紙", "桌布"),
    ("菜單", "選單"),
    ("來迴", "來回"),
    ("指針", "指標"),
    ("滾動", "捲動"),
    ("滾", "捲"),
    ("視頻", "影片"),
    ("音頻", "音訊"),
    ("應用", "應用程式"),
    ("默認", "預設"),
    ("屏幕", "螢幕"),
    ("硬體", "硬體"),
    ("軟件", "軟體"),
    ("文檔", "文件"),
    ("加載", "載入"),
    ("重新載入", "重新載入"),
    ("表冠", "錶冠"),
    ("拖拽", "拖曳"),
    ("閃光燈", "手電筒"),
    ("灵敏度", "靈敏度"),
    ("點擊", "點按"),
    ("点一下", "點一下"),
    ("反馈", "回饋"),
    ("启动", "啟動"),
    ("傳感", "感應"),
    ("变焦", "變焦"),
    ("視頻進度", "影片進度"),
]


def fix(text: str) -> str:
    out = convert(text, "zh-hant")
    for a, b in TERM:
        if a != b:
            out = out.replace(a, b)
    # 「應用程式」别把已经正确的「應用程式」套两遍
    out = out.replace("應用程式程式", "應用程式")
    return out


def main() -> int:
    if not os.path.exists(SRC):
        print("!! 请在仓库根目录运行", file=sys.stderr)
        return 1
    src = io.open(SRC, encoding="utf-8").read()

    def repl(m):
        head, body, tail = m.group(1), m.group(2), m.group(3)
        return head + fix(body) + tail

    out = re.sub(r'(<string name="[a-z0-9_]+">)(.*?)(</string>)', repl, src, flags=re.S)
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    io.open(DST, "w", encoding="utf-8", newline="\n").write(out)
    n = len(re.findall(r'<string name="', out))
    print(f"写入 {DST}：{n} 条")
    # 自检：不能有简体残留（挑几个高频简体词）。注释行跳过：
    # 源文件里的中文注释是给开发者看的，不参与运行时取词
    body = "\n".join(
        l for l in out.split("\n") if not l.strip().startswith("<!--")
    )
    left = [(w, body.count(w)) for w in ("设置", "滚动", "视频", "应用", "默认", "屏幕", "触摸") if w in body]
    if left:
        print("!! 仍有简体用词，补进 TERM 再来：", left)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
