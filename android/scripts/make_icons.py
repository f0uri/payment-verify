#!/usr/bin/env python3
"""
make_icons.py — توليد أيقونة تطبيق Payment Verify (هوية زجاجية iOS)
- طبقة خلفية: تدرج كحلي عميق + توهجات بنفسجية/زرقاء (كخلفية التطبيق)
- طبقة أمامية: بطاقة دفع متدرجة مائلة + شريحة ذهبية + شارة تحقق خضراء
المخرجات: mipmap للأيقونة التكيفية (API 26+) + legacy لـ API 24-25
"""
from PIL import Image, ImageDraw, ImageFilter
import os

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app/src/main/res")
SS = 4  # معامل الحجم الزائد للحدة (supersampling)

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def diagonal_gradient(size, c1, c2):
    """تدرج قطري (من أعلى-يسار إلى أسفل-يمين)"""
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / max(w - 1, 1) + y / max(h - 1, 1)) / 2
            px[x, y] = lerp(c1, c2, t)
    return img

def make_background(px):
    """خلفية الأيقونة: كحلي داكن + توهج بنفسجي (أعلى اليمين) + أزرق (أسفل اليسار)"""
    s = px * SS
    bg = diagonal_gradient((s, s), (13, 13, 34), (33, 22, 74)).convert("RGBA")

    blob = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(blob)
    r = int(s * 0.55)
    d.ellipse([s - r, -r * 0.5, s + r * 0.6, r * 0.8], fill=(94, 92, 230, 200))   # بنفسجي أعلى اليمين
    d.ellipse([-r * 0.6, s - r * 0.9, r * 0.8, s + r * 0.5], fill=(10, 132, 255, 190))  # أزرق أسفل اليسار
    blob = blob.filter(ImageFilter.GaussianBlur(s * 0.16))
    bg.alpha_composite(blob)
    return bg.resize((px, px), Image.LANCZOS)

def make_foreground(px):
    """البطاقة الزجاجية المائلة + الشريحة + شارة التحقق"""
    s = px * SS
    fg = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    # ---- البطاقة (تُرسم منفصلة ثم تُدار -7 درجة) ----
    cw, ch = int(s * 0.560), int(s * 0.385)          # أبعاد البطاقة
    card = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    rad = int(ch * 0.16)

    # جسم البطاقة: تدرج أزرق iOS -> بنفسجي
    grad = diagonal_gradient((cw, ch), (10, 132, 255), (94, 92, 230)).convert("RGBA")
    mask = Image.new("L", (cw, ch), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, cw - 1, ch - 1], radius=rad, fill=255)
    card.paste(grad, (0, 0), mask)

    # لمعة زجاجية علوية (glass sheen)
    sheen = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
    ds = ImageDraw.Draw(sheen)
    ds.rounded_rectangle([0, 0, cw - 1, int(ch * 0.46)], radius=rad, fill=(255, 255, 255, 52))
    sheen = sheen.filter(ImageFilter.GaussianBlur(cw * 0.02))
    card.alpha_composite(sheen)

    # حافة زجاجية
    d = ImageDraw.Draw(card)
    d.rounded_rectangle([0, 0, cw - 1, ch - 1], radius=rad, outline=(255, 255, 255, 120), width=max(2, cw // 150))

    # الشريحة الذهبية
    chip_w, chip_h = int(cw * 0.155), int(ch * 0.24)
    cx, cy = int(cw * 0.09), int(ch * 0.30)
    d.rounded_rectangle([cx, cy, cx + chip_w, cy + chip_h], radius=int(chip_h * 0.28),
                        fill=(255, 214, 10, 255), outline=(255, 244, 160, 255), width=max(2, cw // 300))
    d.line([cx + chip_w * 0.5, cy, cx + chip_w * 0.5, cy + chip_h], fill=(212, 175, 55, 255), width=max(2, cw // 350))
    d.line([cx, cy + chip_h * 0.5, cx + chip_w, cy + chip_h * 0.5], fill=(212, 175, 55, 255), width=max(2, cw // 350))

    # نقاط رقم البطاقة
    dy = int(ch * 0.72)
    dot_r = max(3, int(cw * 0.018))
    gap = int(cw * 0.042)
    start_x = int(cw * 0.10)
    for i in range(4):
        x = start_x + i * (dot_r * 2 + gap)
        d.ellipse([x, dy - dot_r, x + dot_r * 2, dy + dot_r], fill=(255, 255, 255, 235))

    # موجات الـ NFC أعلى اليمين
    for i, rr in enumerate((0.055, 0.085, 0.115)):
        d.arc([cw * 0.80 - cw * rr, ch * 0.30 - cw * rr, cw * 0.80 + cw * rr, ch * 0.30 + cw * rr],
              start=-50, end=50, fill=(255, 255, 255, 180), width=max(2, cw // 220))

    # تدوير البطاقة ولصقها في المركز (منطقة الأمان 66%)
    card = card.rotate(7, expand=True, resample=Image.BICUBIC)
    fx = (s - card.width) // 2
    fy = int(s * 0.46) - card.height // 2
    fg.alpha_composite(card, (fx, fy))

    # ---- شارة التحقق الخضراء (أعلى يمين البطاقة) ----    # الموضع يتبع زاوية البطاقة
    badge_r = int(s * 0.085)
    bx = fx + int(card.width * 0.94)
    by = fy + int(card.height * 0.12)
    badge = Image.new("RGBA", (badge_r * 2 + 8, badge_r * 2 + 8), (0, 0, 0, 0))
    db = ImageDraw.Draw(badge)
    db.ellipse([4, 4, 4 + badge_r * 2, 4 + badge_r * 2], fill=(48, 209, 88, 255),
               outline=(255, 255, 255, 230), width=max(3, badge_r // 5))
    lw = max(4, badge_r // 3)
    db.line([(4 + badge_r * 0.52, 4 + badge_r * 1.02), (4 + badge_r * 0.88, 4 + badge_r * 1.38),
             (4 + badge_r * 1.52, 4 + badge_r * 0.62)], fill=(255, 255, 255, 255), width=lw, joint="curve")
    badge = badge.rotate(-7, expand=True, resample=Image.BICUBIC)
    fg.alpha_composite(badge, (bx - badge.width // 2, by - badge.height // 2))

    return fg.resize((px, px), Image.LANCZOS)

def rounded(img, radius_ratio=0.225):
    """قص الزوايا للنسخة التقليدية (legacy)"""
    s = img.width
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * radius_ratio), fill=255)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out

def save(img, rel, sizes):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((sizes, sizes), Image.LANCZOS).save(path)
    print("✓", rel, sizes)

bg_ref, fg_ref = None, None

# الأيقونة التكيفية (طبقات 108dp)
for dpi, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
    os.makedirs(os.path.join(RES, f"mipmap-{dpi}"), exist_ok=True)
    bg = make_background(px); bg.save(os.path.join(RES, f"mipmap-{dpi}/ic_bg.png"))
    fg = make_foreground(px); fg.save(os.path.join(RES, f"mipmap-{dpi}/ic_fg.png"))
    if dpi == "xxxhdpi":
        bg_ref, fg_ref = bg, fg
    print("✓", f"mipmap-{dpi}/ic_bg.png + ic_fg.png", px)

# النسخة التقليدية (48dp أساس) — مركّبة من الخلفية والأمامية
for dpi, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    comp = Image.alpha_composite(
        bg_ref.resize((432, 432), Image.LANCZOS), fg_ref.resize((432, 432), Image.LANCZOS)
    ).resize((px * SS, px * SS), Image.LANCZOS)
    comp = rounded(comp).resize((px, px), Image.LANCZOS)
    save(comp, f"mipmap-{dpi}/ic_launcher.png", px)

# معاينة كبيرة للعرض
os.makedirs("/home/user/payment-verify/design", exist_ok=True)
prev = Image.alpha_composite(bg_ref.copy(), fg_ref.copy()).resize((512, 512), Image.LANCZOS)
rounded(Image.alpha_composite(
    bg_ref.resize((512, 512), Image.LANCZOS), fg_ref.resize((512, 512), Image.LANCZOS))
).save("/home/user/payment-verify/design/icon-512-rounded.png")
prev.save("/home/user/payment-verify/design/icon-adaptive-preview.png")
print("✓ معاينة: design/icon-512-rounded.png + icon-adaptive-preview.png")
