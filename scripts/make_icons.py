#!/usr/bin/env python3
"""Делает иконки приложения из design/icon-source.png (нужен Pillow).

    python3 scripts/make_icons.py

В исходнике - готовая плитка со скруглёнными углами (медведь на бежевом фоне) на белом поле.
Иконка должна выглядеть ровно как эта картинка: без приближения и без уменьшения.

Создаёт:
  * адаптивную иконку Android 8+ (передний план; плитка точно во всём видимом окне);
  * обычные квадратные и круглые иконки для Android 7 и ниже.

Файл drawable-nodpi/bear_hero.png (большой медведь на экране лаунчера) скрипт не трогает.
"""
import os

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "design", "icon-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

src = Image.open(SRC).convert("RGB")
W = src.width

# --- прозрачные углы: всё белое вокруг плитки убираем ---
# Плитка бежевая (самый светлый пиксель ~ 241), поле белое (~ 253), поэтому порог 248 их делит.
r, g, b = src.split()
min_ch = ImageChops.darker(ImageChops.darker(r, g), b)  # самый тёмный из каналов
white = Image.eval(min_ch, lambda v: 255 if v >= 248 else 0)
mask = Image.eval(white, lambda v: 255 - v)
# убираем светлую кайму по краю (сглаживание картинки смешало белое с бежевым)
mask = mask.filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(0.8))

tile = src.convert("RGBA")
tile.putalpha(mask)


def resized(img, size):
    """Сжимает RGBA с прозрачными краями без светлой каймы (через предумноженный альфа-канал)."""
    return img.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")


# цвет фона плитки (сливочный), берём с чистого места у верхнего края
BG = src.getpixel((W // 2, int(W * 0.10)))
BG_HEX = "#%02X%02X%02X" % BG

# --- адаптивная иконка: холст 108 dp, видимое окно 72 dp (две трети) ---
FG = 432                 # 108 dp при xxxhdpi
VIS = FG * 72 // 108     # 288 px - всё, что launcher точно покажет
# подложка вокруг плитки: сильно размытая та же картинка (без белых углов), цвета продолжаются
cut = int(W * 0.065)
under = src.crop((cut, cut, W - cut, W - cut)).resize((FG, FG), Image.LANCZOS)
under = under.filter(ImageFilter.GaussianBlur(FG * 0.05)).convert("RGBA")
off = (FG - VIS) // 2
under.alpha_composite(resized(tile, VIS), (off, off))
os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
under.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"), optimize=True)

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

# --- обычные иконки (Android 7 и ниже): вся плитка целиком, как на картинке ---
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dens, size in SIZES.items():
    d = os.path.join(RES, "mipmap-" + dens)
    os.makedirs(d, exist_ok=True)
    big = resized(tile, size * 4)  # рисуем крупно и сжимаем для гладких краёв
    # квадратная: плитка как есть (скруглённые углы уже прозрачные)
    resized(big, size).save(os.path.join(d, "ic_launcher.png"), optimize=True)
    # круглая: тот же масштаб, обрезан кругом (медведь целиком помещается)
    circle = Image.new("L", big.size, 0)
    ImageDraw.Draw(circle).ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
    round_big = big.copy()
    a = Image.composite(big.getchannel("A"), Image.new("L", big.size, 0), circle)
    round_big.putalpha(a)
    resized(round_big, size).save(os.path.join(d, "ic_launcher_round.png"), optimize=True)

print("фон", BG_HEX, "- иконки готовы")
