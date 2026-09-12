"""Generate Android launcher icon resources from a square master artwork."""

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
    # Image generation can leave a dark matte around rounded corners. Remove
    # only the edge-connected matte so the adaptive background can show through.
    for point in ((0, 0), (image.width - 1, 0), (0, image.height - 1), (image.width - 1, image.height - 1)):
        ImageDraw.floodfill(image, point, (0, 0, 0, 0), thresh=48)
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

    # Android 12 applies its own splash-icon mask. Generous transparent padding
    # keeps the complete rounded-square logo visible inside every device mask.
    splash_size = 768
    splash_logo_size = 500
    splash = Image.new("RGBA", (splash_size, splash_size), (0, 0, 0, 0))
    splash_logo = resize(master, splash_logo_size)
    splash_offset = (splash_size - splash_logo_size) // 2
    splash.alpha_composite(splash_logo, (splash_offset, splash_offset))
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


if __name__ == "__main__":
    main()
