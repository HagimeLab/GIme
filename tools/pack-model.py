#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""pack-model.py — 把模型目录打成 App 可直接下载安装的模型包（zip）。

App 侧约定（AsrModels.unzip）：
  · zip 里只取文件名，目录层级会被拍平；
  · 解压后必须齐备 m.files 清单里的每个文件，否则视为安装失败；
  · 下载后按 size + sha256 校验整包。

因此这里强制「扁平打包 + 固定文件清单」，产出后打印 size / sha256 和可直接
粘进 AsrModels.MODELS 的 Model(...) 调用，并落一份 .sha256 边车文件。

用法：
  python tools/pack-model.py --dir D:\desktop\加速推理框架\models_1920_int8\sim \
      --out dist/gime-asr-xasr-1920ms-int8.zip
"""
import argparse
import hashlib
import os
import sys
import zipfile

# 快模型包的固定清单：loader 优先精确名 encoder.onnx / decoder.onnx / joiner.onnx
FAST_FILES = ["encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="模型目录（含 4 个文件）")
    ap.add_argument("--out", required=True, help="输出 zip 路径")
    ap.add_argument("--files", default="", help="逗号分隔清单，默认快模型 4 件")
    args = ap.parse_args()

    files = [x.strip() for x in args.files.split(",") if x.strip()] or FAST_FILES
    missing = [f for f in files if not os.path.isfile(os.path.join(args.dir, f))]
    if missing:
        sys.exit("缺文件：%s" % ", ".join(missing))

    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)
    # 压缩方式用 STORED：onnx 本身已接近熵极限，deflate 只多花 CPU 换 ~0 收益，
    # 而 App 端是边下边存，包体越小越好换传输时间；这里主要是避免打包耗时。
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_STORED) as z:
        for f in files:
            p = os.path.join(args.dir, f)
            # arcname 只用文件名：App 解压时本来就只认 basename
            z.write(p, arcname=f)
            print("  + %-16s %12d bytes" % (f, os.path.getsize(p)))

    size = os.path.getsize(args.out)
    digest = sha256(args.out)
    with open(args.out + ".sha256", "w", encoding="ascii") as f:
        f.write("%s  %s\n" % (digest, os.path.basename(args.out)))

    print("\nzip      %s" % args.out)
    print("size     %d bytes (%.1f MB)" % (size, size / 1e6))
    print("sha256   %s" % digest)
    print("\n模型条目（AsrModels.MODELS 里替换用）：")
    print('    new Model(MODEL_ID, "本地字幕模型",')
    print('            "X-ASR 中英大模型（1920ms 快模型）· %d MB · MatMul int8 + onnxsim",' % round(size / 1e6))
    print("            0L, %dL," % size)
    print('            "%s",' % digest)
    print('            "%s",' % os.path.basename(args.out))
    for f in files:
        print('            "%s",' % f)
    print("            );")


if __name__ == "__main__":
    main()