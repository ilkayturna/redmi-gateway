"""Generate the opaque, single-size iOS app icon without external assets."""

from pathlib import Path

from PIL import Image, ImageDraw


SIZE = 2048
image = Image.new("RGB", (SIZE, SIZE))
pixels = image.load()
for y in range(SIZE):
    for x in range(SIZE):
        t = (0.65 * x + 0.35 * y) / SIZE
        pixels[x, y] = (
            round(13 + 10 * t),
            round(38 + 26 * t),
            round(62 + 35 * t),
        )

draw = ImageDraw.Draw(image)
white = (247, 252, 255)
mint = (82, 222, 189)

# Two devices share a short, bidirectional local link.
draw.rounded_rectangle((392, 514, 916, 1514), radius=112, outline=white, width=63)
draw.rounded_rectangle((1132, 514, 1656, 1514), radius=112, outline=white, width=63)
draw.rounded_rectangle((584, 1366, 722, 1384), radius=9, fill=white)
draw.rounded_rectangle((1324, 1366, 1462, 1384), radius=9, fill=white)

draw.line((949, 901, 1098, 901), fill=mint, width=54)
draw.polygon(((1088, 838), (1160, 901), (1088, 964)), fill=mint)
draw.line((1099, 1135, 950, 1135), fill=mint, width=54)
draw.polygon(((960, 1072), (888, 1135), (960, 1198)), fill=mint)

target = Path(__file__).parent / "RedmiGateway/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
target.parent.mkdir(parents=True, exist_ok=True)
image.resize((1024, 1024), Image.Resampling.LANCZOS).save(target, optimize=True)
print(target)
