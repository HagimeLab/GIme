#!/usr/bin/env python3
"""把单个文件（安装包等）推到 Gitee 发行版附件。

Gitee 只做「安装包 + 模型分卷」的国内中转，不存源码：
  * 安装包 → 发行版 v<版本> 的附件（本脚本）
  * 模型分卷 → 发行版 v<版本>-models 的 9 卷（见 tools/upload-gitee.py）

用法：
    set GITEE_TOKEN=xxxx            # 私人令牌，需 projects 写权限
    python tools/upload-gitee-file.py --tag v1.1.8 --file release/GIme-v1.1.8-XXXXXXXX.apk

可重复执行：已传过且大小一致的附件跳过，不重传。
"""

import argparse
import os
import sys
import time

import requests

API = "https://gitee.com/api/v5"


def api(method, path, token, **kw):
    kw.setdefault("timeout", 60)
    if method == "GET":
        kw.setdefault("params", {})["access_token"] = token
        r = requests.get(API + path, **kw)
    else:
        if "json" in kw:
            kw["json"]["access_token"] = token
            r = requests.request(method, API + path, **kw)
        else:
            kw.setdefault("data", {})["access_token"] = token
            r = requests.request(method, API + path, **kw)
    if r.status_code >= 300:
        raise SystemExit("%s %s -> %s %s" % (method, path, r.status_code, r.text[:400]))
    return r.json() if r.text.strip() else {}


def ensure_release(owner, repo, tag, name, body, token):
    rels = api("GET", "/repos/%s/%s/releases" % (owner, repo), token, params={"per_page": 100}) or []
    for r in rels:
        if r.get("tag_name") == tag:
            print("· 发行版已存在：%s (id=%s)" % (tag, r.get("id")))
            return r
    print("· 创建发行版 %s" % tag)
    return api("POST", "/repos/%s/%s/releases" % (owner, repo), token,
               json={"tag_name": tag, "name": name, "body": body,
                     "target_commitish": "master", "prerelease": False})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", default="hagemilab")
    ap.add_argument("--repo", default="gi")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--name", default=None)
    ap.add_argument("--body", default="")
    ap.add_argument("--file", required=True)
    a = ap.parse_args()

    token = os.environ.get("GITEE_TOKEN", "").strip()
    if not token:
        raise SystemExit("缺少 GITEE_TOKEN 环境变量")

    path = a.file
    if not os.path.isfile(path):
        raise SystemExit("文件不存在：%s" % path)
    name = os.path.basename(path)
    size = os.path.getsize(path)
    if not a.name:
        a.name = "GIme %s 安装包（国内中转）" % a.tag
    if not a.body:
        a.body = ("安装包 `%s`（%d 字节）。\n\n"
                  "只放安装包，源码与清单留在 GitHub；App 更新时优先试这个源，"
                  "失败自动降级到 jsDelivr / GitHub。" % (name, size))

    rel = ensure_release(a.owner, a.repo, a.tag, a.name, a.body, token)
    rid = rel["id"]

    existing = api("GET", "/repos/%s/%s/releases/%s/attach_files" % (a.owner, a.repo, rid), token,
                   params={"per_page": 100}) or []
    for x in existing:
        if x.get("name") == name:
            if int(x.get("size", -1)) == size:
                print("= 已存在且大小一致，跳过：%s" % name)
                print("  %s" % x.get("browser_download_url"))
                return
            print("! 已存在但大小不符（%s），先删" % x.get("size"))
            api("DELETE", "/repos/%s/%s/releases/%s/attach_files/%s" % (a.owner, a.repo, rid, x.get("id")), token)

    t0 = time.time()
    for attempt in range(3):
        try:
            with open(path, "rb") as fh:
                r = requests.post(
                    "%s/repos/%s/%s/releases/%s/attach_files" % (API, a.owner, a.repo, rid),
                    data={"access_token": token},
                    files={"file": (name, fh, "application/octet-stream")},
                    timeout=1800)
            if r.status_code >= 300:
                raise RuntimeError("%s %s" % (r.status_code, r.text[:200]))
            obj = r.json()
            if int(obj.get("size", -1)) != size:
                raise RuntimeError("回执大小不符 %s" % obj.get("size"))
            print("+ %s  %d 字节  %.1fs" % (name, size, time.time() - t0))
            print("  %s" % obj.get("browser_download_url"))
            return
        except Exception as e:
            print("! 第 %d 次失败：%s" % (attempt + 1, str(e)[:200]))
            if attempt == 2:
                raise SystemExit("上传失败：%s" % name)
            time.sleep(3)


if __name__ == "__main__":
    main()
