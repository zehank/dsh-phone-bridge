"""生成 PWA 图标（和 icon.svg 同一套几何）。用：python3 make-icons.py"""
import numpy as np
from PIL import Image, ImageDraw

S = 512
k = S / 96  # svg viewBox 96 -> 512

# 背景：圆角矩形 + 对角渐变 #4f8cff -> #2450b8
yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
t = np.clip((xx + yy) / (2 * S), 0, 1)[..., None]
c0 = np.array([0x4F, 0x8C, 0xFF], np.float32)
c1 = np.array([0x24, 0x50, 0xB8], np.float32)
grad = (c0 * (1 - t) + c1 * t).astype(np.uint8)

mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(22 * k), fill=255)
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
img.paste(Image.fromarray(grad, "RGB"), (0, 0), mask)
d = ImageDraw.Draw(img)

w = 3.4 * k
# 手机外框
d.rounded_rectangle([26 * k, 17 * k, 53 * k, 63 * k], radius=6.5 * k, outline=(255, 255, 255, 255), width=int(w))
# 底部横条
d.rounded_rectangle([36.5 * k, 55 * k, 42.5 * k, 57.4 * k], radius=1.2 * k, fill=(255, 255, 255, 255))
# 信号弧（右上一小段 + 大一点的一段）
cx, cy = 63.5 * k, 53 * k
for r, sw, op in ((12 * k, w, 255), (22 * k, 3.2 * k, 178)):
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(layer).arc(
        [cx - r, cy - r, cx + r, cy + r], start=-88, end=6, fill=(255, 255, 255, op), width=int(sw)
    )
    img = Image.alpha_composite(img, layer)
d = ImageDraw.Draw(img)
d.ellipse([cx - 2.6 * k, cy - 2.6 * k, cx + 2.6 * k, cy + 2.6 * k], fill=(255, 255, 255, 255))

img.resize((512, 512), Image.LANCZOS).save("web/icon-512.png")
img.resize((192, 192), Image.LANCZOS).save("web/icon-192.png")
print("wrote web/icon-512.png, web/icon-192.png")
