#!/usr/bin/env python3
"""
★ FIX140-HOMERV: Home ekranini RecyclerView'ga o'tkazish.

═══════════════════════════════════════════════════════════════════════
  MUAMMO
═══════════════════════════════════════════════════════════════════════

Home ekrani `ScrollView` + `LinearLayout` ishlatadi. Karta ochilganda
200 ta `makeServerRow()` chaqiriladi — har biri XML `inflate()`:

    fillBodyIfNeeded(open:radikal_hy2): 1921ms, rows=200
    Choreographer: Skipped 135 frames!

`inflate()` — eng qimmat UI operatsiyasi. 200 marta = 2 sekundlik friz.

Servers bo'limi esa `RecyclerView` ishlatadi va FRIZ YO'Q, chunki
RecyclerView faqat KO'RINADIGAN 10-15 qatorni yaratadi va scroll
paytida ularni QAYTA ISHLATADI.

═══════════════════════════════════════════════════════════════════════
  YECHIM
═══════════════════════════════════════════════════════════════════════

Butun Home ekranini bitta `RecyclerView` ga o'tkazamiz:

    RecyclerView (home_recycler)
      ├─ Item 0 .. N   : Header (traffic, timer, tugmalar, AWG kartasi)
      └─ Item N+1 ...  : Server kartalari va qatorlari

ScrollView OLIB TASHLANADI — aks holda RecyclerView bilan scroll
konflikti bo'ladi (ikkalasi ham scroll qiladi, natijada View qayta
ishlatish ishlamaydi).

═══════════════════════════════════════════════════════════════════════
  XAVFSIZLIK
═══════════════════════════════════════════════════════════════════════

  • Barcha `android:id` lar SAQLANADI — `findViewById` kod o'zgarmaydi.
  • `home_scroll_root` id saqlanadi (endi RecyclerView'da) — kod
    unga murojaat qilsa ishlashda davom etadi.
  • Header — alohida `item_home_header.xml` (asl LinearLayout nusxasi,
    faqat `server_cards_container` olib tashlangan).
  • `server_cards_container` — endi kod orqali yaratilmaydi; uning
    o'rnini Adapter egallaydi.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, 'tools')
from _backup import safe_backup

ROOT = Path('.')
LAYOUT = ROOT / 'app/src/main/res/layout/fragment_home.xml'
HEADER = ROOT / 'app/src/main/res/layout/item_home_header.xml'

src = LAYOUT.read_text(encoding='utf-8')

# ─────────────────────────────────────────────────────────────────────
# 1. `LinearLayout` (13-qator) ichidagi hamma narsani ajratib olamiz.
#    `server_cards_container` bloki OLIB TASHLANADI.
# ─────────────────────────────────────────────────────────────────────
start = src.index('<LinearLayout\n        android:layout_width="match_parent"')
end = src.rindex('</LinearLayout>\n</ScrollView>')
inner = src[start:end]

# `server_cards_container` blokini olib tashlaymiz
cards_re = re.compile(
    r'\n *<!-- ══════ DINAMIK SERVER KARTALARI ══════ -->\n'
    r' *<LinearLayout\n'
    r' *android:id="@\+id/server_cards_container".*?/>\n',
    re.DOTALL)
inner_wo_cards, n = cards_re.subn('\n', inner)
assert n == 1, f"server_cards_container topilmadi (n={n})"

# ─────────────────────────────────────────────────────────────────────
# 2. Header layout faylini yozamiz.
# ─────────────────────────────────────────────────────────────────────
header = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!--\n'
    '  ★ FIX140-HOMERV: Home ekranining HEADER qismi.\n'
    '\n'
    '  Bu fayl `fragment_home.xml` dagi asl `LinearLayout` nusxasi.\n'
    '  Farqi: `server_cards_container` OLIB TASHLANGAN — uning\n'
    '  o\'rnini endi RecyclerView adapteri egallaydi.\n'
    '\n'
    '  Barcha `android:id` lar ASL holida saqlangan — HomeFragment\n'
    '  dagi `findViewById` chaqiruvlari o\'zgarmaydi.\n'
    '-->\n'
    '<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:app="http://schemas.android.com/apk/res-auto"\n'
    '    android:layout_width="match_parent"\n'
    '    android:layout_height="wrap_content"\n'
    '    android:orientation="vertical"\n'
    '    android:paddingStart="16dp"\n'
    '    android:paddingEnd="16dp"\n'
    '    android:paddingTop="12dp"\n'
    '    android:paddingBottom="8dp">\n'
    + inner_wo_cards +
    '\n</LinearLayout>\n'
)
HEADER.write_text(header, encoding='utf-8')
print(f"✅ {HEADER}: {len(header.splitlines())} qator")

# ─────────────────────────────────────────────────────────────────────
# 3. `fragment_home.xml` ni RecyclerView ga aylantiramiz.
# ─────────────────────────────────────────────────────────────────────
new_layout = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!--\n'
    '  ★ FIX140-HOMERV: Home ekrani endi RecyclerView.\n'
    '\n'
    '  ═══ NEGA ═══\n'
    '\n'
    '  Avval `ScrollView` + `LinearLayout` edi. Karta ochilganda 200 ta\n'
    '  `inflate()` bajarilardi (2 sekund, 135 kadr tashlanardi).\n'
    '\n'
    '  RecyclerView faqat KO\'RINADIGAN qatorlarni yaratadi va scroll\n'
    '  paytida ularni QAYTA ISHLATADI — Servers bo\'limidagi kabi.\n'
    '\n'
    '  ═══ TUZILISH ═══\n'
    '\n'
    '    Item 0   : Header (traffic, timer, SpeedWave, tugmalar, AWG)\n'
    '    Item 1..N: Server kartalari (sarlavha) va ochilgan qatorlar\n'
    '\n'
    '  `home_scroll_root` id SAQLANDI — kod unga murojaat qilsa\n'
    '  (masalan scroll pozitsiyasi) ishlashda davom etadi.\n'
    '-->\n'
    '<androidx.recyclerview.widget.RecyclerView\n'
    '    xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:id="@+id/home_scroll_root"\n'
    '    android:saveEnabled="false"\n'
    '    android:layout_width="match_parent"\n'
    '    android:layout_height="match_parent"\n'
    '    android:background="@color/bg"\n'
    '    android:scrollbars="none"\n'
    '    android:clipToPadding="false"/>\n'
)
safe_backup(LAYOUT, 'fix140homerv')
LAYOUT.write_text(new_layout, encoding='utf-8')
print(f"✅ {LAYOUT}: RecyclerView ga aylantirildi")
