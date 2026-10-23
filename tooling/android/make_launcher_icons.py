"""Cut the Android adaptive-icon layers out of the artwork in keep_icons/.

Run from the repo root with Pillow installed:

    python3 tooling/android/make_launcher_icons.py

Each coloured icon becomes one transparent foreground layer (432 px = 108 dp at xxxhdpi, artwork
scaled into the 66 dp safe zone); the background is a colour resource shared by all of them. The
monochrome layer for themed icons is made from keep_monochrome.png, using darkness as opacity.
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "keep_icons"
OUT = ROOT / "apps" / "android" / "app" / "src" / "main" / "res" / "drawable-nodpi"

CANVAS = 432
ARTWORK = 276

ICONS = {
    "classic": "icon_keep.png",
    "blue": "keep_blue.png",
    "gray": "keep_gray.png",
    "green": "keep_green.png",
    "pink": "keep_pink.png",
    "red": "keep_red.png",
    "violet": "keep_violet.png",
    "yellow": "keep_yellow.png",
}


def fit(layer: Image.Image) -> Image.Image:
    box = layer.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox()
    layer = layer.crop(box)
    scale = ARTWORK / max(layer.size)
    layer = layer.resize((round(layer.width * scale), round(layer.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.paste(layer, ((CANVAS - layer.width) // 2, (CANVAS - layer.height) // 2), layer)
    return canvas


def monochrome() -> Image.Image:
    source = Image.open(SOURCE / "keep_monochrome.png").convert("RGBA")
    white = Image.new("RGBA", source.size, (255, 255, 255, 255))
    flat = Image.alpha_composite(white, source).convert("L")
    alpha = flat.point(lambda v: 255 - v)
    layer = Image.new("RGBA", source.size, (0, 0, 0, 255))
    layer.putalpha(alpha)
    return fit(layer)


def save(image: Image.Image, name: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    image.save(OUT / name, "WEBP", quality=92, alpha_quality=100, method=6)


def main() -> None:
    for name, file in ICONS.items():
        save(fit(Image.open(SOURCE / file).convert("RGBA")), f"ic_launcher_foreground_{name}.webp")
    save(monochrome(), "ic_launcher_monochrome.webp")


if __name__ == "__main__":
    main()
