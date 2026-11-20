#!/usr/bin/env python3
"""把模型包分卷推送到 Gitee 发行版附件（国内下载中转）。

背景：Gitee 发行版单个附件 ≤ 100MB，169MB 的模型包必须切卷；附件下载不支持 Range，
单连接 30MB 以上容易断，所以按 20MB 一卷（见 tools/split-model.py）。

用法：
    set GITEE_TOKEN=xxxx            # 私人令牌，需 projects 写权限
    python tools/upload-gitee.py --tag v1.1.8-models --dir models/gitee-parts

可重复执行：已传过且大小一致的附件会跳过，只补缺的卷。
"""

import argparse
import hashlib
import json
import os
import sys
import time

import requests

API = "https://gitee.com/api/v5"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()


def api(method, path, token, **kw):
    kw.setdefault("timeout", 60)
    if method == "GET":
        kw.setdefault("params", {})["access_token"] = token
        r = requests.get(API + path, **kw)
    else:
        if "json" in kw:
            kw["json"]["access_token"] = token
        else:
            kw.setdefault("data", {})["access_token"] = token
        r = requests.request(method, API + path, **kw)
    if r.status_code >= 300:
        raise SystemExit("%s %s -> %s %s" % (method, path, r.status_code, r.text[:400]))
    return r.json() if r.text.strip() else {}


def ensure_tag(owner, repo, tag, token):
    tags = api("GET", "/repos/%s/%s/tags" % (owner, repo), token, params={"per_page": 100})
    if any(t.get("name") == tag for t in tags or []):
        return
    print("· 创建标签 %s" % tag)
    try:
        api("POST", "/repos/%s/%s/git/tags" % (owner, repo), token,
            json={"tag_name": tag, "refs": "master", "tag_message": "模型包下载中转"})
    except SystemExit as e:
        print("  标签接口不可用（%s），交给发行版自动建标签" % str(e).split("->")[-1].strip()[:60])


def ensure_release(owner, repo, tag, name, body, token):
    rels = api("GET", "/repos/%s/%s/releases" % (owner, repo), token, params={"per_page": 100})
    for r in rels or []:
        if r.get("tag_name") == tag:
            print("· 发行版已存在：%s (id=%s)" % (tag, r.get("id")))
            return r
    print("· 创建发行版 %s" % tag)
    rel = api("POST", "/repos/%s/%s/releases" % (owner, repo), token,
              json={"tag_name": tag, "name": name, "body": body,
                    "target_commitish": "master", "prerelease": False})
    return rel


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", default="hagemilab")
    ap.add_argument("--repo", default="gi")
    ap.add_argument("--tag", default="v1.1.8-models")
    ap.add_argument("--name", default="GIme 字幕模型包（国内中转）")
    ap.add_argument("--body", default="")
    ap.add_argument("--dir", required=True)
    a = ap.parse_args()

    token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token:
        raise SystemExit("缺少 GITEE_TOKEN 环境变量")

    man_path = os.path.join(a.dir, "manifest.json")
    with open(man_path, encoding="utf-8") as f:
        man = json.load(f)
    if not a.body:
        a.body = ("字幕模型包 `%s`（%d 字节，sha256 `%s`）按 20MB 切成 %d 卷。\n\n"
                  "App 侧按卷断点续传，全部到位后拼接并校验整包 sha256。"
                  % (man["package"], man["size"], man["sha256"], man["part_count"]))

    ensure_tag(a.owner, a.repo, a.tag, token)
    rel = ensure_release(a.owner, a.repo, a.tag, a.name, a.body, token)
    rid = rel["id"]

    existing = api("GET", "/repos/%s/%s/releases/%s/attach_files" % (a.owner, a.repo, rid), token,
                   params={"per_page": 100}) or []
    done = {x.get("name"): x for x in existing}

    print("· 目标：%d 卷，已有 %d 个附件" % (man["part_count"], len(done)))
    for p in man["parts"]:
        name = p["name"]
        path = os.path.join(a.dir, name)
        if not os.path.isfile(path):
            raise SystemExit("缺少分卷文件 %s" % path)
        got_size = os.path.getsize(path)
        if got_size != p["size"]:
            raise SystemExit("分卷长度不符 %s: %d != %d" % (name, got_size, p["size"]))
        has = done.get(name)
        if has and int(has.get("size", -1)) == p["size"]:
            print("  = 跳过（已传）%s" % name)
            continue
        if has:
            print("  ! 已存在但大小不符（%s），先删" % has.get("size"))
            api("DELETE", "/repos/%s/%s/releases/%s/attach_files/%s" % (a.owner, a.repo, rid, has.get("id")), token)
        t0 = time.time()
        for attempt in range(3):
            try:
                with open(path, "rb") as fh:
                    r = requests.post(
                        "%s/repos/%s/%s/releases/%s/attach_files" % (API, a.owner, a.repo, rid),
                        data={"access_token": token},
                        files={"file": (name, fh, "application/octet-stream")},
                        timeout=900)
                if r.status_code >= 300:
                    raise RuntimeError("%s %s" % (r.status_code, r.text[:200]))
                obj = r.json()
                if int(obj.get("size", -1)) != p["size"]:
                    raise RuntimeError("回执大小不符 %s" % obj.get("size"))
                print("  + %-42s %10d  %5.1fs" % (name, p["size"], time.time() - t0))
                break
            except Exception as e:
                print("  ! %s 第 %d 次失败：%s" % (name, attempt + 1, str(e)[:160]))
                if attempt == 2:
                    raise SystemExit("上传失败：%s" % name)
                time.sleep(3)

    final = api("GET", "/repos/%s/%s/releases/%s/attach_files" % (a.owner, a.repo, rid), token,
                params={"per_page": 100}) or []
    print("\n=== 附件清单（%d 个，合计 %d 字节）===" % (len(final), sum(int(x.get("size", 0)) for x in final)))
    for x in sorted(final, key=lambda s: s.get("name", "")):
        print("  %-42s %10d  %s" % (x.get("name"), int(x.get("size", 0)), x.get("browser_download_url")))
    missing = [p["name"] for p in man["parts"] if p["name"] not in {x.get("name") for x in final}]
    if missing:
        print("\n!! 还缺 %d 卷：%s" % (len(missing), missing))
        sys.exit(2)
    print("\n全部 %d 卷就位。" % man["part_count"])


if __name__ == "__main__":
    main()
