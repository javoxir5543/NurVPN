#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FIX148-CLEANDEBUG — FIX147 diagnostikasi uchun qo'shilgan vaqtinchalik
loglarni olib tashlaydi.

════════════════════════════════════════════════════════════════════════
 NIMA O'CHIRILADI (faqat FIX147 diagnostikasi)
════════════════════════════════════════════════════════════════════════

  1) RowVH.bind dagi `NurVPN-VH` log (2 qatorli blok)
  2) RowVH listener ichidagi `NurVPN-CLICK` log
  3) RowVH dagi `NurVPN-GEOROW` post bloki (5 qator)
  4) CardVH listener ichidagi `NurVPN-CLICK` log
  5) CardVH dagi `NurVPN-TOUCH` + `NurVPN-GEO` post bloklari
  6) onBindViewHolder dagi `NurVPN-BIND` log (3 qator)

  ⚠️ Boshqa NurVPN-* loglar (TOGGLE, SELECT, PERF, SCROLL, SVC...)
     TEGILMAYDI — ular doimiy diagnostika.

════════════════════════════════════════════════════════════════════════
 ISHLATISH
════════════════════════════════════════════════════════════════════════

    python3 tools/fix148_clean_debug.py --check
    python3 tools/fix148_clean_debug.py

  Idempotent: 2-marta ishga tushirilsa hech narsa o'zgarmaydi.
  Backup: .fix-backups/HomeFragment.kt.fix148clean.<STAMP>.bak
"""

import argparse
import hashlib
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
BACKUP_DIR = ROOT / ".fix-backups"
STAMP = time.strftime("%Y%m%d_%H%M%S")

# ── O'chiriladigan bloklar (aniq matn) ──────────────────────────────
BLOCKS = [
    # 1) RowVH.bind dagi VH log
    (
        "RowVH: NurVPN-VH log",
        '''            android.util.Log.i("NurVPN-VH",
                "RowVH.bind: ${si.displayName()} clk=${itemView.isClickable} " +
                "lst=${itemView.hasOnClickListeners()}")
''',
        "",
    ),
    # 2) RowVH listener ichidagi CLICK log
    (
        "RowVH: NurVPN-CLICK log",
        '''                android.util.Log.i("NurVPN-CLICK",
                    "Home row: ${si.displayName()} selMode=$homeSelectMode")
''',
        "",
    ),
    # 3) RowVH dagi GEOROW post bloki
    (
        "RowVH: NurVPN-GEOROW post bloki",
        '''            itemView.post {
                val loc = IntArray(2)
                itemView.getLocationOnScreen(loc)
                android.util.Log.i("NurVPN-GEOROW",
                    "${si.displayName()}: screen=${loc[0]},${loc[1]} " +
                    "w=${itemView.width} h=${itemView.height} " +
                    "clk=${itemView.isClickable} lst=${itemView.hasOnClickListeners()}")
            }
''',
        "",
    ),
    # 4) CardVH listener ichidagi CLICK log
    (
        "CardVH: NurVPN-CLICK log",
        '''                android.util.Log.i("NurVPN-CLICK", "Card bosildi: ${c.subId}")
''',
        "",
    ),
    # 5) CardVH dagi TOUCH + GEO bloklari
    (
        "CardVH: NurVPN-TOUCH + GEO bloklari",
        '''            itemView.setOnTouchListener { _, ev ->
                android.util.Log.i("NurVPN-TOUCH",
                    "Card touch action=${ev.action} x=${ev.x} y=${ev.y}")
                false
            }
            itemView.post {
                val loc = IntArray(2)
                itemView.getLocationOnScreen(loc)
                android.util.Log.i("NurVPN-GEO",
                    "Card ${c.subId}: screen=${loc[0]},${loc[1]} " +
                    "w=${itemView.width} h=${itemView.height} " +
                    "clk=${itemView.isClickable} enabled=${itemView.isEnabled} " +
                    "parent=${itemView.parent?.javaClass?.simpleName}")
            }
''',
        "",
    ),
    # 6) onBindViewHolder dagi BIND log
    (
        "HomeAdapter: NurVPN-BIND log",
        '''            android.util.Log.i("NurVPN-BIND",
                "onBind pos=$pos idx=$idx type=${it::class.simpleName} " +
                "clk=${holder.itemView.isClickable} vis=${holder.itemView.visibility}")
''',
        "",
    ),
]


def md5(p: Path) -> str:
    return hashlib.md5(p.read_bytes()).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    print("═" * 68)
    print("  FIX148-CLEANDEBUG — vaqtinchalik diagnostika loglarini tozalash")
    print("═" * 68)

    if not TARGET.exists():
        print(f"✗ Fayl topilmadi: {TARGET}")
        return 1

    text = TARGET.read_text(encoding="utf-8")
    before = md5(TARGET)
    print(f"\n   oldingi hash: {before}")

    removed = []
    skipped = []
    for name, block, _ in BLOCKS:
        if block in text:
            text = text.replace(block, "", 1)
            removed.append(name)
            print(f"   ✓ olib tashlandi: {name}")
        else:
            skipped.append(name)
            print(f"   – allaqachon yo'q: {name}")

    if not removed:
        print("\n✓ Tozalash kerak emas — barcha loglar yo'q")
        print(f"   hash: {before}")
        return 0

    # Kod sintaksisini qo'pol tekshirish.
    # ⚠️ Kotlin'da `{`/`}` balansi NOLGA teng bo'lishi SHART EMAS:
    #    string shablonlari (`"${x}"`) va char literallari hisobga
    #    olinmaganda farq qoladi. Shuning uchun ASL farqni saqlab
    #    qolganini tekshiramiz (o'zgargan bo'lsa — xato).
    orig = TARGET.read_text(encoding="utf-8")
    d_orig = orig.count("{") - orig.count("}")
    d_new = text.count("{") - text.count("}")
    if d_orig != d_new:
        print(f"\n✗ Qavslar balansi O'ZGARDI: {d_orig} → {d_new}")
        print("   BEKOR QILINDI")
        return 1
    print(f"\n   ✓ Qavslar balansi saqlandi (farq={d_new})")

    # Kalit funksiyalar joyidami?
    for marker in ["fun bind(si: ServerItem)",
                   "itemView.setOnClickListener",
                   "private fun toggleHomeCard",
                   "override fun onBindViewHolder"]:
        if marker not in text:
            print(f"\n✗ MUHIM kod yo'qolgan: {marker}")
            print("   BEKOR QILINDI")
            return 1
    print("   ✓ Asosiy funksiyalar joyida")

    after = hashlib.md5(text.encode("utf-8")).hexdigest()
    print(f"\n   yangi hash: {after}")

    if args.check:
        print(f"\n✅ CHECK — {len(removed)} ta blok olib tashlanadi")
        print("   (--check rejimi: fayl YOZILMADI)")
        return 0

    BACKUP_DIR.mkdir(exist_ok=True)
    bak = BACKUP_DIR / f"{TARGET.name}.fix148clean.{STAMP}.bak"
    shutil.copy2(TARGET, bak)
    print(f"\n   backup: {bak.relative_to(ROOT)}")

    TARGET.write_text(text, encoding="utf-8")
    print(f"\n✅ TAYOR — {len(removed)} ta blok olib tashlandi")
    print("\n   Keyingi qadam:")
    print("     ./gradlew assembleDebug")
    return 0


if __name__ == "__main__":
    sys.exit(main())
