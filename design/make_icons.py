# -*- coding: utf-8 -*-
"""从启动页那段猫轮廓重出全部启动图标。

    python3 design/make_icons.py

唯一来源是 index.html 里 <div class="sp-logo"> 内的 <svg>（启动页和应用图标必须是同一只猫，
所以不另存一份矢量文件）。这里把它换成图标用的配色/线宽/取景，用无头 Chrome 光栅成 1024 母图，
再降采样到五档密度。改轮廓只改 index.html，别手改 PNG。
"""
import os
import re
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, os.pardir)
INDEX = os.path.join(ROOT, "app", "src", "main", "assets", "index.html")
RES = os.path.join(ROOT, "app", "src", "main", "res")
MASTER = os.path.join(HERE, "ic_master.png")
BROWSERS = (
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
)
DENSITY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 图标比启动页细：1.5/24 格在 48px 上会糊成一团
SWAP = (
    ('viewBox="0 0 24 24"', 'viewBox="0.6 1.1 23.6 23.6"'),
    ('stroke-width="1.5"', 'stroke-width="0.7"'),
    ('stroke="currentColor"', 'stroke="#401305"'),
    ('fill="currentColor"', 'fill="#401305"'),
)


def outline_svg():
    html = open(INDEX, encoding="utf-8").read()
    block = html.split('<div class="sp-logo"', 1)
    if len(block) < 2:
        sys.exit("index.html 里找不到 sp-logo，取轮廓失败")
    m = re.search(r"<svg\b.*?</svg>", block[1], re.S)
    if not m:
        sys.exit("sp-logo 里没有 <svg>")
    svg = m.group(0)
    for old, new in SWAP:
        if old not in svg:
            sys.exit("启动页轮廓的属性改了（找不到 %s），本脚本要同步改" % old)
        svg = svg.replace(old, new)
    return re.sub(r"\s+aria-hidden=\"true\"", "", svg)


def raster(svg, px, out):
    chrome = next((b for b in BROWSERS if os.path.exists(b)), None)
    if not chrome:
        sys.exit("找不到 Chrome/Edge，无法光栅化 SVG")
    # SVG 根元素得显式定尺寸，否则 Chrome 按 300x150 排版
    body = svg.replace("<svg ", '<svg width="%d" height="%d" ' % (px, px), 1)
    page = os.path.join(tempfile.gettempdir(), "gime_icon.html")
    with open(page, "w", encoding="utf-8") as f:
        f.write("<!doctype html><meta charset=utf-8><style>"
                "html,body{margin:0;padding:0;background:#fff}</style>" + body)
    subprocess.run(
        [chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars",
         "--force-device-scale-factor=1", "--default-background-color=FFFFFFFF",
         "--window-size=%d,%d" % (px, px), "--screenshot=" + out,
         "file:///" + page.replace("\\", "/")],
        check=True, capture_output=True, timeout=120)


if __name__ == "__main__":
    raster(outline_svg(), 1024, MASTER)
    im = Image.open(MASTER).convert("RGB")
    if im.size != (1024, 1024):
        sys.exit("母图尺寸不对：%s" % (im.size,))
    for d, px in sorted(DENSITY.items()):
        im.resize((px, px), Image.LANCZOS).save(
            os.path.join(RES, "mipmap-" + d, "ic_launcher.png"))
        print("  mipmap-%s/ic_launcher.png  %dx%d" % (d, px, px))
