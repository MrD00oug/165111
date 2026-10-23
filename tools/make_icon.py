"""Gera o ícone do app: círculo escuro com uma seta laranja (estilo do pod)."""
import os
import sys
from PIL import Image, ImageDraw

out_root = sys.argv[1]
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dens, s in sizes.items():
    S = s * 4  # desenha grande e reduz (antialias)
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((0, 0, S - 1, S - 1), fill=(24, 27, 34, 255))
    d.ellipse((S * 0.06, S * 0.06, S * 0.94, S * 0.94), outline=(245, 165, 36, 255), width=int(S * 0.035))
    # seta virando à direita
    w = S * 0.11
    c = (245, 165, 36, 255)
    d.rectangle((S * 0.36, S * 0.42, S * 0.36 + w, S * 0.78), fill=c)              # haste vertical
    d.rectangle((S * 0.36, S * 0.42, S * 0.60, S * 0.42 + w), fill=c)              # haste horizontal
    d.polygon([(S * 0.58, S * 0.28), (S * 0.76, S * 0.42 + w / 2), (S * 0.58, S * 0.70)], fill=c)  # ponta
    im = im.resize((s, s), Image.LANCZOS)
    p = os.path.join(out_root, f"mipmap-{dens}")
    os.makedirs(p, exist_ok=True)
    im.save(os.path.join(p, "ic_launcher.png"))
print("ok")
