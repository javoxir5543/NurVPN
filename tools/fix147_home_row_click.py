#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FIX147-HOMEROW-CLICK — Home kartasi ichidagi server qatori bosilmasligi

════════════════════════════════════════════════════════════════════════
 MUAMMO
════════════════════════════════════════════════════════════════════════

Foydalanuvchi Home ekranida obuna kartasini ochib, ichidagi BOSHQA
serverni bosadi — VPN o'sha serverga ulanmaydi.

ILDIZ SABAB (kod o'qish + qurilma logi bilan tasdiqlangan):

  `item_home_row.xml` da ICHKI `LinearLayout` (24-qator) da:

      android:clickable="true"      (33-qator)
      android:focusable="true"      (34-qator)
      android:background="?android:attr/selectableItemBackground"  (35)

  bor. `RowVH.bind()` esa listener'ni ILDIZGA qo'yadi:

      itemView.setOnClickListener { a.selectServer(si); ... }

  Natijada ichki `LinearLayout` (u `layout_weight=1` bilan qatorning
  deyarli butun kengligini egallaydi) TAPNI YUTADI va
  `itemView.setOnClickListener` UMUMAN chaqirilmaydi.

  → `selectServer()` chaqirilmaydi → `restartVpn()` chaqirilmaydi
  → VPN eski serverda qolib ketadi.

  ⚠️ Bu — FIX143 dagi AYNAN bir xil xato. FIX143 da
  `item_home_card_header.xml` uchun tuzatilgan edi ("inner
  LinearLayout consumed taps"), lekin `item_home_row.xml` da
  tuzatish QILINMAGAN.

════════════════════════════════════════════════════════════════════════
 YECHIM
════════════════════════════════════════════════════════════════════════

  clickable / focusable / background ni ICHKI `LinearLayout` dan
  ILDIZ `LinearLayout` ga ko'chiramiz (FIX143 bilan bir xil usul).

  Bu XAVFSIZ:
    • `row_fav` (yulduzcha) o'z clickable'ini SAQLAB qoladi —
      unga tegmaymiz.
    • `select_check` allaqachon clickable=false.
    • Layout o'lchamlari/padding O'ZGARMAYDI — faqat touch target
      ildizga ko'chadi.
    • Ripple effekti saqlanadi (background ko'chadi).

════════════════════════════════════════════════════════════════════════
 ISHLATISH
════════════════════════════════════════════════════════════════════════

    python3 tools/fix147_home_row_click.py --check   # sinov
    python3 tools/fix147_home_row_click.py           # qo'llash

  Idempotent: 2-marta ishga tushirilsa hech narsa o'zgarmaydi.
  Backup: .fix-backups/item_home_row.xml.fix147click.<STAMP>.bak
"""

import argparse
import hashlib
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAYOUT = ROOT / "app/src/main/res/layout/item_home_row.xml"
BACKUP_DIR = ROOT / ".fix-backups"
STAMP = time.strftime("%Y%m%d_%H%M%S")

# ── Naqshlar ────────────────────────────────────────────────────────
# ICHKI LinearLayout dan olib tashlanadigan atribut qatorlari
# ⚠️ Oxirgi `>` ALOHIDA ishlanadi — u tegni yopadi.
INNER_ATTRS = [
    '        android:clickable="true"\n',
    '        android:focusable="true"\n',
    '        android:background="?android:attr/selectableItemBackground"',
]

# ILDIZGA qo'shiladigan blok (ichkidan ko'chirilgan)
ROOT_ADD = (
    '    android:clickable="true"\n'
    '    android:focusable="true"\n'
    '    android:background="?android:attr/selectableItemBackground">\n'
)

# ILDIZning hozirgi yopilish qatori (ko'chirishdan oldin)
ROOT_OLD_CLOSE = '    android:orientation="vertical">\n'

# Guard — allaqachon qilinganligini bildiruvchi belgi
GUARD = '    android:clickable="true"\n    android:focusable="true"\n    android:background="?android:attr/selectableItemBackground">\n    \n'
GUARD_SIMPLE = 'android:orientation="vertical">\n    android:clickable="true"'


def md5(p: Path) -> str:
    return hashlib.md5(p.read_bytes()).hexdigest()


def backup(p: Path) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    dst = BACKUP_DIR / f"{p.name}.fix147click.{STAMP}.bak"
    shutil.copy2(p, dst)
    return dst


def validate(text: str) -> bool:
    """XML yaroqli ekanini tekshiradi."""
    try:
        ET.fromstring(text)
        return True
    except ET.ParseError as e:
        print(f"   ✗ XML XATO: {e}")
        return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="Faqat tekshirish, yozmaslik")
    args = ap.parse_args()

    print("═" * 68)
    print("  FIX147-HOMEROW-CLICK — Home kartasidagi server qatori bosilishi")
    print("═" * 68)

    if not LAYOUT.exists():
        print(f"✗ Fayl topilmadi: {LAYOUT}")
        return 1

    text = LAYOUT.read_text(encoding="utf-8")
    before_hash = md5(LAYOUT)
    changes = []

    # ── 1) Idempotentlik tekshiruvi ─────────────────────────────────
    # ⚠️ `find(">")` XML deklaratsiyasining `?>` ini topadi!
    # Shuning uchun ildiz tegni `<LinearLayout` dan boshlab qidiramiz.
    root_start = text.find("<LinearLayout")
    first_gt = text.find(">", root_start) if root_start >= 0 else -1
    root_tag = text[root_start:first_gt + 1] if first_gt > 0 else ""
    already = ('android:clickable="true"' in root_tag
               and 'android:focusable="true"' in root_tag)

    if already:
        print("\n✓ ILDIZDA clickable allaqachon bor — o'zgarish kerak emas")
        print(f"   hash: {before_hash}")
        return 0

    print(f"\n   oldingi hash: {before_hash}")

    # ── 2) ICHKIDAN 3 qatorni olib tashlash ─────────────────────────
    # ⚠️ FAQAT ichki LinearLayout hududida (ildiz `>` dan keyin,
    #    `row_fav` dan oldin). `row_fav` ham clickable="true" —
    #    unga TEGMASLIK kerak.
    print("\n[1/3] ICHKI LinearLayout dan clickable/focusable/background")

    inner_start = text.find(">", text.find("<LinearLayout")) + 1
    fav_pos = text.find('android:id="@+id/row_fav"')
    if fav_pos < 0:
        print("   ✗ row_fav topilmadi — xavfsiz to'xtatamiz")
        return 1
    inner_end = text.rfind("<ImageView", inner_start, fav_pos)
    if inner_end < 0:
        print("   ✗ ichki LinearLayout chegarasi topilmadi")
        return 1

    inner = text[inner_start:inner_end]
    outer = text[inner_end:]

    removed = 0
    for attr in INNER_ATTRS:
        if attr in inner:
            # Atribut qatorini butunlay olib tashlaymiz (oxiridagi \n bilan)
            inner = inner.replace(attr + "\n", "", 1) if attr + "\n" in inner \
                else inner.replace(attr, "", 1)
            removed += 1
            print(f"   ✓ olib tashlandi: {attr.strip()}")
        else:
            print(f"   ⚠ topilmadi: {attr.strip()}")

    # Ichki teg yopilishi: `android:paddingBottom="8dp"` dan keyin
    # yolg'iz `>` qator qolgan bo'lishi mumkin — uni oldingi qatorga
    # qo'shib, toza XML hosil qilamiz.
    pb = '        android:paddingBottom="8dp"\n'
    lone_gt = '>\n'
    if pb + lone_gt in inner:
        inner = inner.replace(pb + lone_gt, pb.rstrip("\n") + ">\n", 1)
        print("   ✓ yolg'iz '>' tozalandi (paddingBottom qatoriga qo'shildi)")

    text = text[:inner_start] + inner + outer

    if removed == 0:
        print("\n✗ Hech narsa olib tashlanmadi — fayl tuzilishi boshqacha")
        return 1
    changes.append(f"ichkidan {removed} qator olib tashlandi")

    # ── 3) ILDIZGA blokni qo'shish ──────────────────────────────────
    print("\n[2/3] ILDIZ LinearLayout ga clickable/focusable/background")
    if text.count(ROOT_OLD_CLOSE) < 1:
        print(f"   ✗ ILDIZ naqshi topilmadi: {ROOT_OLD_CLOSE.strip()}")
        return 1
    text = text.replace(ROOT_OLD_CLOSE, ROOT_ADD, 1)
    print("   ✓ ILDIZGA qo'shildi")
    changes.append("ildizga 3 atribut qo'shildi")

    # ── 4) XML validatsiya ──────────────────────────────────────────
    print("\n[3/3] XML validatsiya")
    if not validate(text):
        print("   ✗ XML buzildi — BEKOR QILINDI")
        return 1
    print("   ✓ XML yaroqli")

    # ── 5) Yozish ───────────────────────────────────────────────────
    after_hash = hashlib.md5(text.encode("utf-8")).hexdigest()
    print(f"\n   yangi hash: {after_hash}")

    if args.check:
        print(f"\n✅ CHECK — {len(changes)} ta o'zgarish kerak:")
        for c in changes:
            print(f"   • {c}")
        print("\n   (--check rejimi: fayl YOZILMADI)")
        return 0

    bak = backup(LAYOUT)
    print(f"\n   backup: {bak.relative_to(ROOT)}")
    LAYOUT.write_text(text, encoding="utf-8")
    print(f"\n✅ TAYOR — {len(changes)} ta o'zgarish:")
    for c in changes:
        print(f"   • {c}")
    print("\n   Keyingi qadam:")
    print("     ./gradlew assembleDebug && adb install -r "
          "app/build/outputs/apk/debug/app-debug.apk")
    return 0


if __name__ == "__main__":
    sys.exit(main())
