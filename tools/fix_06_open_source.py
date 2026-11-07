#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════════╗
║  TUZATISH #6 — Yangi ochiq manba obunasi: Radikal Hysteria2              ║
╠══════════════════════════════════════════════════════════════════════════╣
║  Qo'shiladi:                                                             ║
║    id              : radikal_hy2                                         ║
║    nameRes         : os_radikal_hy2_name  ("Radikal Hysteria2")          ║
║    descriptionRes  : os_radikal_hy2_desc  ("Hysteria2 configs by         ║
║                                            0xRadikal")                  ║
║    url             : raw.githubusercontent.com/0xRadikal/Free-v2ray-     ║
║                      Configs/main/protocols/hysteria2.txt                ║
║    flag            : 🌐                                                  ║
║                                                                          ║
║  strings.xml (UZ, RU, EN) — uchtasiga ham nom va tavsif qo'shiladi.      ║
║                                                                          ║
║  ⚠️ QO'SHIMCHA: `toast_sub_url_hint` (3-muammo uchun) ham qo'shiladi,    ║
║  chunki foydalanuvchi to'g'ridan-to'g'ri obuna URL'ini yopishtirsa       ║
║  tushunarli yo'l-yo'riq ko'rsatilishi kerak.                             ║
╚══════════════════════════════════════════════════════════════════════════╝
"""
import re
import sys
from pathlib import Path

import sys as _sys
_sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "app/src/main/java/com/nurvpn/app/storage/OpenSourceSubscriptions.kt"

NEW_ENTRY = (
    '        OpenSourceSubscription(id = "radikal_hy2", '
    'nameRes = R.string.os_radikal_hy2_name, '
    'descriptionRes = R.string.os_radikal_hy2_desc, '
    'url = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/protocols/hysteria2.txt", '
    'flag = "\\uD83C\\uDF10"),\n'
)

# Har bir til uchun (nom, tavsif, sub-url-yo'riqnoma)
STRINGS = {
    "values": (
        "Radikal Hysteria2",
        "Hysteria2 configs by 0xRadikal",
        "Bu obuna URL manzili. Uni qo\\'shish uchun «Ochiq manbalar» bo\\'limidan foydalaning",
    ),
    "values-ru": (
        "Radikal Hysteria2",
        "Hysteria2 configs by 0xRadikal",
        "Это URL подписки. Для добавления используйте раздел «Открытые источники»",
    ),
    "values-uz": (
        "Radikal Hysteria2",
        "Hysteria2 configs by 0xRadikal",
        "Bu obuna URL manzili. Uni qo\\'shish uchun «Ochiq manbalar» bo\\'limidan foydalaning",
    ),
}


def patch_catalog() -> bool:
    src = CATALOG.read_text(encoding="utf-8")
    if 'id = "radikal_hy2"' in src:
        print("ℹ️  radikal_hy2 allaqachon mavjud — o'tkazib yuborildi")
        return True

    # Oxirgi elementdan keyin qo'shamiz
    anchor = 'flag = "\\u2601\\uFE0F")\n    )'
    if anchor not in src:
        print("❌ Katalog oxiri topilmadi (anchor)")
        return False

    safe_backup(CATALOG, "fix6")

    src = src.replace(anchor, 'flag = "\\u2601\\uFE0F"),\n' + NEW_ENTRY + "    )", 1)
    CATALOG.write_text(src, encoding="utf-8")
    print(f"✅ Yangilandi: {CATALOG.relative_to(ROOT)}")
    return True


def patch_strings() -> bool:
    ok = True
    for folder, (name, desc, hint) in STRINGS.items():
        path = ROOT / f"app/src/main/res/{folder}/strings.xml"
        if not path.exists():
            print(f"❌ Topilmadi: {path}")
            ok = False
            continue

        src = path.read_text(encoding="utf-8")

        if "os_radikal_hy2_name" in src:
            print(f"ℹ️  {folder}: allaqachon mavjud")
            continue

        safe_backup(path, "fix6")

        # toast_invalid_link qatoridan keyin qo'shamiz (barcha tillarda bor)
        anchor_re = re.compile(
            r'(\s*<string name="toast_invalid_link">.*?</string>\n)'
        )
        m = anchor_re.search(src)
        if not m:
            print(f"❌ {folder}: anchor topilmadi")
            ok = False
            continue

        block = (
            m.group(1)
            + f'    <string name="toast_sub_url_hint">{hint}</string>\n'
            + f'    <string name="os_radikal_hy2_name">{name}</string>\n'
            + f'    <string name="os_radikal_hy2_desc">{desc}</string>\n'
        )
        src = src[:m.start()] + block + src[m.end():]
        path.write_text(src, encoding="utf-8")
        print(f"✅ {folder}: 3 ta string qo'shildi")
    return ok


def main() -> int:
    a = patch_catalog()
    b = patch_strings()
    return 0 if (a and b) else 1


if __name__ == "__main__":
    sys.exit(main())
