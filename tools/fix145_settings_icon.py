#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ FIX145-SETTINGSICON: karta sozlamalar tugmasi 🔑 → ⚙️

═══════════════════════════════════════════════════════════════════
 MUAMMO
═══════════════════════════════════════════════════════════════════

`item_home_card_header.xml` dagi `card_settings` tugmasida
`android:text="🔑"` (kalit) ishlatilgan edi. Bu XATO:

  • ASL loyihada (`/media/pc/Windows/Games/NurVPN`) shu joyda
    `text = "\\u2699"` — ya'ni ⚙️ (sozlama) ishlatilgan:

        // ASL HomeFragment.kt:1512-1514
        // ⚙️ sozlama tugmasi (faqat subId bo'lsa)
        val settingsBtn = TextView(ctx).apply {
            text = "\\u2699"

  • AWG kartasida ham xuddi shu naqsh — ⚙️ (`awg_settings`).

  • Tugma `showHomeSubMenu(subId, title)` ni ochadi — bu OBUNA
    SOZLAMALARI menyusi (nomini o'zgartirish, yashirish, o'chirish,
    yangilash). Kalit (🔑) emas, SOZLAMA (⚙️) belgisi kerak.

  • Qo'shimcha: avvalgi `sed` urinishi faylni BUZGAN:

        android:text="android:text="🔑"#9881;"

    ya'ni ikkita `android:text=` va buzuq `#9881;`. XML parse
    bo'lmaydi. Skript buni ham tuzatadi.

═══════════════════════════════════════════════════════════════════
 YECHIM
═══════════════════════════════════════════════════════════════════

1) Buzilgan qatorni (agar bo'lsa) ANIQLAB, to'g'ri holatga qaytaradi.
2) `card_settings` tugmasidagi belgini ⚙️ (U+2699) ga o'rnatadi.
3) XML + emoji o'rniga XML entity `&#9881;` ishlatiladi — bu
   `sed`/shell muammolarini butunlay yo'q qiladi (ASCII-only).

═══════════════════════════════════════════════════════════════════
 USUL
═══════════════════════════════════════════════════════════════════

  • `card_settings` TextView bloki topiladi (id bo'yicha).
  • Uning ICHIDAGI `android:text="..."` qatori almashtiriladi.
  • Boshqa hech qanday qatorga tegilmaydi (idempotent).
  • Zaxira: tools/_backup.py → safe_backup(path, tag)
    (bir marta yaratiladi, qayta yozilmaydi).

═══════════════════════════════════════════════════════════════════
 ISHLATISH
═══════════════════════════════════════════════════════════════════

    python3 tools/fix145_settings_icon.py           # qo'llash
    python3 tools/fix145_settings_icon.py --check   # faqat tekshirish
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# ── tools/_backup.py ni import qilamiz ──────────────────────────────
sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "app/src/main/res/layout/item_home_card_header.xml"
BACKUP_TAG = "fix145settings"

# ⚙️ = U+2699 GEAR. XML entity sifatida — ASCII-only, sed muammosi yo'q.
GEAR_ENTITY = "&#9881;"

# Buzilgan qator naqshlari (avvalgi sed urinishidan qolgan).
#   android:text="android:text="🔑"#9881;"
#   android:text="android:text="🔑""
#   android:text="🔑#9881;"
#
# ⚠️ DIQQAT: `android:text="&#9881;"` (to'g'ri XML entity) bu
# naqshlarga TUSHMASLIGI kerak. Shuning uchun `#\d+;` ni faqat
# `&` BELGISIZ holatda qidiramiz.
BROKEN_PATTERNS = [
    # ichida IKKI marta `android:text=` (asosiy buzuqlik belgisi)
    re.compile(r'^\s*android:text="[^"]*android:text='),
    # buzuq entity: `#9881;` — lekin `&#9881;` EMAS
    re.compile(r'^\s*android:text="[^"&]*#\d+;?"\s*$'),
    # yopilmagan tirnoq: `android:text="..."...` ortiqcha
    re.compile(r'^\s*android:text="[^"]*"[^"]*"\s*$'),
]

# ⚙️ to'g'ri qiymati — bu naqshlarga tushmasligi SHART
_GEAR_OK = re.compile(r'^\s*android:text="&#9881;"\s*$')


def find_broken_line(lines):
    """Buzilgan `android:text=` qatorini topadi (index, matn) yoki (None, None)."""
    for i, line in enumerate(lines):
        if line is None:
            continue
        # ⚙️ to'g'ri qiymat — hech qachon "buzilgan" deb hisoblanmaydi
        if _GEAR_OK.match(line):
            continue
        s = line.strip()
        # oddiy holat: ichida IKKI marta android:text= bor
        if s.count('android:text=') > 1:
            return i, line
        for pat in BROKEN_PATTERNS:
            if pat.match(line):
                return i, line
    return None, None


def find_settings_block(lines):
    """
    `card_settings` TextView blokini topadi.
    Qaytaradi: (id_qatori_indexi, text_qatori_indexi, blok_oxiri_indexi)
    """
    start = None
    for i, line in enumerate(lines):
        if line is not None and '@+id/card_settings' in line:
            start = i
            break
    if start is None:
        return None

    text_i = None
    end = start
    for j in range(start, min(start + 30, len(lines))):
        s = lines[j]
        if s is None:
            continue
        if j > start and (s.strip().startswith('>') or s.strip().endswith('/>')):
            end = j
            break
        # `android:text=` — lekin `android:textSize`/`textColor` EMAS
        if re.match(r'^\s*android:text="', s):
            text_i = j
    return start, text_i, end


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true',
                    help="faqat tekshirish, faylni o'zgartirmaslik")
    args = ap.parse_args()

    if not TARGET.exists():
        print(f"✗ Fayl topilmadi: {TARGET}")
        return 1

    src = TARGET.read_text(encoding='utf-8')
    lines = src.split('\n')
    changed = []

    # ── 1) Buzilgan qatorni tuzatamiz ───────────────────────────────
    bi, bline = find_broken_line(lines)
    if bi is not None:
        print(f"⚠️  Buzilgan qator topildi ({bi + 1}):")
        print(f"      {bline.strip()}")
        # Uni darhol olib tashlaymiz — keyin to'g'ri qiymat yoziladi.
        del lines[bi]
        changed.append(f"buzilgan {bi + 1}-qator tozalandi")

    # ── 2) card_settings blokini topamiz ────────────────────────────
    block = find_settings_block(lines)
    if block is None:
        print("✗ `card_settings` bloki topilmadi!")
        return 1
    start, text_i, end = block
    print(f"✓ card_settings bloki: {start + 1}..{end + 1}-qator")

    indent = ' ' * 12
    new_text_line = f'{indent}android:text="{GEAR_ENTITY}"'

    if text_i is None:
        # `android:text` qatori yo'q — qo'shamiz (`textSize` oldiga)
        ins = start + 1
        while ins < end and 'android:textSize' not in lines[ins]:
            ins += 1
        lines.insert(ins, new_text_line)
        changed.append(f"{ins + 1}-qatorga ⚙️ qo'shildi")
    else:
        old = lines[text_i].strip()
        if _GEAR_OK.match(lines[text_i]):
            print("✓ Allaqachon ⚙️ — o'zgarish kerak emas (idempotent)")
        else:
            print(f"  {text_i + 1}-qator: {old}  →  android:text=\"{GEAR_ENTITY}\"")
            lines[text_i] = new_text_line
            changed.append(f"{text_i + 1}-qator ⚙️ ga almashtirildi")

    out = '\n'.join(lines)

    if args.check:
        # Tekshirish rejimi: fayl YOZILMAYDI, shuning uchun XML ham
        # o'zgarmaydi. Faqat NATIJANI xotirada tekshiramiz.
        print("\n(--check rejimi: fayl YOZILMADI)")
        for c in changed:
            print(f"   • {c}")
        print("\n── Natija (xotirada) ──")
        try:
            ET.fromstring(out)
            print("✅ Natijada XML TO'G'RI bo'ladi")
        except ET.ParseError as e:
            print(f"✗ Natijada ham XML xato: {e}")
            return 1
        final = out.split('\n')
        b = find_settings_block(final)
        if b and b[1] is not None:
            print(f"✅ card_settings:text → {final[b[1]].strip()}")
        print("\nQo'llash uchun: python3 tools/fix145_settings_icon.py")
        return 0

    if not changed:
        print("\n✅ O'zgarish yo'q — fayl allaqachon to'g'ri.")
    else:
        safe_backup(TARGET, BACKUP_TAG)
        TARGET.write_text(out, encoding='utf-8')
        print(f"\n✅ Yozildi: {TARGET.name}")
        for c in changed:
            print(f"   • {c}")

    # ── 3b) `res/` ichida qolib ketgan zaxiralarni tozalaymiz ───────
    #
    #  ⚠️ SABOQ: `sed -i.bak` yoki shunga o'xshash vositalar zaxirani
    #  `res/layout/` ICHIDA qoldiradi. AAPT bunday faylni resurs deb
    #  hisoblaydi va build yiqiladi:
    #
    #      ERROR: item_home_card_header.xml.bak.1793811374:
    #      The file name must end with .xml
    #
    #  Shuning uchun `res/` ostidagi `.bak*` fayllarni `.fix-backups/`
    #  ga ko'chirib, keyin o'chiramiz.
    res_dir = ROOT / "app/src/main/res"
    stray = [p for p in res_dir.rglob("*") if p.is_file()
             and ".bak" in p.name]
    if stray:
        dest_dir = ROOT / ".fix-backups"
        dest_dir.mkdir(exist_ok=True)
        print("\n⚠️  `res/` ichida zaxira fayllar topildi — ko'chiriladi:")
        for p in stray:
            tgt = dest_dir / f"{p.name}.rescued"
            if not tgt.exists():
                tgt.write_text(p.read_text(encoding='utf-8', errors='ignore'),
                               encoding='utf-8')
            p.unlink()
            print(f"   • {p.relative_to(ROOT)} → .fix-backups/{tgt.name}")

    # ── 4) XML validatsiyasi ────────────────────────────────────────
    print("\n── XML tekshiruvi ──")
    try:
        ET.parse(TARGET)
        print("✅ XML TO'G'RI (parse muvaffaqiyatli)")
    except ET.ParseError as e:
        print(f"✗ XML XATO: {e}")
        return 1

    # ── 5) card_settings qiymatini tasdiqlaymiz ─────────────────────
    final = TARGET.read_text(encoding='utf-8').split('\n')
    b = find_settings_block(final)
    if b and b[1] is not None:
        print(f"✅ card_settings:text = {final[b[1]].strip()}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
