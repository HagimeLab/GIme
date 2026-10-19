# -*- coding: utf-8 -*-
"""从 design/cat-light.png / cat-dark.png 重出全部美术产物：应用图标 + 启动页那两张猫图。

    python3 design/make_icons.py

来源是两张同一条线稿的黑白版（用户给的）：cat-light = 黑墨白纸，cat-dark = 白墨黑纸。
启动页一只猫在浅色下用黑墨、在深色下用白墨，所以两套主题各一张 <img>，
由 index.html 里 [data-theme] 切换——不走 CSS mask：真机（WebView Chrome 80）和桌面
headless 两边都量过，挂上 mask 之后整只猫被裁没了，连一张已知正确的 alpha 圆点都画不出来。

产物：
  app/src/main/assets/cat-light.png / cat-dark.png
      抠掉纸色、只留墨迹的 256×256 剪影。墨色不抄第二份：从 index.html 的 --ink 令牌读，
      改色板之后重跑本脚本，猫跟着色板走。
  app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png
      应用图标按用户裁定直接用第一张原图：黑墨白纸，五档密度。
  design/ic_master.png
      1024 母图，只是给人眼核对用的，不进包。
"""
import io
import os
import re

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, os.pardir)
LIGHT = os.path.join(HERE, "cat-light.png")      # 黑墨白纸
DARK = os.path.join(HERE, "cat-dark.png")        # 白墨黑纸
INDEX = os.path.join(ROOT, "app", "src", "main", "assets", "index.html")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
RES = os.path.join(ROOT, "app", "src", "main", "res")
MASTER = os.path.join(HERE, "ic_master.png")

DENSITY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SPLASH_PX = 256              # 启动页只画 62 CSS px，dpr 3 也够
MARGIN = 0.05                # 取景留白，按最长边算


def coverage(path, white_ink):
    """把"墨有多实"算成一张灰度图。黑墨白纸 ⇒ 越暗越实；白墨黑纸 ⇒ 越亮越实。"""
    g = Image.open(path).convert("L")
    return g if white_ink else g.point(lambda v: 255 - v)


def framed(a, px):
    """按墨迹外接方框裁切 → 补成正方形 → 缩到 px×px。两张原图取景一致，图标和启动页才对得上。"""
    box = a.point(lambda v: 255 if v > 8 else 0).getbbox()
    if not box:
        raise SystemExit("%s 上没找到墨迹，取不到取景框" % a)
    a = a.crop(box)
    w, h = a.size
    side = max(w, h)
    pad = int(side * MARGIN)
    canvas = Image.new("L", (side + pad * 2, side + pad * 2), 0)
    canvas.paste(a, ((canvas.width - w) // 2, (canvas.height - h) // 2))
    return canvas.resize((px, px), Image.LANCZOS)


def token_ink(sel):
    """从 index.html 的色板里读 --ink，别在这儿抄第二份颜色。"""
    html = io.open(INDEX, encoding="utf-8").read()
    block = re.search(re.escape(sel) + r"\s*\{(.*?)\n\}", html, re.S)
    if not block:
        raise SystemExit("index.html 里找不到 %s 那条色板" % sel)
    m = re.search(r"--ink:\s*#([0-9A-Fa-f]{6})", block.group(1))
    if not m:
        raise SystemExit("%s 那条色板里没有六位 hex 的 --ink" % sel)
    return tuple(int(m.group(1)[i:i + 2], 16) for i in (0, 2, 4))


if __name__ == "__main__":
    for src, name, sel, white_ink in (
            (LIGHT, "cat-light.png", ':root, [data-theme="light"]', False),
            (DARK, "cat-dark.png", '[data-theme="dark"]', True)):
        mask = framed(coverage(src, white_ink), SPLASH_PX)
        ink = token_ink(sel)
        im = Image.new("RGBA", mask.size, (0, 0, 0, 0))
        im.paste(Image.new("RGB", mask.size, ink), (0, 0), mask)
        out = os.path.join(ASSETS, name)
        im.save(out, optimize=True)
        print("  assets/%s  %dx%d  %dKB  墨色 #%02X%02X%02X" % ((name, mask.width, mask.height,
              os.path.getsize(out) // 1024) + ink))

    icon = Image.new("RGB", (1024, 1024), (255, 255, 255))
    icon.paste(Image.new("RGB", (1024, 1024), (0, 0, 0)), (0, 0), framed(coverage(LIGHT, False), 1024))
    icon.save(MASTER)
    print("  design/ic_master.png  1024x1024（核对用）")
    for d, px in sorted(DENSITY.items()):
        icon.resize((px, px), Image.LANCZOS).save(os.path.join(RES, "mipmap-" + d, "ic_launcher.png"))
        print("  mipmap-%s/ic_launcher.png  %dx%d" % (d, px, px))
