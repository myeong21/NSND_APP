"""Export launcher resources from square artwork with built-in safe padding.

The eye/road emblem must already fit inside the central 60dp circle of the
108dp adaptive canvas. Keep the blue artwork full bleed; launchers apply masks.
"""

from pathlib import Path
from io import BytesIO
import sys

from PIL import Image, ImageChops, ImageDraw


LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

FOREGROUND_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def prepare_master(source: Path) -> Image.Image:
    image = Image.open(source).convert("RGBA")
    if image.width != image.height:
        raise ValueError("Launcher master must be square")
    if image.getchannel("A").getextrema() != (255, 255):
        raise ValueError("Use an opaque, full-bleed blue master with safe logo padding")
    return image


def resize(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.Resampling.LANCZOS)


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    buffer = BytesIO()
    image.save(buffer, "WEBP", lossless=True, method=6)
    encoded = buffer.getvalue()
    if path.exists() and path.read_bytes() == encoded:
        return
    path.write_bytes(encoded)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: generate_launcher_icons.py SOURCE PROJECT_ROOT")

    source = Path(sys.argv[1])
    project_root = Path(sys.argv[2])
    res = project_root / "app" / "src" / "main" / "res"
    master = prepare_master(source)

    design_dir = project_root / "design"
    design_dir.mkdir(parents=True, exist_ok=True)
    master.save(design_dir / "nsnd_launcher_master.png", optimize=True)

    # The artwork itself includes safe padding. Avoid adding a second inset:
    # Android 12 applies its own splash mask to this full-bleed square as well.
    splash_size = 768
    splash = resize(master, splash_size)
    splash_path = res / "drawable-nodpi" / "nsnd_splash_logo.png"
    splash_path.parent.mkdir(parents=True, exist_ok=True)
    splash.save(splash_path, optimize=True)

    for density, size in LEGACY_SIZES.items():
        square = resize(master, size)
        save_webp(square, res / f"mipmap-{density}" / "ic_launcher.webp")

        circle = square.copy()
        circle_mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(circle_mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        circle.putalpha(ImageChops.multiply(circle.getchannel("A"), circle_mask))
        save_webp(circle, res / f"mipmap-{density}" / "ic_launcher_round.webp")

    for density, size in FOREGROUND_SIZES.items():
        save_webp(resize(master, size), res / f"mipmap-{density}" / "ic_launcher_foreground.webp")

    # Preview the launcher's 72dp viewport, rather than incorrectly masking the
    # entire 108dp bitmap (which hides the cropping that caused the old issue).
    inset = master.width / 6
    viewport = master.crop((round(inset), round(inset), round(master.width - inset), round(master.height - inset)))
    tile_size = 256
    preview = Image.new("RGB", (tile_size * 3 + 64, tile_size + 32), "#edf1f7")
    for index, shape in enumerate(("square", "circle", "rounded")):
        tile = resize(master if shape == "square" else viewport, tile_size)
        mask = Image.new("L", (tile_size, tile_size), 0)
        painter = ImageDraw.Draw(mask)
        if shape == "circle":
            painter.ellipse((0, 0, tile_size - 1, tile_size - 1), fill=255)
        elif shape == "rounded":
            painter.rounded_rectangle((0, 0, tile_size - 1, tile_size - 1), radius=64, fill=255)
        else:
            painter.rectangle((0, 0, tile_size - 1, tile_size - 1), fill=255)
        preview.paste(tile, (16 + index * (tile_size + 16), 16), mask)
    preview.save(design_dir / "nsnd_launcher_mask_preview.png", optimize=True)


if __name__ == "__main__":
    main()
