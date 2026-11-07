#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX152-INCYPOLISH — Obuna kartasini INCY darajasiga yetkazish (final polish)
════════════════════════════════════════════════════════════════════════════

 ═══ 3 TA MUAMMO (rasmda ko'ringan) ═══

   1) E'LON cho'zilib ketgan (8-9 qator)
      FIX151 da `maxLines` butunlay olib tashlangan edi. Provayder
      uzun xabar yuborsa karta ekran balandligidan oshib ketardi.

   2) TRAFIK matni kesilgan:  "0 B / 50.0..."  ← "GB" ko'rinmaydi
      `card_info_traffic` — `layout_width="0dp"` + `layout_weight="1"`,
      `textSize="10sp"`. Uzun kun matni ("Остался 1 день") bilan
      birga sig'may, oxiri `ellipsize` bo'lardi.

   3) SUPPORT/SAYT — oddiy matn.
      `🛠 Поддержка   •   🌐 Сайт` — bitta TextView, tugma emas.

 ═══ YECHIM ═══

   1) `card_info_announce`:
        + android:maxLines="4"
        + android:ellipsize="end"
      Matn 4 qatordan oshsa oxirida "…" — batafsil ma'lumot
      Support/Sayt havolalarida.

   2) `card_info_traffic`:
        textSize 10sp → 9sp          (kichraytirildi)
        maxLines  1 → 2              (o'ta tor ekranda ham to'liq)
        ellipsize end → none         (KESILMAYDI)
      `sub_bytes_used` formati "%1$s / %2$s" — birlik `bytesShort()`
      dan keladi, ya'ni "0 B / 50.00 GB".

   3) HAVOLALAR — ikkita ALOHIDA TextView tugma:
        `card_info_btn_support`  [🛠 Поддержка]
        `card_info_btn_web`      [🌐 Сайт]
      Har biri `bg_chip.xml` (20dp radius, chip_bg + chip_stroke),
      matni `@color/accent`. Yangi `LinearLayout` konteyner ichida.

      ⚠️ `card_info_links` O'CHIRILMAYDI — `View.GONE` qilib
         qoldiriladi (boshqa kod unga murojaat qilsa buzilmasin).
         `bindSubInfo()` endi uni ishlatmaydi.

 ═══ XAVFSIZLIK ═══

   • Karta IDLARI va struktura o'zgarmaydi
   • `toggleHomeCard` / ping / ⚙️ / ⬇️ TEGILMAYDI
   • Yangi tugmalar `clickable="false"` — bosish karta ochilishiga
     o'tadi (`toggleHomeCard` buzilmaydi)
   • Barcha yangi element `View.GONE` — ma'lumot bo'lmasa karta
     O'SMАЙДИ
   • Zaxiralar `.fix-backups/` ga (`res/` ichida .bak YO'Q!)
   • Yangi string KERAK EMAS — mavjud `sub_btn_support` /
     `sub_btn_web` ishlatiladi

 ═══ ISHLATISH ═══

     python3 tools/fix_incy_polish.py --check
     python3 tools/fix_incy_polish.py
     python3 tools/fix_incy_polish.py --rollback
"""

import argparse
import glob
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")

# ⚠️ `res/` ICHIDA .bak QOLDIRILMAYDI (AGP "must end with .xml" xatosi)
BACKUP_DIR = ROOT / ".fix-backups"

LAYOUT = ROOT / "app/src/main/res/layout/item_home_card_header.xml"
HOME = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"


def backup_path(src: Path) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    return BACKUP_DIR / f"{src.name}.{STAMP}.bak"


def latest_backup(src: Path):
    pats = sorted(glob.glob(str(BACKUP_DIR / f"{src.name}.*.bak")))
    return Path(pats[-1]) if pats else None


class Edit:
    def __init__(self, path: Path):
        self.path = path
        self.orig = path.read_text(encoding="utf-8") if path.exists() else ""
        self.text = self.orig
        self.changes = []

    def sub(self, old: str, new: str, what: str, guard: str = None) -> bool:
        if guard and guard in self.text:
            return False
        if old not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:160]!r}")
        d0 = old.count("{") - old.count("}")
        d1 = new.count("{") - new.count("}")
        if d0 != d1:
            raise SystemExit(
                f"✗ [{self.path.name}] QAVSLAR NOMUVOFIQ ({what}): "
                f"eski={d0}, yangi={d1}")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        return True

    def changed(self) -> bool:
        return self.text != self.orig

    def write(self, dry: bool) -> bool:
        if not self.changed():
            return False
        if dry:
            return True
        if self.path.exists():
            shutil.copy2(self.path, backup_path(self.path))
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        bak = latest_backup(self.path)
        if not bak:
            return False
        shutil.copy2(bak, self.path)
        return True


# ═══════════════════════════════════════════════════════════════════════
#  1) TRAFIK: kesilmasin  (10sp → 9sp, maxLines 2, ellipsize none)
# ═══════════════════════════════════════════════════════════════════════

TRAFFIC_OLD = '''                <!-- Trafik: "0 B / 50 GB" — o'ngga suriladi -->
                <TextView
                    android:id="@+id/card_info_traffic"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:layout_marginStart="8dp"
                    android:gravity="end"
                    android:textColor="@color/text_tertiary"
                    android:textSize="10sp"
                    android:maxLines="1"
                    android:ellipsize="end"/>'''

TRAFFIC_NEW = '''                <!--
                  ★ FIX152-INCYPOLISH: TRAFIK — KESILMAYDI

                  ═══ MUAMMO ═══
                  Avval `textSize="10sp"` + `maxLines="1"` +
                  `ellipsize="end"` edi. Kun matni ("Остался 1 день")
                  uzun bo'lsa trafik "0 B / 50.0..." bo'lib
                  KESILARDI — "GB" ko'rinmasdi.

                  ═══ YECHIM ═══
                    • 10sp → 9sp   (bitta qatorga sig'adi)
                    • maxLines 1 → 2  (o'ta tor ekranda zaxira)
                    • ellipsize="end" → "none"  (KESILMAYDI)

                  Matn: "0 B / 50.00 GB" (`sub_bytes_used` +
                  `bytesShort()`), o'ngga tekislangan.
                -->
                <TextView
                    android:id="@+id/card_info_traffic"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:layout_marginStart="6dp"
                    android:gravity="end"
                    android:textColor="@color/text_tertiary"
                    android:textSize="9sp"
                    android:maxLines="2"
                    android:ellipsize="none"/>'''

# ═══════════════════════════════════════════════════════════════════════
#  2) HAVOLALAR + 3) E'LON maxLines
# ═══════════════════════════════════════════════════════════════════════

LINKS_ANN_OLD = '''            <!--
              ★ FIX150-INCYSTYLE: HAVOLALAR — tugma ko'rinishida

              Avval oddiy matn edi ("🌐 domen.com  🛠 support").
              Endi `bg_chip_small` fon + padding → bosiladigan
              tugma taassurotini beradi.

              ⚠️ Bu elementlar `clickable` EMAS (karta bosilishi
                 `toggleHomeCard` uchun saqlanadi). Havolalar
                 `showSubSettingsDialog` orqali ochiladi.
            -->
            <TextView
                android:id="@+id/card_info_links"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:textColor="@color/text_secondary"
                android:textSize="10sp"
                android:textStyle="bold"
                android:maxLines="1"
                android:ellipsize="end"
                android:visibility="gone"/>

            <!--
              ★ FIX150-INCYSTYLE: E'LON — sariq ogohlantirish bloki

              Avval oddiy kulrang matn edi va e'tibordan chetda
              qolardi. Endi `bg_announce_alert` foni (sariq + chap
              chiziq) bilan ajralib turadi.
            -->
            <TextView
                android:id="@+id/card_info_announce"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_announce_alert"
                android:paddingStart="10dp"
                android:paddingEnd="10dp"
                android:paddingTop="7dp"
                android:paddingBottom="7dp"
                android:textColor="@color/text_primary"
                android:textSize="12sp"
                android:lineSpacingExtra="2dp"
                android:visibility="gone"/>'''

LINKS_ANN_NEW = '''            <!--
              ═══════════════════════════════════════════════════════
                ★ FIX152-INCYPOLISH: HAVOLALAR — HAQIQIY TUGMALAR
              ═══════════════════════════════════════════════════════

              ═══ MUAMMO ═══

              Avval BITTA TextView edi:

                  "🛠 Поддержка   •   🌐 Сайт"

              Bu tugmaga o'xshamaydi — foydalanuvchi bosish mumkin
              deb o'ylamaydi. INCY'da ikkita alohida yumaloq tugma.

              ═══ YECHIM ═══

              Ikkita ALOHIDA TextView, har biri `bg_chip.xml` foni
              bilan (20dp radius, chip_bg + chip_stroke) va accent
              rangli matn:

                  ┌──────────────┐  ┌──────────┐
                  │ 🛠 Поддержка │  │ 🌐 Сайт  │
                  └──────────────┘  └──────────┘

              ═══ NEGA `LinearLayout` ICHIDA ═══

              Ikkalasini yonma-yon qo'yish uchun. `wrap_content` +
              `marginEnd` — tugmalar kerakli kenglikni oladi, ortiqcha
              joy egallamaydi. Ortiqcha tugma keyingi qatorga tushadi
              (RecyclerView'da balandlik o'zgaradi, lekin bu normal).

              ⚠️ `clickable="false"` — ATAYLAB. Karta bosilishi
                 `toggleHomeCard` uchun saqlanadi (FIX147/FIX143).
                 Havola ochish `showSubSettingsDialog` orqali.
            -->
            <LinearLayout
                android:id="@+id/card_info_btn_row"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:orientation="horizontal"
                android:visibility="gone">

                <!-- [🛠 Поддержка] -->
                <TextView
                    android:id="@+id/card_info_btn_support"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="10dp"
                    android:paddingEnd="10dp"
                    android:paddingTop="5dp"
                    android:paddingBottom="5dp"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="end"
                    android:clickable="false"
                    android:focusable="false"/>

                <!-- [🌐 Сайт] -->
                <TextView
                    android:id="@+id/card_info_btn_web"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="10dp"
                    android:paddingEnd="10dp"
                    android:paddingTop="5dp"
                    android:paddingBottom="5dp"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="end"
                    android:clickable="false"
                    android:focusable="false"/>
            </LinearLayout>

            <!--
              ★ FIX150-INCYSTYLE / FIX152: eski havola qatori.

              ⚠️ ENDI ISHLATILMAYDI — `bindSubInfo()` unga yozmaydi.
                 O'CHIRILMADI, chunki ID'ga boshqa kod murojaat
                 qilsa `findViewById` null qaytarib crash bo'lardi.
                 `View.GONE` — ko'rinmaydi, xotirani egallamaydi.
            -->
            <TextView
                android:id="@+id/card_info_links"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:textColor="@color/text_secondary"
                android:textSize="10sp"
                android:textStyle="bold"
                android:maxLines="1"
                android:ellipsize="end"
                android:visibility="gone"/>

            <!--
              ★ FIX152-INCYPOLISH: E'LON — 4 QATORGA CHEKLANDI

              ═══ MUAMMO ═══

              FIX151 da `maxLines` butunlay olib tashlangan edi.
              Natijada uzun provayder xabari 8-9 qatorga cho'zilib,
              karta ekrandan oshib ketardi va dizayn buzilardi.

              ═══ YECHIM ═══

                  maxLines="4" + ellipsize="end"

              4 qatordan oshsa oxirida "…" qo'yiladi. Foydalanuvchi
              batafsil ma'lumotni Support/Sayt tugmalari orqali
              oladi — INCY'da ham shunday.

              ⚠️ `textSize="12sp"` va `lineSpacingExtra="2dp"`
                 SAQLANDI (FIX151) — o'qish qulayligi uchun.
            -->
            <TextView
                android:id="@+id/card_info_announce"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:background="@drawable/bg_announce_alert"
                android:paddingStart="10dp"
                android:paddingEnd="10dp"
                android:paddingTop="7dp"
                android:paddingBottom="7dp"
                android:textColor="@color/text_primary"
                android:textSize="12sp"
                android:lineSpacingExtra="2dp"
                android:maxLines="4"
                android:ellipsize="end"
                android:visibility="gone"/>'''


# ═══════════════════════════════════════════════════════════════════════
#  KOTLIN — havolalarni ikkita tugmaga yozish
# ═══════════════════════════════════════════════════════════════════════

LINKS_KT_OLD = '''            // ═══════════════════════════════════════════════════════
            //  QISM 3: HAVOLALAR (tugma ko'rinishida)
            // ═══════════════════════════════════════════════════════
            val linkParts = mutableListOf<String>()
            if (sub != null) {
                sub.supportShort().takeIf { it.isNotEmpty() }?.let {
                    linkParts.add("\\uD83D\\uDEE0 " +
                        getString(R.string.sub_btn_support))
                }
                sub.webPageShort().takeIf { it.isNotEmpty() }?.let {
                    linkParts.add("\\uD83C\\uDF10 " +
                        getString(R.string.sub_btn_web))
                }
            }
            if (linkParts.isEmpty()) {
                linksTv.visibility = View.GONE
            } else {
                linksTv.visibility = View.VISIBLE
                // Ikkalasini alohida "chip" ko'rinishida ajratamiz
                linksTv.text = linkParts.joinToString("   \\u2022   ")
            }'''

LINKS_KT_NEW = '''            // ═══════════════════════════════════════════════════════
            //  QISM 3: HAVOLALAR (ikki alohida TUGMA)
            // ═══════════════════════════════════════════════════════
            //  ★ FIX152-INCYPOLISH: avval bitta TextView edi
            //    ("🛠 Поддержка • 🌐 Сайт") — tugmaga o'xshamasdi.
            //    Endi ikkita alohida TextView, har biri o'z foniga ega
            //    (`bg_chip.xml`, 20dp radius) va accent rangda.
            //    Bo'sh bo'lsa `View.GONE` — karta o'smaydi.
            val btnRow = itemView.findViewById<View>(R.id.card_info_btn_row)
            val supBtn = itemView.findViewById<TextView>(R.id.card_info_btn_support)
            val webBtn = itemView.findViewById<TextView>(R.id.card_info_btn_web)

            val hasSupport = sub != null && sub.supportShort().isNotEmpty()
            val hasWeb = sub != null && sub.webPageShort().isNotEmpty()

            if (hasSupport || hasWeb) {
                btnRow.visibility = View.VISIBLE
                supBtn.visibility = if (hasSupport) View.VISIBLE else View.GONE
                webBtn.visibility = if (hasWeb) View.VISIBLE else View.GONE
                supBtn.text = "\\uD83D\\uDEE0 " + getString(R.string.sub_btn_support)
                webBtn.text = "\\uD83C\\uDF10 " + getString(R.string.sub_btn_web)
            } else {
                btnRow.visibility = View.GONE
            }
            // Eski qator endi ishlatilmaydi (ID saqlanadi — crash bo'lmasin)
            linksTv.visibility = View.GONE'''

# `linksTv` endi faqat GONE qilinadi — ishlatilmasa ham qoldiramiz
LINKS_FIND_OLD = '''            val linksTv = itemView.findViewById<TextView>(R.id.card_info_links)
            val annTv = itemView.findViewById<TextView>(R.id.card_info_announce)'''

LINKS_FIND_NEW = '''            @Suppress("UNUSED_VARIABLE")
            val linksTv = itemView.findViewById<TextView>(R.id.card_info_links)
            val annTv = itemView.findViewById<TextView>(R.id.card_info_announce)'''


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX152-INCYPOLISH")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 72)
    print("  FIX152-INCYPOLISH — karta dizaynini INCY darajasiga yetkazish")
    print("═" * 72)

    if args.rollback:
        n = 0
        for p in [LAYOUT, HOME]:
            e = Edit(p)
            if e.rollback():
                print(f"   ↩ tiklandi: {p.relative_to(ROOT)}")
                n += 1
        print(f"\n{'✅' if n else '–'} {n} ta fayl tiklandi")
        return 0

    for p in [LAYOUT, HOME]:
        if not p.exists():
            print(f"✗ Fayl topilmadi: {p}")
            return 1

    e_l = Edit(LAYOUT)
    e_k = Edit(HOME)

    res = []
    res.append(("Trafik: 9sp + maxLines=2 + ellipsize=none",
                e_l.sub(TRAFFIC_OLD, TRAFFIC_NEW, "trafik kesilmasin",
                        guard='android:id="@+id/card_info_btn_support"')))
    res.append(("Havolalar: 2 ta tugma + e'lon maxLines=4",
                e_l.sub(LINKS_ANN_OLD, LINKS_ANN_NEW, "tugmalar + e'lon",
                        guard='android:id="@+id/card_info_btn_web"')))
    res.append(("Kotlin: tugmalarni to'ldirish",
                e_k.sub(LINKS_KT_OLD, LINKS_KT_NEW, "bindSubInfo havolalar",
                        guard="val btnRow = itemView.findViewById")))
    res.append(("Kotlin: linksTv unused ogohlantirishi",
                e_k.sub(LINKS_FIND_OLD, LINKS_FIND_NEW, "linksTv suppress",
                        guard="@Suppress(\"UNUSED_VARIABLE\")")))

    print("\n  ── O'zgarishlar ──")
    for name, done in res:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {name}")

    # ── Tekshiruvlar ──
    print("\n  ── Tekshiruvlar ──")

    if e_l.changed():
        tmp = Path("/tmp/_f152.xml")
        tmp.write_text(e_l.text, encoding="utf-8")
        try:
            ET.parse(str(tmp))
            print("   ✓ XML tuzilishi to'g'ri")
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ XML buzildi: {ex} — BEKOR")
            return 1
        finally:
            tmp.unlink(missing_ok=True)

        # Barcha kerakli ID lar
        need = ["card_icon", "card_title", "card_count", "card_ping",
                "card_settings", "card_arrow", "card_info_row",
                "card_info_days", "card_info_traffic",
                "card_info_progress", "card_info_links",
                "card_info_announce", "card_info_btn_row",
                "card_info_btn_support", "card_info_btn_web"]
        miss = [n for n in need if f'@+id/{n}"' not in e_l.text]
        if miss:
            print(f"   ✗ Element yo'q: {miss} — BEKOR")
            return 1
        print(f"   ✓ {len(need)} ta element joyida")

        # FIX143 (karta bosilishi) saqlanganmi?
        if 'android:clickable="true"' not in e_l.text:
            print("   ✗ FIX143 clickable yo'qoldi — BEKOR")
            return 1
        print("   ✓ FIX143 (ildiz clickable) saqlandi")

        # E'lon 4 qatorga cheklanganmi?
        ann = e_l.text.split('card_info_announce')[1][:700]
        if 'android:maxLines="4"' not in ann:
            print("   ✗ e'lon maxLines=4 emas — BEKOR")
            return 1
        if 'android:ellipsize="end"' not in ann:
            print("   ✗ e'lon ellipsize=end emas — BEKOR")
            return 1
        print("   ✓ e'lon 4 qatorga cheklandi (ellipsize=end)")

        # Trafik kesilmasin?
        tr = e_l.text.split('card_info_traffic')[1][:500]
        if 'android:ellipsize="none"' not in tr:
            print("   ✗ trafik ellipsize=none emas — BEKOR")
            return 1
        if 'android:textSize="9sp"' not in tr:
            print("   ✗ trafik textSize 9sp emas — BEKOR")
            return 1
        print("   ✓ trafik KESILMAYDI (9sp, ellipsize=none)")

        # Tugmalar clickable EMAS (karta bosilishi buzilmasin)
        # ⚠️ ID izohda ham uchraydi — shuning uchun `<TextView` blokini
        #    izlaymiz, oddiy `split` emas.
        for bid in ["card_info_btn_support", "card_info_btn_web"]:
            blk = e_l.text.split(f'<TextView\n                    android:id="@+id/{bid}"')
            if len(blk) < 2:
                print(f"   ✗ {bid} elementi topilmadi — BEKOR")
                return 1
            body = blk[1][:800]
            if 'android:clickable="false"' not in body:
                print(f"   ✗ {bid} clickable=false emas — BEKOR")
                return 1
        print("   ✓ tugmalar clickable=false (toggleHomeCard buzilmaydi)")

    if e_k.changed():
        d0 = e_k.orig.count("{") - e_k.orig.count("}")
        d1 = e_k.text.count("{") - e_k.text.count("}")
        if d0 != d1:
            print(f"   ✗ Kotlin qavslar: {d0} → {d1} — BEKOR")
            return 1
        print(f"   ✓ Kotlin qavslar balansi (farq={d1})")
        for m in ["toggleHomeCard(", "bindSubInfo(", "private fun bytesShort("]:
            if m not in e_k.text:
                print(f"   ✗ Yo'qolgan: {m} — BEKOR")
                return 1
        print("   ✓ toggleHomeCard / bindSubInfo / bytesShort joyida")

    total = sum(1 for _, d in res if d)
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    print("\n  ── Yozish ──")
    for e in [e_l, e_k]:
        if e.write(dry=False):
            print(f"   💾 {e.path.relative_to(ROOT)}")
            print(f"      zaxira: .fix-backups/{backup_path(e.path).name}")

    print("\n" + "═" * 72)
    print("  ✅ TAYOR")
    print("═" * 72)
    print("""
  YAKUNIY KO'RINISH:

    ┌──────────────────────────────────────────────────┐
    │ 📡  Buba VPN                        📶  ⚙  ⬇     │
    │     49                                           │
    │     Остался 1 день            0 B / 50.00 GB     │ ← to'liq
    │     ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░        │ ← progress
    │     [🛠 Поддержка]  [🌐 Сайт]                     │ ← tugmalar
    │     ┌ ⚠️ Выключите VPN и нажмите 🔄, если не...  │ ← 4 qator
    │     │    🔐 Продлевайте подписку заранее из-...  │
    │     │    ✅ Поддержка на самолетик справа ↗️       │
    │     └ …                                          │
    └──────────────────────────────────────────────────┘

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Qurilmada: trafik to'liq, e'lon 4 qator, tugmalar chiqadi
""")


if __name__ == "__main__":
    sys.exit(main())
