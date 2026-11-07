#!/usr/bin/env python3
"""Generate density-specific launcher assets from the approved BARDO source artwork."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}
ASSETS = {
    "ic_launcher.webp": 48,
    "ic_launcher_foreground.webp": 108,
}

def main() -> None:
    for filename, base_dp in ASSETS.items():
        source = ROOT / "mipmap-mdpi" / filename
        if not source.is_file():
            raise SystemExit(f"Missing approved source artwork: {source}")
        with Image.open(source) as original:
            original.load()
            image = original.convert("RGBA")
            if image.width != image.height:
                raise SystemExit(f"Launcher artwork must be square: {filename} is {image.size}")
            for density, scale in DENSITIES.items():
                pixels = round(base_dp * scale)
                target_dir = ROOT / f"mipmap-{density}"
                target_dir.mkdir(parents=True, exist_ok=True)
                target = target_dir / filename
                resized = image.resize((pixels, pixels), Image.Resampling.LANCZOS)
                resized.save(target, format="WEBP", lossless=True, method=6)
                with Image.open(target) as check:
                    if check.size != (pixels, pixels):
                        raise SystemExit(f"Bad generated size for {target}: {check.size}")
                print(f"{target.relative_to(ROOT)}: {pixels}x{pixels}")

if __name__ == "__main__":
    main()
