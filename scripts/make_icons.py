#!/usr/bin/env python3
"""Делает иконки приложения из design/icon-source.png (нужен Pillow).

    python3 scripts/make_icons.py

Создаёт:
  * адаптивную иконку Android 8+ (передний план + однотонный фон);
  * обычные квадратные и круглые иконки для Android 7 и ниже.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "design", "icon-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

src = Image.open(SRC).convert("RGB")
W = src.width

# цвет фона картинки: по краю, где нет рисунка
bg = tuple(int(v) for v in src.resize((1, 1), Image.BOX).getpixel((0, 0)))
px = src.load()
bg = px[int(W * 0.04), W // 2]
BG_HEX = "#%02X%02X%02X" % bg


def feathered_paste(canvas, img, feather):
    """Вставляет img по центру canvas, края плавно растворяются в фон."""
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rectangle(
        (feather, feather, img.width - feather - 1, img.height - feather - 1), fill=255
    )
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    x = (canvas.width - img.width) // 2
    y = (canvas.height - img.height) // 2
    canvas.paste(img, (x, y), mask)


# --- адаптивная иконка: 108 dp, видимая часть ~72 dp, безопасная зона 66 dp ---
FG = 432  # 108 dp при xxxhdpi
inner = int(FG * 0.70)  # рисунок целиком (с запасом) укладывается в безопасный круг
fg = Image.new("RGB", (FG, FG), bg)
feathered_paste(fg, src.resize((inner, inner), Image.LANCZOS), feather=int(inner * 0.06))
os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
fg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"), optimize=True)

os.makedirs(os.path.join(RES, "values"), exist_ok=True)
with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
    f.write(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_background">%s</color>\n</resources>\n' % BG_HEX
    )

adaptive = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background" />\n'
    '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
    "</adaptive-icon>\n"
)
os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(RES, "mipmap-anydpi-v26", name), "w", encoding="utf-8") as f:
        f.write(adaptive)

# --- обычные иконки (Android 7 и ниже) ---
crop = int(W * 0.96)
off = (W - crop) // 2
legacy_src = src.crop((off, off, off + crop, off + crop))
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dens, size in SIZES.items():
    d = os.path.join(RES, "mipmap-" + dens)
    os.makedirs(d, exist_ok=True)
    big = legacy_src.resize((size * 4, size * 4), Image.LANCZOS)  # рисуем крупно и сжимаем для гладких краёв
    for kind in ("square", "round"):
        m = Image.new("L", big.size, 0)
        dr = ImageDraw.Draw(m)
        if kind == "square":
            dr.rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius=int(big.width * 0.18), fill=255)
        else:
            dr.ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
        icon = big.convert("RGBA")
        icon.putalpha(m)
        icon = icon.resize((size, size), Image.LANCZOS)
        icon.save(os.path.join(d, "ic_launcher.png" if kind == "square" else "ic_launcher_round.png"), optimize=True)

print("фон", BG_HEX, "- иконки готовы")
