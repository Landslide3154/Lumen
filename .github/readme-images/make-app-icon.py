# -*- coding: utf-8 -*-
"""把 Lumen 的自适应图标合成成一张独立图标（README / 仓库页用）。

按本机 app-icon 技能：超椭圆底板（r=0.35S, n=4）、两层阴影、只堆大形状、留 7.5% 边。
输入 = 应用真正在用的图层（beige 背景 + drawable-xxxhdpi 前景 PNG），
按启动器的规则取中间 72/108、套超椭圆遮罩，保证和手机上看到的一致。
"""
from PIL import Image, ImageDraw, ImageFilter
import math

SRC = r"D:\code\Lumen\app\src\main\res\drawable-xxxhdpi\ic_launcher_foreground.png"
OUT = r"D:\code\Lumen\.github\readme-images\app-icon.png"
CHECK = r"C:\Users\godis\AppData\Local\Temp\lumen-icon-check.png"

BEIGE = (0xF7, 0xF3, 0xEC, 255)
SHADOW = (0x0A, 0x10, 0x30)
VISIBLE = 72 / 108          # 启动器只显示 108dp 画布中间这一块
ICON = 512                  # 成品图标本体边长
SS = 4                      # 超采样
MARGIN = 0.075              # 四周留边，给外投影
OUTER_ALPHA = int(255 * 0.42)


def squircle(size: int, r_ratio: float = 0.35, n: float = 4.0, steps: int = 240):
    """超椭圆圆角矩形：四角用 r=0.35S、指数 4 的参数曲线，边为直线。"""
    r = r_ratio * size
    c = size / 2
    d = c - r
    pts = []
    for ox, oy, a0 in ((d, -d, -90), (d, d, 0), (-d, d, 90), (-d, -d, 180)):
        for i in range(steps + 1):
            th = math.radians(a0 + 90 * i / steps)
            ct, st = math.cos(th), math.sin(th)
            pts.append(
                (
                    c + ox + r * math.copysign(abs(ct) ** (2 / n), ct),
                    c + oy + r * math.copysign(abs(st) ** (2 / n), st),
                )
            )
    return pts


def build() -> Image.Image:
    work = ICON * SS
    canvas = int(round(work / (1 - 2 * MARGIN)))

    layer = Image.new("RGBA", (work, work), BEIGE)
    fg = Image.open(SRC).convert("RGBA").resize((work, work), Image.LANCZOS)
    layer = Image.alpha_composite(layer, fg)

    # 手机上看到的那一块：中间 72/108
    crop = int(round(work * VISIBLE))
    off = (work - crop) // 2
    shown = layer.crop((off, off, off + crop, off + crop))

    mask = Image.new("L", (crop, crop), 0)
    ImageDraw.Draw(mask).polygon(squircle(crop), fill=255)

    body = Image.new("RGBA", (crop, crop), (0, 0, 0, 0))
    body.paste(shown, (0, 0), mask)

    # 外层投影：底板整体向右下 (0.0024S, 0.024S)、模糊 0.022S、alpha 0.42
    shadow = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    plate = Image.new("RGBA", (crop, crop), SHADOW + (OUTER_ALPHA,))
    pos = (
        (canvas - crop) // 2 + int(0.0024 * crop),
        (canvas - crop) // 2 + int(0.024 * crop),
    )
    shadow.paste(plate, pos, mask)
    shadow = shadow.filter(ImageFilter.GaussianBlur(0.022 * crop))

    out = shadow
    out.alpha_composite(body, ((canvas - crop) // 2, (canvas - crop) // 2))
    return out.resize((ICON, ICON), Image.LANCZOS)


icon = build()
icon.save(OUT)
print("saved", OUT, icon.size)

# 自检：512 / 96 / 64 / 48 并排
strip = Image.new("RGB", (512 + 96 + 64 + 48 + 3 * 24, 512), (255, 255, 255))
x = 0
for s in (512, 96, 64, 48):
    strip.paste(icon.convert("RGB").resize((s, s), Image.LANCZOS), (x, (512 - s) // 2 if s < 512 else 0))
    x += s + 24
strip.save(CHECK)
print("check", CHECK)
