#!/usr/bin/env python3
"""Builds the published images from real screenshots of the Android app.

    python3 tooling/screenshots/compose.py <raw-en-dir> <raw-ru-dir>

Each raw directory holds 1080×2340 phone screenshots named as in SCREENS, taken from the release build with the demo
databases (tooling/screenshots/generate_demo_vault.py) and «Allow screenshots» on. Writes, for English and Russian:

  docs/assets/screenshots/<lang>/0N-*.png   README screenshots (540×1170, rounded)
  docs/assets/showcase/showcase-<lang>.png  the README's hero composition (2400×1200)
  docs/assets/store/<lang>/N.png            store screenshots (1080×1920, 9:16)

and docs/assets/brand/vazie-keep-icon-rounded.png, the icon on its plate for the README header.

Needs Pillow. The font is the app's own (apps/android/app/src/main/res/font/geologica.ttf). Nothing here draws an
interface: every phone screen is a screenshot of the real app; only the status and navigation bars are painted over
with the screen's own background, so no clock, notification or carrier shows.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FONT = os.path.join(ROOT, "apps/android/app/src/main/res/font/geologica.ttf")
# The launcher artwork at full size when it is on this machine (keep_icons/ stays out of git), else the README copy.
ICON_SOURCES = [os.path.join(ROOT, "keep_icons/icon_keep.png"), os.path.join(ROOT, "docs/assets/brand/vazie-keep-icon.png")]
ROUNDED_ICON = os.path.join(ROOT, "docs/assets/brand/vazie-keep-icon-rounded.png")

SCREENS = ["02-vault", "03-entry", "04-generator", "05-appearance", "06-settings", "07-vault-light"]
README_SCREENS = SCREENS[:4]
STATUS_BAR, NAV_BAR = 110, 135

# The Keep palette: Obsidian, lavender and cream.
INK = (18, 16, 20)
PLUM = (40, 30, 58)
LAVENDER = (201, 182, 242)
CREAM = (241, 227, 200)
WHITE = (246, 243, 250)
MUTED = (182, 174, 196)

CAPTIONS = {
    "en": [
        ("Your KeePass file, on your phone", "Groups and entries from the .kdbx you already own"),
        ("Secrets stay hidden", "Copy or reveal only when you ask"),
        ("Strong passwords in a tap", "Passwords and passphrases, every option free"),
        ("Make it yours", "Ivory or Obsidian, eight app icons, three backgrounds"),
        ("It locks itself", "Auto-lock, clipboard clearing and biometrics"),
        ("Ivory or Obsidian", "No ads, no analytics, no account needed"),
    ],
    "ru": [
        ("Ваш файл KeePass на телефоне", "Группы и записи из .kdbx, который уже ваш"),
        ("Секреты скрыты", "Скопировать или показать — только по нажатию"),
        ("Надёжный пароль за секунду", "Пароли и парольные фразы — всё бесплатно"),
        ("Оформите по-своему", "Ivory или Obsidian, восемь значков, три фона"),
        ("Блокируется сам", "Автоблокировка, очистка буфера, биометрия"),
        ("Ivory или Obsidian", "Без рекламы, аналитики и обязательного аккаунта"),
    ],
}

HERO = {
    "en": ("Vazie Keep", "A password manager for the\nKeePass file you already own",
           ["KDBX 4 · KeePass-compatible", "Works offline", "Autofill & passkeys", "No account · No analytics"]),
    "ru": ("Vazie Keep", "Менеджер паролей для файла\nKeePass, который уже ваш",
           ["KDBX 4 · совместимо с KeePass", "Работает офлайн", "Автозаполнение и passkeys", "Без аккаунта и аналитики"]),
}


def font(size, weight=400):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_axes([weight, 0, 0, 0])  # Weight, Cursive, Sharpness, Slant
    return f


def background(w, h):
    """Obsidian, warmer towards the top right, with a soft lavender glow."""
    corners = Image.new("RGB", (2, 2))
    corners.putdata([INK, PLUM, INK, (28, 22, 38)])
    base = corners.resize((w, h), Image.BILINEAR)
    glow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(glow).ellipse((int(w * 0.5), int(-h * 0.3), int(w * 1.2), int(h * 0.5)), fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) // 4)).point(lambda v: int(v * 0.26))
    return Image.composite(Image.new("RGB", (w, h), LAVENDER), base, glow)


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def clean(shot):
    """The screenshot without the phone's status and navigation bars: both take the screen's own background."""
    shot = shot.convert("RGB").copy()
    w, h = shot.size
    draw = ImageDraw.Draw(shot)
    draw.rectangle((0, 0, w, STATUS_BAR), fill=shot.getpixel((5, STATUS_BAR + 40)))
    draw.rectangle((0, h - NAV_BAR, w, h), fill=shot.getpixel((5, h - NAV_BAR - 15)))
    return shot


def rounded_icon(size=420):
    """The shield on a rounded plate, plum to midnight, for the README header and the showcase."""
    source = next(p for p in ICON_SOURCES if os.path.exists(p))
    corners = Image.new("RGB", (2, 2))
    corners.putdata([(74, 40, 128), (40, 30, 92), (34, 26, 78), (18, 34, 66)])
    plate = rounded(corners.resize((size, size), Image.BILINEAR), round(size * 0.22))
    glow = Image.new("L", (size, size), 0)
    ImageDraw.Draw(glow).ellipse((int(size * 0.15), int(size * 0.1), int(size * 0.85), int(size * 0.8)), fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(size // 6)).point(lambda v: int(v * 0.35))
    lit = Image.composite(Image.new("RGBA", (size, size), (*LAVENDER, 255)), plate, glow)
    lit.putalpha(plate.getchannel("A"))
    shield = Image.open(source).convert("RGBA")
    inner = round(size * 0.80)
    shield = shield.resize((inner, inner), Image.LANCZOS)
    lit.alpha_composite(shield, ((size - inner) // 2, (size - inner) // 2 + round(size * 0.01)))
    return lit


def phone(shot, height):
    """The screenshot as a phone screen: rounded, with a thin lavender rim."""
    w = round(shot.width * height / shot.height)
    screen = rounded(shot.resize((w, height), Image.LANCZOS), radius=round(w * 0.075))
    rim = 6
    frame = Image.new("RGBA", (w + 2 * rim, height + 2 * rim), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle(
        (0, 0, frame.width - 1, frame.height - 1), round(w * 0.075) + rim, fill=(52, 44, 70, 255), outline=(*LAVENDER, 140), width=2
    )
    frame.alpha_composite(screen, (rim, rim))
    return frame


def paste_with_shadow(canvas, item, xy):
    shadow = Image.new("RGBA", (item.width + 160, item.height + 160), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((80, 100, 80 + item.width, 100 + item.height), 80, fill=(4, 2, 10, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(40))
    canvas.alpha_composite(shadow, (xy[0] - 80, xy[1] - 80))
    canvas.alpha_composite(item, xy)


def centered(draw, y, text, f, fill, width):
    box = draw.textbbox((0, 0), text, font=f)
    draw.text(((width - (box[2] - box[0])) // 2, y), text, font=f, fill=fill)


def fitted(draw, text, size, weight, width):
    """The largest size up to [size] at which [text] fits in [width]."""
    while size > 20 and draw.textlength(text, font=font(size, weight)) > width:
        size -= 2
    return font(size, weight)


def store_image(shot, title, subtitle):
    w, h = 1080, 1920
    canvas = background(w, h).convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    centered(draw, 120, title, fitted(draw, title, 64, 700, w - 120), WHITE, w)
    centered(draw, 214, subtitle, fitted(draw, subtitle, 36, 400, w - 120), MUTED, w)
    item = phone(shot, 1500)
    paste_with_shadow(canvas, item, ((w - item.width) // 2, 340))
    return canvas.convert("RGB")


def showcase(shots, icon, lang):
    w, h = 2400, 1200
    canvas = background(w, h).convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    name, tagline, chips = HERO[lang]
    canvas.alpha_composite(icon.resize((150, 150), Image.LANCZOS), (150, 250))
    draw.text((150, 440), name, font=font(110, 700), fill=WHITE)
    draw.multiline_text((152, 590), tagline, font=font(46, 400), fill=MUTED, spacing=14)
    x, y = 152, 760
    chip_font = font(26, 500)
    for chip in chips:
        tw = draw.textlength(chip, font=chip_font)
        if x + tw + 40 > 960:
            x, y = 152, y + 64
        draw.rounded_rectangle((x, y, x + tw + 36, y + 48), 24, fill=(38, 30, 52), outline=(*LAVENDER, 110), width=2)
        draw.text((x + 18, y + 9), chip, font=chip_font, fill=CREAM)
        x += tw + 52
    # Three real screens, staggered: the database, the generator, and the database in Ivory.
    for i, (key, top) in enumerate([("02-vault", 190), ("04-generator", 100), ("07-vault-light", 190)]):
        item = phone(shots[key], 900)
        paste_with_shadow(canvas, item, (1010 + i * 455, top))
    return canvas.convert("RGB")


def main(raw_en, raw_ru):
    icon = rounded_icon()
    os.makedirs(os.path.dirname(ROUNDED_ICON), exist_ok=True)
    icon.save(ROUNDED_ICON, optimize=True)
    for lang, raw in (("en", raw_en), ("ru", raw_ru)):
        shots = {key: clean(Image.open(os.path.join(raw, key + ".png"))) for key in SCREENS}

        readme_dir = os.path.join(ROOT, "docs/assets/screenshots", lang)
        os.makedirs(readme_dir, exist_ok=True)
        for key in README_SCREENS:
            rounded(shots[key].resize((540, 1170), Image.LANCZOS), 40).save(os.path.join(readme_dir, key + ".png"), optimize=True)

        showcase_dir = os.path.join(ROOT, "docs/assets/showcase")
        os.makedirs(showcase_dir, exist_ok=True)
        showcase(shots, icon, lang).save(os.path.join(showcase_dir, f"showcase-{lang}.png"), optimize=True)

        store_dir = os.path.join(ROOT, "docs/assets/store", lang)
        os.makedirs(store_dir, exist_ok=True)
        for n, (key, (title, subtitle)) in enumerate(zip(SCREENS, CAPTIONS[lang]), start=1):
            store_image(shots[key], title, subtitle).save(os.path.join(store_dir, f"{n}.png"), optimize=True)
    print("done")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
