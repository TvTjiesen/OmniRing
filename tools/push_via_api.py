#!/usr/bin/env python3
"""用 GitHub Git API 把本地提交推上去（本机走 443 的 git 协议时通时断）。

为什么需要这个：这台机器 `git push https://github.com:443/...` 会随机
`Failed to connect to github.com:443 after 21082 ms` 或
`send-pack: unexpected disconnect while reading sideband packet`，
而 `api.github.com` 一直是通的。所以走 Git API 手工造提交：

    POST /git/blobs    （只传改动过的文件）
    POST /git/trees    （base_tree = 远端当前 tree）
    POST /git/commits  （parents = 上一个 sha）
    PATCH /git/refs/heads/<branch>   （全部造好后才动分支）

难点是 sha 对齐：API 造出来的 commit sha 和本地 `git commit` 的不一样，
因为时间戳/时区/换行都是服务器决定的。做法是拿 API 返回的
committer.date + message，在本地用 `git hash-object -t commit -w --stdin`
把同一个 commit 对象重算一遍，命中后 `git reset --hard` + `update-ref`，
这样本地和远端字节一致，下次推送还能用普通 diff。

复现时两个变量必须都试：时区 `+0000` 与 `+0800`（不同轮次命中不同），
message 用 `rstrip('\n') + '\n'`（API 会改写尾部换行）。

用法：
    python tools/push_via_api.py                 # 推到默认 remote/branch
    python tools/push_via_api.py --dry-run       # 只打印要做什么
    python tools/push_via_api.py --repo o/r --branch main
退出码：0 成功（本地=远端）；1 失败（分支未被改动，或已改动但本地未对齐）。
"""

import argparse
import base64
import calendar
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

GH_CANDIDATES = [
    r"C:\Program Files\GitHub CLI\gh.exe",
    "gh",
]


def run(args, cwd=None, stdin=None):
    return subprocess.run(args, cwd=cwd, input=stdin, capture_output=True)


def git(*args, cwd=None, stdin=None):
    p = run(["git", *args], cwd=cwd, stdin=stdin)
    if p.returncode != 0:
        raise RuntimeError("git %s 失败: %s" % (" ".join(args), p.stderr.decode("utf-8", "replace").strip()))
    return p.stdout.decode("utf-8", "replace")


def token():
    env = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if env:
        return env.strip()
    last = None
    for exe in GH_CANDIDATES:
        try:
            p = subprocess.run([exe, "auth", "token"], capture_output=True, text=True, timeout=30)
        except (FileNotFoundError, subprocess.TimeoutExpired) as e:
            last = e
            continue
        if p.returncode == 0 and p.stdout.strip():
            return p.stdout.strip()
        last = p.stderr.strip()
    raise SystemExit("拿不到 GitHub token（gh auth token 失败：%s）" % last)


class Api:
    def __init__(self, tok):
        self.tok = tok

    def call(self, path, method="GET", body=None):
        req = urllib.request.Request(
            "https://api.github.com" + path,
            method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={
                "Authorization": "Bearer " + self.tok,
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "User-Agent": "hzr-push-via-api",
            },
        )
        for attempt in range(4):
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    return json.loads(r.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                detail = e.read().decode("utf-8", "replace")
                if e.code >= 500 and attempt < 3:
                    time.sleep(2 * (attempt + 1))
                    continue
                raise SystemExit("API %s %s -> HTTP %d\n%s" % (method, path, e.code, detail[:800]))
            except (urllib.error.URLError, TimeoutError) as e:
                if attempt < 3:
                    time.sleep(2 * (attempt + 1))
                    continue
                raise SystemExit("API %s %s 网络失败: %s" % (method, path, e))


def changed_entries(a, local, parent):
    """返回 [(path, status)]，status 是 git 的 A/M/D/R 首字母。"""
    if parent is None:
        out = git("ls-tree", "-r", "--name-only", local)
        return [(p, "A") for p in out.splitlines() if p]
    out = git("diff-tree", "--no-commit-id", "--name-status", "-r", "-M", parent, local)
    rows = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        st = parts[0][0]
        if st == "R" and len(parts) >= 3:
            rows.append((parts[1], "D"))
            rows.append((parts[2], "A"))
        else:
            rows.append((parts[1], st))
    return rows


def upload_blobs(api, repo, local, entries, dry):
    """把新增/修改的文件做成 blob，返回 {path: blob_sha}。内容与本地完全相同，
    所以 sha 必然等于本地 `git rev-parse <commit>:<path>`，可以直接复用 —— 这里仍走一次
    API 是为了确保远端真的有这个 blob（远端可能是一次全新的仓库）。"""
    out = {}
    for path, st in entries:
        if st == "D":
            continue
        blob_sha = git("rev-parse", "%s:%s" % (local, path)).strip()
        if dry:
            print("   blob %s %s" % (blob_sha[:10], path))
            out[path] = blob_sha
            continue
        data = git_bytes("cat-file", "blob", blob_sha)
        r = api.call(
            "/repos/%s/git/blobs" % repo,
            "POST",
            {"content": base64.b64encode(data).decode(), "encoding": "base64"},
        )
        out[path] = r["sha"]
    return out


def git_bytes(*args):
    p = run(["git", *args])
    if p.returncode != 0:
        raise RuntimeError("git %s 失败" % " ".join(args))
    return p.stdout


def reproduce(api_commit, tree, parent, message):
    """在本地把 API 造出的 commit 对象重算成同一个 sha（两个时区、两种尾部换行都试）。"""
    want = api_commit["sha"]
    dt = api_commit["committer"]["date"]
    epoch = calendar.timegm(time.strptime(dt, "%Y-%m-%dT%H:%M:%SZ"))
    ident = "%s <%s>" % (api_commit["author"]["name"], api_commit["author"]["email"])
    variants = {
        "rstrip+nl": message.rstrip("\n") + "\n",
        "as-is": message,
        "rstrip+nlnl": message.rstrip("\n") + "\n\n",
    }
    for tz in ("+0000", "+0800"):
        for name, body in variants.items():
            header = "tree %s\n" % tree
            if parent:
                header += "parent %s\n" % parent
            header += "author %s %d %s\ncommitter %s %d %s\n\n" % (ident, epoch, tz, ident, epoch, tz)
            raw = (header + body).encode("utf-8")
            sha = run(["git", "hash-object", "-t", "commit", "-w", "--stdin"], stdin=raw)
            sha = sha.stdout.decode().strip()
            if sha == want:
                return want, tz, name
    return None, None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="TvTjiesen/OmniRing")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    remote_ref = "/repos/%s/git/ref/heads/%s" % (args.repo, args.branch)
    api = Api(token())

    if args.dry_run:
        print("dry-run：只解析本地/远端差异，不改任何东西")
    else:
        time.sleep(0)

    ref = api.call(remote_ref)
    remote_head = ref["object"]["sha"]
    local_head = git("rev-parse", "HEAD").strip()
    print("远端 %s = %s" % (args.branch, remote_head))
    print("本地 HEAD = %s" % local_head)

    if remote_head == local_head:
        print("已经一致，无事可做。")
        return 0

    known = run(["git", "cat-file", "-e", remote_head + "^{commit}"])
    if known.returncode != 0:
        raise SystemExit(
            "远端 head %s 不在本地对象库里 —— 先 `git fetch` 或手工把该对象放进本地，否则算不出差异。"
            % remote_head
        )

    chain = [c for c in git("rev-list", "--reverse", "%s..%s" % (remote_head, local_head)).splitlines() if c]
    if not chain:
        raise SystemExit("本地没有领先远端的新提交，但 head 不同 —— 需要人工判断（可能是历史被改过）。")
    print("待推 %d 个提交：" % len(chain))
    for c in chain:
        print("   %s %s" % (c[:9], git("log", "-1", "--format=%s", c).strip()))

    prev_remote = remote_head
    prev_tree = api.call("/repos/%s/git/commits/%s" % (args.repo, prev_remote))["tree"]["sha"]
    aligned = []
    for c in chain:
        parent = git("rev-parse", "%s^" % c).strip() if run(["git", "rev-parse", "--verify", "-q", c + "^"]).returncode == 0 else None
        entries = changed_entries(api, c, parent)
        print("→ %s 改动 %d 个路径" % (c[:9], len(entries)))
        blobs = upload_blobs(api, args.repo, c, entries, args.dry_run)
        tree_entries = []
        for path, st in entries:
            if st == "D":
                tree_entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            else:
                mode = git("ls-tree", c, "--", path).split()[0]
                tree_entries.append({"path": path, "mode": mode, "type": "blob", "sha": blobs[path]})
        if args.dry_run:
            print("   tree: base=%s entries=%d" % (prev_tree[:10], len(tree_entries)))
            prev_tree = git("rev-parse", "%s^{tree}" % c).strip()
            prev_remote = c
            continue
        tree = api.call(
            "/repos/%s/git/trees" % args.repo,
            "POST",
            {"base_tree": prev_tree, "tree": tree_entries},
        )
        expect_tree = git("rev-parse", "%s^{tree}" % c).strip()
        if tree["sha"] != expect_tree:
            raise SystemExit(
                "tree 不一致：API=%s 本地=%s（说明有路径没被正确搬运，分支没动）"
                % (tree["sha"], expect_tree)
            )
        message = git("log", "-1", "--format=%B", c)
        commit = api.call(
            "/repos/%s/git/commits" % args.repo,
            "POST",
            {"message": message, "tree": tree["sha"], "parents": [prev_remote]},
        )
        sha, tz, how = reproduce(commit, tree["sha"], prev_remote, message)
        if sha is None:
            raise SystemExit(
                "造出了远端 commit %s，但本地复现不出同一个 sha（分支没动）。"
                "手工对齐：git reset --hard %s" % (commit["sha"], commit["sha"])
            )
        print("   commit %s（本地复现命中 tz=%s msg=%s）" % (sha[:9], tz, how))
        aligned.append(sha)
        prev_remote = sha
        prev_tree = tree["sha"]

    if args.dry_run:
        print("dry-run 结束，没有写入。")
        return 0

    patched = api.call(
        "/repos/%s/git/refs/heads/%s" % (args.repo, args.branch),
        "PATCH",
        {"sha": prev_remote, "force": False},
    )
    print("分支已更新：%s" % patched["object"]["sha"])

    git("reset", "--hard", prev_remote)
    git("update-ref", "refs/remotes/origin/%s" % args.branch, prev_remote)
    out = git("log", "--oneline", "-1").strip()
    status = git("status", "--short").strip()
    print("本地已对齐：%s" % out)
    print("工作树：%s" % ("干净" if not status else status))
    return 0


if __name__ == "__main__":
    sys.exit(main())
