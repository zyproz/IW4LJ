# Legacy 4: builds zz_legacy4.iwd, which replaces the main menu's background layers with the Legacy 4 banner.
# IWDs load in name order and the last one wins, so `zz_` puts these images over the stock ones of iw_00..iw_13.
# usage: python3 make_menu_iwd.py <banner image> <out .iwd>
import struct
import sys
import zipfile

from PIL import Image, ImageFilter

banner_path, out = sys.argv[1], sys.argv[2]


def iwi(img):
    """An uncompressed IWI v6: BGRA8 (format 1), no mipmaps (flag 0x02)."""
    img = img.convert("RGBA")
    w, h = img.size
    r, g, b, a = img.split()
    body = Image.merge("RGBA", (b, g, r, a)).tobytes()
    total = 28 + len(body)
    return b"IWi\x06" + bytes([1, 0x02]) + struct.pack("<3H", w, h, 1) + struct.pack("<4I", total, total, total, total) + body


banner = Image.open(banner_path).convert("RGBA").resize((1024, 576), Image.LANCZOS)
blurred = banner.filter(ImageFilter.GaussianBlur(10)).point(lambda v: int(v * 0.6))
clear = Image.new("RGBA", (8, 8), (0, 0, 0, 0))

images = {
    # The scene behind the main menu: the banner.
    "bg_back": banner,
    # The soldier in front, the moving fog and the CoD4 logo: gone.
    "bg_front": clear,
    "bg_front2": clear,
    "bg_fogscroll": clear,
    "bg_fogscrollthin": clear,
    "main_back_top_mp_english": clear,
    # The blurred background of the other menus: the banner, blurred and darker.
    "bg_blur_back": blurred,
    "bg_blur_front": clear,
    "bg_blur_fogscroll": clear,
}

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for name, img in images.items():
        z.writestr(f"images/{name}.iwi", iwi(img))
print(out, "written")
