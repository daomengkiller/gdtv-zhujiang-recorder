#!/usr/bin/env python3
"""按配额清理 GitHub Release 里的历史录制。

删除顺序：从最旧的 Release 开始，直到同时满足：
  - 全部 Release 资源总字节 <= --keep-bytes
  - Release 数量 <= --keep-count
  - 不存在发布超过 --keep-days 天的 Release
并且永远保留最近 --keep-min 期。

用法（Actions 里自动调用）：
  python3 scripts/prune.py --repo daomengkiller/gdtv-zhujiang-recorder
环境变量：GH_TOKEN 或 GITHUB_TOKEN
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"


def token():
    return os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""


def api(path, method="GET", payload=None, token_str=""):
    url = path if path.startswith("http") else API + path
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "gdtv-prune/1.0")
    if token_str:
        req.add_header("Authorization", "Bearer " + token_str)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            body = r.read()
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = {"raw": body[:300].decode("utf-8", "replace")}
        return e.code, parsed


def list_releases(repo, tok):
    out, page = [], 1
    while True:
        code, data = api(f"/repos/{repo}/releases?per_page=100&page={page}", token_str=tok)
        if code != 200:
            print(f"!! 列 Release 失败 {code}: {data}", file=sys.stderr)
            return out
        if not data:
            break
        out.extend(data)
        if len(data) < 100:
            break
        page += 1
    return out


def parse_time(s):
    # 2026-11-06T13:05:12Z
    try:
        return time.mktime(time.strptime(s, "%Y-%m-%dT%H:%M:%SZ")) - time.timezone
    except Exception:
        return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--keep-bytes", type=int, default=8_000_000_000)
    ap.add_argument("--keep-count", type=int, default=30)
    ap.add_argument("--keep-days", type=int, default=0)
    ap.add_argument("--keep-min", type=int, default=3)
    ap.add_argument("--only-prefix", default="rec-", help="只处理 tag 以此开头的 Release，空字符串表示全部")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    tok = token()
    if not tok:
        print("!! 缺少 GH_TOKEN/GITHUB_TOKEN", file=sys.stderr)
        return 2

    releases = list_releases(args.repo, tok)
    rels = []
    for r in releases:
        if r.get("draft"):
            continue
        tag = r.get("tag_name") or ""
        if args.only_prefix and not tag.startswith(args.only_prefix):
            continue
        size = sum(int(a.get("size") or 0) for a in r.get("assets") or [])
        rels.append({
            "id": r["id"],
            "tag": tag,
            "size": size,
            "ts": parse_time(r.get("published_at") or r.get("created_at") or ""),
            "name": r.get("name") or tag,
        })

    rels.sort(key=lambda x: x["ts"])  # 旧 → 新
    total = sum(x["size"] for x in rels)
    print(f"共 {len(rels)} 期录制，合计 {total / 1024 / 1024 / 1024:.2f} GiB "
          f"(配额 {args.keep_bytes / 1024 / 1024 / 1024:.2f} GiB / 最多 {args.keep_count} 期)")

    now = time.time()
    to_delete = []
    keep = list(rels)
    # 从最旧开始判断是否需要删
    for item in rels:
        if len(keep) <= max(args.keep_min, 1):
            break
        over_bytes = total > args.keep_bytes
        over_count = len(keep) > args.keep_count
        over_days = args.keep_days > 0 and (now - item["ts"]) > args.keep_days * 86400
        if over_bytes or over_count or over_days:
            to_delete.append(item)
            keep.remove(item)
            total -= item["size"]
        else:
            break

    if not to_delete:
        print("无需清理 ✅")
        return 0

    for item in to_delete:
        print(f"删除 {item['tag']} ({item['size'] / 1024 / 1024:.0f} MiB)")
        if args.dry_run:
            continue
        code, data = api(f"/repos/{args.repo}/releases/{item['id']}", "DELETE", token_str=tok)
        if code not in (204, 200):
            print(f"!! 删除失败 {code}: {data}", file=sys.stderr)
            continue
        # 顺手删掉远端 tag，避免 tags 越堆越多
        code2, _ = api(f"/repos/{args.repo}/git/refs/tags/{item['tag']}", "DELETE", token_str=tok)
        if code2 not in (204, 200, 422):
            print(f"   (tag 清理返回 {code2}，可忽略)")

    print(f"清理完成，删除 {len(to_delete)} 期，剩余 {len(keep)} 期 "
          f"{sum(x['size'] for x in keep) / 1024 / 1024 / 1024:.2f} GiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
