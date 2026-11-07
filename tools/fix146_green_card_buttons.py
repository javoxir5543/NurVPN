#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ FIX146-GREENTEXT: karta tugmalari ⚙️ ⬇️ ⬆️ 📶 — YASHIL rang

═══════════════════════════════════════════════════════════════════
 MUAMMO
═══════════════════════════════════════════════════════════════════

Foydalanuvchi: "rangi yashil edi AWG CONFIG dagi kabi ⚙️⬇️⬆️ yashil bo'lsin"

`item_home_card_header.xml` dagi karta tugmalarida `android:textColor`
UMUMAN yo'q edi (yoki `text_tertiary` — kulrang):

    card_ping     (📶)  — textColor YO'Q      → default oq
    card_settings (⚙️)  — textColor YO'Q      → default oq
    card_arrow    (⬇/⬆) — @color/text_tertiary → KULRANG

═══════════════════════════════════════════════════════════════════
 ASL (AWG CONFIG) QANDAY
═══════════════════════════════════════════════════════════════════

`HomeFragment.kt` da AWG tugmalari KODDA yashil qilinadi:

    // ⚙️ AWG Config sozlamalari (programmatic)
    val settingsBtn = TextView(requireContext()).apply {
        text = "\\u2699"
        setTextColor(androidx.core.content.ContextCompat
            .getColor(requireContext(), R.color.accent))   ← YASHIL
    }

Va `values/colors.xml`:

    <color name="accent">#FF5A7A00</color>     ← yashil (lime-olive)

═══════════════════════════════════════════════════════════════════
 YECHIM
═══════════════════════════════════════════════════════════════════

Uch tugmaga ham `android:textColor="@color/accent"` qo'shamiz:
`card_ping`, `card_settings`, `card_arrow`.

  • `card_arrow` da mavjud `@color/text_tertiary` ALMASHTIRILADI.
  • `card_ping`/`card_settings` da YANGI atribut QO'SHILADI
    (`android:textSize` qatoridan oldin).
  • Boshqa hech narsaga tegilmaydi (idempotent).
  • Zaxira: tools/_backup.py → safe_backup()

═══════════════════════════════════════════════════════════════════
 ESLATMA
═══════════════════════════════════════════════════════════════════

`card_arrow` matni kodda ⬆/⬇ ga o'zgartiriladi (CardVH.bind):

    card_arrow.text = if (c.expanded) "\\u2B06" else "\\u2B07"

Rang XML'da berilgani uchun ikkala strelka ham yashil bo'ladi. ✓

═══════════════════════════════════════════════════════════════════
 ISHLATISH
═══════════════════════════════════════════════════════════════════

    python3 tools/fix146_green_card_buttons.py --check
    python3 tools/fix146_green_card_buttons.py
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _backup import safe_backup  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "app/src/main/res/layout/item_home_card_header.xml"
BACKUP_TAG = "fix146green"

# Qaysi id'larga yashil rang beriladi
GREEN_IDS = ["@+id/card_ping", "@+id/card_settings", "@+id/card_arrow"]
ACCENT = '@color/accent'

TEXT_COLOR_RE = re.compile(r'^(\s*)android:textColor="[^"]*"\s*$')
TEXT_SIZE_RE = re.compile(r'^(\s*)android:textSize="[^"]*"\s*$')


def find_block(lines, target_id):
    """`target_id` TextView blokini topadi → (start, end) yoki None."""
    start = None
    for i, l in enumerate(lines):
        if l and target_id in l:
            start = i
            break
    if start is None:
        return None
    for j in range(start, min(start + 25, len(lines))):
        s = lines[j]
        if s is None:
            continue
        if j > start and (s.strip().endswith('/>') or
                          s.strip() == '>' or
                          s.strip().startswith('</')):
            return start, j
    return start, min(start + 25, len(lines) - 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true')
    args = ap.parse_args()

    if not TARGET.exists():
        print(f"✗ Fayl topilmadi: {TARGET}")
        return 1

    lines = TARGET.read_text(encoding='utf-8').split('\n')
    changed = []

    for tid in GREEN_IDS:
        blk = find_block(lines, tid)
        if blk is None:
            print(f"✗ {tid} — blok topilmadi!")
            continue
        start, end = blk

        # Blok ichida mavjud textColor bormi?
        tc_i = None
        for k in range(start, end + 1):
            if lines[k] and TEXT_COLOR_RE.match(lines[k]):
                tc_i = k
                break

        if tc_i is not None:
            cur = lines[tc_i].strip()
            if f'"{ACCENT}"' in cur:
                print(f"✓ {tid}: allaqachon {ACCENT} (idempotent)")
                continue
            indent = TEXT_COLOR_RE.match(lines[tc_i]).group(1)
            lines[tc_i] = f'{indent}android:textColor="{ACCENT}"'
            print(f"  {tid}: {cur} → android:textColor=\"{ACCENT}\"")
            changed.append(f"{tid} rangi {ACCENT} ga almashtirildi")
        else:
            # `android:textSize` oldiga qo'shamiz
            ins = end
            for k in range(start, end + 1):
                if lines[k] and TEXT_SIZE_RE.match(lines[k]):
                    ins = k
                    break
            indent = ' ' * 12
            if lines[ins] and TEXT_SIZE_RE.match(lines[ins]):
                indent = TEXT_SIZE_RE.match(lines[ins]).group(1)
            lines.insert(ins, f'{indent}android:textColor="{ACCENT}"')
            print(f"  {tid}: android:textColor=\"{ACCENT}\" QO'SHILDI "
                  f"({ins + 1}-qator)")
            changed.append(f"{tid} ga {ACCENT} qo'shildi")

    out = '\n'.join(lines)

    if args.check:
        print("\n(--check rejimi: fayl YOZILMADI)")
        for c in changed:
            print(f"   • {c}")
        try:
            ET.fromstring(out)
            print("\n✅ Natijada XML TO'G'RI bo'ladi")
        except ET.ParseError as e:
            print(f"\n✗ Natijada XML xato: {e}")
            return 1
        print("Qo'llash: python3 tools/fix146_green_card_buttons.py")
        return 0

    if not changed:
        print("\n✅ O'zgarish yo'q — hammasi allaqachon yashil.")
    else:
        safe_backup(TARGET, BACKUP_TAG)
        TARGET.write_text(out, encoding='utf-8')
        print(f"\n✅ Yozildi: {TARGET.name}")
        for c in changed:
            print(f"   • {c}")

    # XML tekshiruvi
    print("\n── XML tekshiruvi ──")
    try:
        ET.parse(TARGET)
        print("✅ XML TO'G'RI")
    except ET.ParseError as e:
        print(f"✗ XML XATO: {e}")
        return 1

    # Natijani ko'rsatamiz
    print("\n── Karta tugmalari ──")
    final = TARGET.read_text(encoding='utf-8').split('\n')
    for tid in GREEN_IDS:
        blk = find_block(final, tid)
        if blk:
            s, e = blk
            for k in range(s, e + 1):
                if final[k] and 'textColor' in final[k]:
                    print(f"  {tid:22} {final[k].strip()}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
