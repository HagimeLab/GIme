#!/usr/bin/env python3
"""把模型包按 Gitee Release 的约束切成多卷，并生成上传清单。

约束来源（Gitee 帮助中心 2026-11 核实）：
  * 发行版单个附件 ≤ 100MB（GVP 项目 200MB），单仓库附件总量 1GB
  * 附件下载不支持 Range，单连接 30MB 以上容易提前断流

所以 169MB 的单文件包不能整包传，按 20MB 一卷切开（这是旧 167MB 包验证过的档位），
每卷单独上传，App 侧按卷断点续传、全部到位后拼接并验整包 SHA-256。

用法：
    python tools/split-model.py --src dist/gime-asr-xasr-1920ms-int8.zip --out models/gitee-parts
"""

import argparse
import hashlib
import json
import os

PART = 20 * 1024 * 1024


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--part", type=int, default=PART, help="单卷字节数，默认 20MB")
    a = ap.parse_args()

    size = os.path.getsize(a.src)
    whole = sha256_of(a.src)
    n = (size + a.part - 1) // a.part
    os.makedirs(a.out, exist_ok=True)

    parts = []
    with open(a.src, "rb") as f:
        for i in range(n):
            want = min(a.part, size - i * a.part)
            name = os.path.basename(a.src) + ".p%d" % (i + 1)
            dst = os.path.join(a.out, name)
            with open(dst, "wb") as o:
                left = want
                while left > 0:
                    b = f.read(min(1024 * 1024, left))
                    if not b:
                        raise SystemExit("源文件中途读完，长度不符")
                    o.write(b)
                    left -= len(b)
            parts.append({"name": name, "size": want, "sha256": sha256_of(dst)})

    manifest = {
        "package": os.path.basename(a.src),
        "size": size,
        "sha256": whole,
        "part_size": a.part,
        "part_count": n,
        "parts": parts,
    }
    with open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print("整包 %s  %d bytes  sha256 %s" % (manifest["package"], size, whole))
    print("切成 %d 卷（单卷 %d 字节）：" % (n, a.part))
    for p in parts:
        print("  %-42s %10d  %s" % (p["name"], p["size"], p["sha256"][:16]))
    tail = size - a.part * (n - 1)
    print("末卷 %d bytes = %.1f MB" % (tail, tail / 1024 / 1024))
    print("清单 -> %s" % os.path.join(a.out, "manifest.json"))


if __name__ == "__main__":
    main()
