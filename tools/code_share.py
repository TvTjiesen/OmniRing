"""统计 app/src 里的代码来源占比 —— 给 GPL-3.0 §5a 的"显著修改声明"提供可复现的数字。

做法是**跟上游工作副本逐文件比 md5**，不做任何人工分类：
  · 逐字节相同  → 「上游原样保留」
  · 同路径不同  → 「二改重写」
  · 只有我们有  → 「二改新增」
  · 只有上游有  → 「已删除」

用法：
    python tools/code_share.py --upstream <上游工作副本>
上游副本自己拉一份（不进本仓库）：
    git clone --depth 1 https://github.com/NextAlone/HyperZoomRing.git /e/dev/tmp_upstream

旧版脚本靠两张手写字面清单判断"这个文件是不是上游原样"，一旦新增文件没进清单就被算成
"上游原样"，2026-11-02 复核时它把 60 个文件 46% 报成上游保留，实测逐字节相同的只有 2 个
非代码文件 —— 所以改成现在的 md5 判据。
"""
import argparse
import hashlib
import os
import sys

SRC = os.path.join("app", "src")


def scan(root):
    """返回 {相对路径: 绝对路径}，只收 app/src 下的文件（跳过构建产物）。"""
    out = {}
    base = os.path.join(root, SRC)
    for dp, dn, fn in os.walk(base):
        dn[:] = [d for d in dn if d not in ("build", ".gradle")]
        for f in fn:
            p = os.path.join(dp, f)
            out[os.path.relpath(p, root).replace(os.sep, "/")] = p
    return out


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def text_md5(path):
    """忽略 CRLF/LF 差异的 md5 —— 本仓库提交时 LF 会被改成 CRLF，
    不归一化会把 8 个其实一字未改的上游小文件误判成"重写"。"""
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read().replace(b"\r\n", b"\n")).hexdigest()


def lines(path):
    with open(path, "rb") as fh:
        return sum(1 for _ in fh)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--upstream", required=True, help="上游仓库工作副本目录")
    args = ap.parse_args()
    here = os.path.dirname(os.path.abspath(__file__))
    ours_root = os.path.dirname(here)
    up_root = os.path.abspath(args.upstream)
    if not os.path.isdir(os.path.join(up_root, SRC)):
        sys.exit(f"上游目录里没有 {SRC}：{up_root}")

    up, ours = scan(up_root), scan(ours_root)
    cats = {"identical": [], "same_text": [], "rewritten": [], "new": [], "removed": []}
    for rel, p in ours.items():
        if rel not in up:
            cats["new"].append(rel)
        elif md5(p) == md5(up[rel]):
            cats["identical"].append(rel)
        elif text_md5(p) == text_md5(up[rel]):
            cats["same_text"].append(rel)
        else:
            cats["rewritten"].append(rel)
    for rel in up:
        if rel not in ours:
            cats["removed"].append(rel)

    def tally(names):
        sel = [n for n in names if n.endswith(".kt")]
        return len(sel), sum(lines(ours.get(x) or up[x]) for x in sel)

    print(f"本仓库 {SRC}：{len(ours)} 个文件（.kt {sum(1 for x in ours if x.endswith('.kt'))}）")
    print(f"上游   {SRC}：{len(up)} 个文件（.kt {sum(1 for x in up if x.endswith('.kt'))}）\n")

    print("按 .kt 行数分（绝对值比百分比可靠，分母是两边 .kt 之和再扣掉已删除）：")
    rows = []
    for key, label in (("identical", "上游原样(逐字节)"), ("same_text", "上游原样(仅换行差)"),
                       ("rewritten", "二改重写"), ("new", "二改新增"), ("removed", "已删除")):
        n, tot = tally(cats[key])
        rows.append((label, key, n, tot))
    verbatim = sum(r[3] for r in rows if r[1] in ("identical", "same_text"))
    base = sum(r[3] for r in rows if r[1] != "removed") or 1
    for label, key, n, tot in rows:
        pct = "" if key == "removed" else f"{tot * 100.0 / base:5.1f}%"
        print(f"  {label:16s} {n:3d} 个 .kt {tot:6d} 行 {pct}")
    print(f"  ⇒ 上游一行没动的 .kt 合计 {verbatim} 行，占现体量 {verbatim * 100.0 / base:.1f}%")

    print("\n非 .kt 的逐字节相同文件（真正没动过的上游文件）：")
    same_other = [x for x in cats["identical"] + cats["same_text"] if not x.endswith(".kt")]
    for x in same_other:
        print(f"  = {x}")
    if not same_other:
        print("  （无）")

    print("\n重写过的上游 .kt（同路径但内容不同）：")
    for x in sorted(cats["rewritten"]):
        if x.endswith(".kt"):
            print(f"  ~ {x}  上游 {lines(up[x])} 行 → 现 {lines(ours[x])} 行")


if __name__ == "__main__":
    main()