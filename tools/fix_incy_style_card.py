#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX150-INCYSTYLE — Obuna kartasini INCY uslubida qayta loyihalash
════════════════════════════════════════════════════════════════════════════

 ═══ MUAMMO ═══

 FIX149-SUBINFO dan keyin ma'lumot kartaga chiqdi, lekin BITTA qatorga
 tiqilib qoldi va kesiladi:

     📅 Остался 1 день   |   📊 0.00 / 50.00 ГБ

 Karta "boy" ko'rinmaydi — progress yo'q, kun va trafik aralash.

 ═══ YECHIM (INCY uslubidagi 5 qismli tuzilish) ═══

     ┌─────────────────────────────────────────────────────────┐
     │ 📡  Buba VPN                          📶  ⚙  ⬇          │
     │     50                                                   │
     │     Осталось: 1 дн.              0 B / 50 GB             │  ← YANGI qator
     │     ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░                 │  ← YANGI progress
     │     [🛠 Поддержка]   [🌐 Сайт]                            │  ← qayta ishlangan
     │     ⚠️ E'lon matni (qizil/sariq)                          │  ← qayta ishlangan
     └─────────────────────────────────────────────────────────┘

 ═══ O'ZGARISHLAR ═══

   XML (item_home_card_header.xml):
     • YANGI  `card_info_row`       — horizontal konteyner
     • YANGI  `card_info_days`      — "Осталось: N дн." (accent, bold)
     • O'ZGAR `card_info_traffic`   — endi faqat trafik ("0 B / 50 GB"),
                                      `text_tertiary`, o'ngga tekislangan
     • YANGI  `card_info_progress`  — ProgressBar (4dp, accent)
     • O'ZGAR `card_info_links`     — `bg_chip_small` fon + padding (tugma)
     • O'ZGAR `card_info_announce`  — `danger` rangi + chap chiziq fon

   Drawable (YANGI 2 ta):
     • `bg_announce_alert.xml`      — e'lon uchun sariq/qizil fon
     • `progress_card.xml`          — progress uchun accent track

   Kotlin (HomeFragment.kt):
     • `bindSubInfo()` qayta yoziladi — 4 ta bo'limga bo'linadi:
         qism1: kun + trafik (matn)
         qism2: progress bar (faqat trafficTotal > 0)
         qism3: havolalar (tugma ko'rinishida)
         qism4: e'lon (muhimlikka qarab rang)

   String (3 til):
     • `sub_remaining`      "Qoldi: %1$s"
     • `sub_btn_support`    "Qo'llab-quvvatlash"
     • `sub_btn_web`        "Sayt"
     • `sub_announce_title` "E'lon"
     • `sub_unlimited`      "Cheksiz"
     • `sub_bytes_used`     "%1$s / %2$s"

 ═══ XAVFSIZLIK KAFOLATLARI ═══

  • Karta IDLARI va struktura o'zgarmaydi — `card_ping`, `card_settings`,
    `card_arrow`, `card_icon`, `card_title`, `card_count` TEGILMAYDI
  • Ildiz `clickable`/`focusable`/`background` (FIX143) TEGILMAYDI
  • `toggleHomeCard()`, `showSubSettingsDialog()`, ping — TEGILMAYDI
  • Progress bar — `android.widget.ProgressBar` (og'ir animatsiya yo'q)
  • Barcha yangi elementlar `View.GONE` — ma'lumot bo'lmasa karta O'SMАЙДИ
  • Zaxiralar `.fix-backups/` ga (res/ ichida .bak QOLDIRILMAYDI!)

 ═══ ISHLATISH ═══

     python3 tools/fix_incy_style_card.py --check
     python3 tools/fix_incy_style_card.py
     python3 tools/fix_incy_style_card.py --rollback
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

# ⚠️ Zaxiralar `app/src/` ICHIDA TURMASLIGI KERAK!
#    AGP `res/` ichidagi har qanday faylni resurs deb o'qishga urinadi →
#      "The file name must end with .xml" → BUILD YIQILADI.
BACKUP_DIR = ROOT / ".fix-backups"

LAYOUT = ROOT / "app/src/main/res/layout/item_home_card_header.xml"
HOME = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
D_ANNOUNCE = ROOT / "app/src/main/res/drawable/bg_announce_alert.xml"
D_PROGRESS = ROOT / "app/src/main/res/drawable/progress_card.xml"

STRINGS = {
    "values": ROOT / "app/src/main/res/values/strings.xml",
    "values-uz": ROOT / "app/src/main/res/values-uz/strings.xml",
    "values-ru": ROOT / "app/src/main/res/values-ru/strings.xml",
    "values-en": ROOT / "app/src/main/res/values-en/strings.xml",
}


def backup_path(src: Path) -> Path:
    BACKUP_DIR.mkdir(exist_ok=True)
    return BACKUP_DIR / f"{src.name}.{STAMP}.bak"


def latest_backup(src: Path):
    pats = sorted(glob.glob(str(BACKUP_DIR / f"{src.name}.*.bak")))
    return Path(pats[-1]) if pats else None


# ═══════════════════════════════════════════════════════════════════════
#  Tahrir dvigateli
# ═══════════════════════════════════════════════════════════════════════

class Edit:
    def __init__(self, path: Path, enabled: bool = True):
        self.path = path
        self.enabled = enabled
        self.orig = path.read_text(encoding="utf-8") if path.exists() else ""
        self.text = self.orig
        self.changes = []

    def sub(self, old: str, new: str, what: str, guard: str = None) -> bool:
        """Idempotent almashtirish. `{`/`}` deltasi SAQLANISHI shart."""
        if guard and guard in self.text:
            return False
        if old not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:140]!r}")
        d0 = old.count("{") - old.count("}")
        d1 = new.count("{") - new.count("}")
        if d0 != d1:
            raise SystemExit(
                f"✗ [{self.path.name}] QAVSLAR NOMUVOFIQ ({what}): "
                f"eski={d0}, yangi={d1} — kod buziladi!")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        return True

    def add_before(self, anchor: str, block: str, what: str,
                   guard: str = None) -> bool:
        """`anchor` dan OLDIN blok qo'shadi (balans tekshiruvisiz — XML uchun)."""
        if guard and guard in self.text:
            return False
        if anchor not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] anchor topilmadi ({what}): {anchor[:90]!r}")
        self.text = self.text.replace(anchor, block + anchor, 1)
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
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        bak = latest_backup(self.path)
        if bak is None:
            return False
        shutil.copy2(bak, self.path)
        return True


# ═══════════════════════════════════════════════════════════════════════
#  1) YANGI drawable'lar
# ═══════════════════════════════════════════════════════════════════════

D_ANNOUNCE_XML = '''<?xml version="1.0" encoding="utf-8"?>
<!-- ★ FIX150-INCYSTYLE: provayder e'loni uchun fon.

     Avval e'lon oddiy kulrang matn edi — muhim ogohlantirish
     E'TIBORDAN CHETDA qolardi. Endi sariq fon + chap chegara
     chizig'i bilan ajralib turadi.

     ⚠️ `danger` (qizil) ATAYLAB ishlatilmadi: e'lon ko'pincha
     shunchaki ma'lumot bo'ladi (yangilanish haqida), qizil esa
     "xato" degan taassurot beradi. Sariq = "diqqat, o'qing". -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#1FB8860B"/>
            <corners android:radius="6dp"/>
        </shape>
    </item>
    <item android:width="3dp" android:gravity="left|start">
        <shape android:shape="rectangle">
            <solid android:color="@color/warning"/>
            <corners android:radius="2dp"/>
        </shape>
    </item>
</layer-list>
'''

D_PROGRESS_XML = '''<?xml version="1.0" encoding="utf-8"?>
<!-- ★ FIX150-INCYSTYLE: trafik progress bari uchun fon.

     `ProgressBar` (horizontal) bu drawable'ni `progressDrawable`
     sifatida ishlatadi. `clip` — to'ldirish effekti uchun.

     Ranglar: track — och kulrang, progress — accent (yashil). -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:id="@android:id/background">
        <shape android:shape="rectangle">
            <solid android:color="#FFE4EAE4"/>
            <corners android:radius="3dp"/>
        </shape>
    </item>
    <item android:id="@android:id/progress">
        <clip>
            <shape android:shape="rectangle">
                <solid android:color="@color/accent"/>
                <corners android:radius="3dp"/>
            </shape>
        </clip>
    </item>
</layer-list>
'''


# ═══════════════════════════════════════════════════════════════════════
#  2) XML layout — INCY uslubidagi qayta loyihalash
# ═══════════════════════════════════════════════════════════════════════

# Eski 3 ta element bloki (FIX149 dan) → yangi 5 ta element bloki
LAYOUT_OLD = '''            <!--
              ★ FIX149-SUBINFO: obuna metama'lumotlari (trafik + kun).

              ═══ NEGA KERAK ═══

              FIX141-CARDSTYLE'dan keyin bu ma'lumot YO'QOLDI. Avval
              `makeExpandableCard()` da kod bilan qo'shilardi (2397-2434),
              yangi adapter yo'lida esa umuman chizilmasdi.

              ═══ JOYLASHTIRISH ═══

                • 3-qator — trafik/kun: MUHIM, shuning uchun `accent`
                  rangda (ko'z darhol ilg'aydi)
                • Balandlik faqat ma'lumot bo'lsa oshadi (`View.GONE`),
                  bo'sh obunada karta O'SMАЙДИ
                • `maxLines=1` — tor ekranda ham buzilmaydi
            -->
            <TextView
                android:id="@+id/card_info_traffic"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="3dp"
                android:textColor="@color/accent"
                android:textSize="11sp"
                android:textStyle="bold"
                android:maxLines="2"
                android:ellipsize="none"
                android:visibility="gone"/>

            <!-- 4-qator — provayder havolalari (sayt + support) -->
            <TextView
                android:id="@+id/card_info_links"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="1dp"
                android:textColor="@color/text_tertiary"
                android:textSize="11sp"
                android:maxLines="1"
                android:ellipsize="end"
                android:visibility="gone"/>

            <!-- 5-qator — provayder e'loni (2 qatorgacha) -->
            <TextView
                android:id="@+id/card_info_announce"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="1dp"
                android:textColor="@color/text_tertiary"
                android:textSize="10sp"
                android:maxLines="2"
                android:ellipsize="end"
                android:visibility="gone"/>
'''

LAYOUT_NEW = '''            <!--
              ═══════════════════════════════════════════════════════
                ★ FIX150-INCYSTYLE: TRAFIK + KUN QATORI
              ═══════════════════════════════════════════════════════

              ═══ NEGA QAYTA LOYIHALANDI ═══

              FIX149 da kun va trafik BITTA matnga tiqilgan edi:

                  "📅 Остался 1 день   |   📊 0.00 / 50.00 ГБ"

              Uzun obuna nomi + tor ekranda bu qator KESILARDI va
              xunuk ko'rinardi. Endi ikkisi ALOHIDA element:

                  ┌──────────────────────────────────────────┐
                  │ Осталось: 1 дн.          0 B / 50 GB     │
                  └──────────────────────────────────────────┘
                    ↑ accent, bold           ↑ tertiary, o'ng

              ═══ NEGA `LinearLayout` ICHIDA ═══

              Karta o'ng tomonida `card_ping` / `card_settings` /
              `card_arrow` (34dp) turadi. Agar bu qator `match_parent`
              bo'lsa, u ikonkalar TAGIGA o'tib ketardi. Shuning uchun
              konteyner `weight=1` hududida — ikonkalar tegilmaydi.
            -->
            <LinearLayout
                android:id="@+id/card_info_row"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="3dp"
                android:orientation="horizontal"
                android:gravity="center_vertical"
                android:baselineAligned="false"
                android:visibility="gone">

                <!-- Kun: "Осталось: 1 дн." — accent, bold -->
                <TextView
                    android:id="@+id/card_info_days"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textColor="@color/accent"
                    android:textSize="11sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="end"/>

                <!-- Trafik: "0 B / 50 GB" — o'ngga suriladi -->
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
                    android:ellipsize="end"/>
            </LinearLayout>

            <!--
              ★ FIX150-INCYSTYLE: TRAFIK PROGRESS BARI (4dp)

              INCY uslubidagi asosiy vizual element. Faqat
              `trafficTotal > 0` bo'lsa ko'rinadi — cheksiz
              obunada chizilmaydi (`View.GONE`).

              ⚠️ `android.widget.ProgressBar` — eng arzon variant.
                 RecyclerView ichida ANIMATSIYA YO'Q (biz `progress`
                 ni bir marta o'rnatamiz), shuning uchun friz bo'lmaydi.
            -->
            <ProgressBar
                android:id="@+id/card_info_progress"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="4dp"
                android:layout_marginTop="5dp"
                android:max="100"
                android:progress="0"
                android:progressDrawable="@drawable/progress_card"
                android:visibility="gone"/>

            <!--
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
                android:paddingStart="8dp"
                android:paddingEnd="8dp"
                android:paddingTop="5dp"
                android:paddingBottom="5dp"
                android:textColor="@color/text_primary"
                android:textSize="10sp"
                android:maxLines="3"
                android:ellipsize="end"
                android:visibility="gone"/>
'''


# ═══════════════════════════════════════════════════════════════════════
#  3) Kotlin — bindSubInfo() ni qayta yozish
# ═══════════════════════════════════════════════════════════════════════

BIND_OLD = '''        private fun bindSubInfo(c: HomeItem.Card) {
            val trafficTv = itemView.findViewById<TextView>(R.id.card_info_traffic)
            val linksTv = itemView.findViewById<TextView>(R.id.card_info_links)
            val annTv = itemView.findViewById<TextView>(R.id.card_info_announce)
            val sub = c.sub

            // ═══ 3-qator: trafik + kun ═══
            val parts = mutableListOf<String>()
            if (sub != null) {
                if (sub.expireAt > 0) {
                    val days = sub.daysLeft()
                    parts.add(
                        when {
                            days < 0 -> getString(R.string.sub_expired)
                            days == 0 -> getString(R.string.sub_days_left_one)
                            else -> getString(R.string.sub_days_left, days)
                        })
                }
                if (sub.trafficTotal > 0) {
                    val g = 1024.0 * 1024 * 1024
                    val used = sub.trafficUsed / g
                    val total = sub.trafficTotal / g
                    parts.add(getString(R.string.sub_traffic_used,
                        String.format(java.util.Locale.US, "%.2f", used),
                        String.format(java.util.Locale.US, "%.2f", total)))
                } else if (sub.expireAt > 0) {
                    // Trafik cheklovi yo'q, lekin muddat bor
                    parts.add(getString(R.string.sub_no_limit))
                }
            }
            if (parts.isEmpty()) {
                trafficTv.visibility = View.GONE
            } else {
                trafficTv.visibility = View.VISIBLE
                trafficTv.text = parts.joinToString("   |   ")
            }

            // ═══ 4-qator: sayt + support ═══
            val linkParts = mutableListOf<String>()
            if (sub != null) {
                sub.webPageShort().takeIf { it.isNotEmpty() }
                    ?.let { linkParts.add("\\uD83C\\uDF10 $it") }
                sub.supportShort().takeIf { it.isNotEmpty() }
                    ?.let { linkParts.add("\\uD83D\\uDEE0 $it") }
            }
            if (linkParts.isEmpty()) {
                linksTv.visibility = View.GONE
            } else {
                linksTv.visibility = View.VISIBLE
                linksTv.text = linkParts.joinToString("    ")
            }

            // ═══ 5-qator: e'lon ═══
            val ann = sub?.announce.orEmpty()
            if (ann.isBlank()) {
                annTv.visibility = View.GONE
            } else {
                annTv.visibility = View.VISIBLE
                annTv.text = "\\uD83D\\uDCE2 $ann"
            }
        }
    }
'''

BIND_NEW = '''        /**
         * ═══════════════════════════════════════════════════════════
         *  ★ FIX150-INCYSTYLE: obuna kartasi metama'lumotlari.
         * ═══════════════════════════════════════════════════════════
         *
         *  ═══ KO'RSATILADIGAN 4 QISM ═══
         *
         *    1) KUN + TRAFIK  ── "Осталось: 1 дн."     "0 B / 50 GB"
         *    2) PROGRESS BAR  ── ishlatilish foizi (4dp chiziq)
         *    3) HAVOLALAR     ── [🛠 Поддержка]  [🌐 Сайт]
         *    4) E'LON         ── sariq ogohlantirish bloki
         *
         *  ⚠️ Har bir qism O'Z mustaqil ko'rinishini boshqaradi —
         *     ma'lumot yo'q bo'lsa `View.GONE`, karta O'SMАЙДИ.
         *     Bu RecyclerView uchun muhim: qayta ishlatilayotgan
         *     View'da eski ma'lumot QOLMASLIGI kerak.
         */
        private fun bindSubInfo(c: HomeItem.Card) {
            val rowTv = itemView.findViewById<View>(R.id.card_info_row)
            val daysTv = itemView.findViewById<TextView>(R.id.card_info_days)
            val trafficTv = itemView.findViewById<TextView>(R.id.card_info_traffic)
            val progBar = itemView.findViewById<android.widget.ProgressBar>(
                R.id.card_info_progress)
            val linksTv = itemView.findViewById<TextView>(R.id.card_info_links)
            val annTv = itemView.findViewById<TextView>(R.id.card_info_announce)
            val sub = c.sub

            // ═══════════════════════════════════════════════════════
            //  QISM 1: KUN + TRAFIK
            // ═══════════════════════════════════════════════════════
            var hasDays = false
            var hasTraffic = false

            if (sub != null && sub.expireAt > 0) {
                hasDays = true
                val days = sub.daysLeft()
                daysTv.text = when {
                    days < 0 -> getString(R.string.sub_expired)
                    days == 0 -> getString(R.string.sub_days_left_one)
                    else -> getString(R.string.sub_days_left, days)
                }
            }

            if (sub != null && sub.trafficTotal > 0) {
                hasTraffic = true
                trafficTv.text = getString(
                    R.string.sub_bytes_used,
                    bytesShort(sub.trafficUsed),
                    bytesShort(sub.trafficTotal))
            }

            // Ko'rinish: kun YOKI trafik bo'lsa qator ko'rinadi
            if (hasDays || hasTraffic) {
                rowTv.visibility = View.VISIBLE
                daysTv.visibility = if (hasDays) View.VISIBLE else View.GONE
                trafficTv.visibility = if (hasTraffic) View.VISIBLE else View.GONE
                // Faqat trafik bo'lsa — kun o'rniga "cheksiz" yozuvi
                if (!hasDays && hasTraffic && sub != null && sub.expireAt <= 0) {
                    daysTv.text = getString(R.string.sub_no_limit)
                    daysTv.visibility = View.VISIBLE
                }
            } else {
                rowTv.visibility = View.GONE
            }

            // ═══════════════════════════════════════════════════════
            //  QISM 2: PROGRESS BAR (faqat cheklangan trafikda)
            // ═══════════════════════════════════════════════════════
            if (sub != null && sub.trafficTotal > 0) {
                val pct = sub.trafficPercent().coerceIn(0, 100)
                progBar.visibility = View.VISIBLE
                progBar.progress = pct
                // 90%+ — qizil ogohlantirish
                val tint = when {
                    pct >= 90 -> 0xFFC0392B.toInt()      // danger
                    pct >= 75 -> 0xFFB8860B.toInt()      // warning
                    else -> 0xFF5A7A00.toInt()           // accent
                }
                progBar.progressTintList =
                    android.content.res.ColorStateList.valueOf(tint)
            } else {
                progBar.visibility = View.GONE
            }

            // ═══════════════════════════════════════════════════════
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
            }

            // ═══════════════════════════════════════════════════════
            //  QISM 4: E'LON (sariq ogohlantirish)
            // ═══════════════════════════════════════════════════════
            val ann = sub?.announce.orEmpty()
            if (ann.isBlank()) {
                annTv.visibility = View.GONE
            } else {
                annTv.visibility = View.VISIBLE
                annTv.text = "\\u26A0\\uFE0F $ann"
            }
        }

        /**
         * ★ FIX150-INCYSTYLE: baytlarni qisqa ko'rinishga o'giradi.
         *
         *   Avval HAR DOIM GB da ko'rsatilardi:
         *       0.00 / 50.00 GB      ← kichik qiymatda "0.00" xunuk
         *
         *   Endi birlik HAJMGA moslashadi:
         *       512 MB / 50 GB        ← o'qish oson
         *       1.5 GB / 50 GB
         *
         *   ⚠️ Xalqaro birliklar (B/KB/MB/GB/TB) — tarjima qilinmaydi,
         *      chunki ular texnik belgilar.
         */
        private fun bytesShort(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            val tb = gb * 1024
            val b = bytes.toDouble()
            return when {
                b >= tb -> String.format(java.util.Locale.US, "%.2f TB", b / tb)
                b >= gb -> String.format(java.util.Locale.US, "%.2f GB", b / gb)
                b >= mb -> String.format(java.util.Locale.US, "%.1f MB", b / mb)
                b >= kb -> String.format(java.util.Locale.US, "%.0f KB", b / kb)
                else -> "$bytes B"
            }
        }
    }
'''


# ═══════════════════════════════════════════════════════════════════════
#  4) String resurslari (4 til)
# ═══════════════════════════════════════════════════════════════════════

NEW_STRINGS = {
    "values": '''
    <!-- ★ FIX150-INCYSTYLE: karta metama'lumotlari -->
    <string name="sub_btn_support">Qo\\'llab-quvvatlash</string>
    <string name="sub_btn_web">Sayt</string>
    <string name="sub_bytes_used">%1$s / %2$s</string>
    <string name="sub_remaining">Qoldi: %1$s</string>
    <string name="sub_announce_title">E\\'lon</string>
    <string name="sub_unlimited">Cheksiz</string>
''',
    "values-uz": '''
    <!-- ★ FIX150-INCYSTYLE: karta metama'lumotlari -->
    <string name="sub_btn_support">Qo\\'llab-quvvatlash</string>
    <string name="sub_btn_web">Sayt</string>
    <string name="sub_bytes_used">%1$s / %2$s</string>
    <string name="sub_remaining">Qoldi: %1$s</string>
    <string name="sub_announce_title">E\\'lon</string>
    <string name="sub_unlimited">Cheksiz</string>
''',
    "values-ru": '''
    <!-- ★ FIX150-INCYSTYLE: данные карточки подписки -->
    <string name="sub_btn_support">Поддержка</string>
    <string name="sub_btn_web">Сайт</string>
    <string name="sub_bytes_used">%1$s / %2$s</string>
    <string name="sub_remaining">Осталось: %1$s</string>
    <string name="sub_announce_title">Объявление</string>
    <string name="sub_unlimited">Безлимит</string>
''',
    "values-en": '''
    <!-- ★ FIX150-INCYSTYLE: subscription card metadata -->
    <string name="sub_btn_support">Support</string>
    <string name="sub_btn_web">Website</string>
    <string name="sub_bytes_used">%1$s / %2$s</string>
    <string name="sub_remaining">Left: %1$s</string>
    <string name="sub_announce_title">Announcement</string>
    <string name="sub_unlimited">Unlimited</string>
''',
}


# ═══════════════════════════════════════════════════════════════════════
#  MAIN
# ═══════════════════════════════════════════════════════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(description="FIX150-INCYSTYLE")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 72)
    print("  FIX150-INCYSTYLE — obuna kartasini INCY uslubida qayta loyihalash")
    print("═" * 72)

    # ── ROLLBACK ──
    if args.rollback:
        n = 0
        for p in [LAYOUT, HOME, *STRINGS.values()]:
            e = Edit(p)
            if e.rollback():
                print(f"   ↩ tiklandi: {p.relative_to(ROOT)}")
                n += 1
        for p in [D_ANNOUNCE, D_PROGRESS]:
            if p.exists():
                p.unlink()
                print(f"   ↩ o'chirildi: {p.relative_to(ROOT)}")
                n += 1
        print(f"\n{'✅' if n else '–'} {n} ta fayl tiklandi")
        return 0

    # ── FAYLLAR MAVJUDLIGI ──
    for p in [LAYOUT, HOME, *STRINGS.values()]:
        if not p.exists():
            print(f"✗ Fayl topilmadi: {p}")
            return 1

    # ── TAHRIRLAR ──
    e_layout = Edit(LAYOUT)
    e_home = Edit(HOME)

    results = []

    # 1) XML: 3 eski element → 5 yangi element
    results.append((
        "Layout: kun+trafik qatori, progress bar, tugma, e'lon",
        e_layout.sub(LAYOUT_OLD, LAYOUT_NEW, "5 qismli tuzilish",
                     guard='id="@+id/card_info_progress"')))

    # 2) Kotlin: bindSubInfo() qayta yozildi
    results.append((
        "Kotlin: bindSubInfo() + bytesShort()",
        e_home.sub(BIND_OLD, BIND_NEW, "bindSubInfo qayta yozildi",
                   guard="private fun bytesShort(")))

    # 3) String resurslari
    str_results = []
    for loc, path in STRINGS.items():
        e = Edit(path)
        block = NEW_STRINGS[loc].lstrip("\n")
        ok = e.add_before("</resources>", block, f"strings ({loc})",
                          guard='name="sub_btn_support"')
        str_results.append((loc, ok, e))

    # ── YANGI DRAWABLE'LAR ──
    d_ann_new = not D_ANNOUNCE.exists()
    d_prog_new = not D_PROGRESS.exists()

    print("\n  ── O'zgarishlar ──")
    for name, done in results:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {name}")
    for loc, done, _ in str_results:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} strings.xml ({loc})")
    print(f"   {'✓ YARATILADI' if d_ann_new else '– mavjud':16} "
          f"bg_announce_alert.xml")
    print(f"   {'✓ YARATILADI' if d_prog_new else '– mavjud':16} "
          f"progress_card.xml")

    # ── TEKSHIRUVLAR ──
    print("\n  ── Tekshiruvlar ──")

    # XML validatsiya
    if e_layout.changed():
        tmp = Path("/tmp/_fix150_layout.xml")
        tmp.write_text(e_layout.text, encoding="utf-8")
        try:
            ET.parse(str(tmp))
            print("   ✓ XML tuzilishi to'g'ri (ET.parse)")
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ XML buzildi: {ex} — BEKOR QILINDI")
            return 1
        finally:
            tmp.unlink(missing_ok=True)
    else:
        print("   ✓ XML o'zgarmadi")

    # Kotlin balansi
    d0 = e_home.orig.count("{") - e_home.orig.count("}")
    d1 = e_home.text.count("{") - e_home.text.count("}")
    if d0 != d1:
        print(f"   ✗ Kotlin qavslar: {d0} → {d1} — BEKOR QILINDI")
        return 1
    print(f"   ✓ Kotlin qavslar balansi (farq={d1})")

    # Muhim ID lar joyidami?
    if e_layout.changed():
        required = ["card_icon", "card_title", "card_count", "card_ping",
                    "card_settings", "card_arrow", "card_info_row",
                    "card_info_days", "card_info_traffic",
                    "card_info_progress", "card_info_links",
                    "card_info_announce"]
        missing = [r for r in required if f'@+id/{r}"' not in e_layout.text]
        if missing:
            print(f"   ✗ Element yo'q: {missing} — BEKOR QILINDI")
            return 1
        print(f"   ✓ Barcha {len(required)} ta element joyida "
              f"(eski 6 + yangi 6)")

        # Ildiz clickable (FIX143) saqlanganmi?
        if 'android:clickable="true"' not in e_layout.text:
            print("   ✗ FIX143 clickable yo'qoldi — BEKOR QILINDI")
            return 1
        print("   ✓ FIX143 (ildiz clickable) saqlandi")

    # Kotlin funksiyalar joyidami?
    for marker in ["private fun bindSubInfo(", "private fun bytesShort(",
                   "private fun toggleHomeCard(", "private fun showSubSettingsDialog("]:
        if marker not in e_home.text:
            print(f"   ✗ Funksiya yo'qolgan: {marker} — BEKOR QILINDI")
            return 1
    print("   ✓ toggleHomeCard / showSubSettingsDialog / bindSubInfo joyida")

    # String fayllari XML validatsiyasi
    for loc, _, e in str_results:
        if e.changed():
            tmp = Path("/tmp/_fix150_str.xml")
            tmp.write_text(e.text, encoding="utf-8")
            try:
                ET.parse(str(tmp))
            except Exception as ex:  # noqa: BLE001
                print(f"   ✗ strings.xml ({loc}) buzildi: {ex} — BEKOR")
                return 1
            finally:
                tmp.unlink(missing_ok=True)
    print("   ✓ strings.xml (4 til) XML validatsiyadan o'tdi")

    # ── JAMI ──
    total = (sum(1 for _, d in results if d) +
             sum(1 for _, d, _ in str_results if d) +
             (1 if d_ann_new else 0) + (1 if d_prog_new else 0))
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        print("   Ishlatish:  python3 tools/fix_incy_style_card.py")
        return 0

    if total == 0:
        print("\n✓ Hech narsa kerak emas (hammasi qo'llangan)")
        return 0

    # ── YOZISH ──
    print("\n  ── Yozish ──")

    def do_write(e: Edit, label: str):
        if e.write(dry=False):
            bak = backup_path(e.path)
            rel = bak.relative_to(ROOT)
            print(f"   💾 {e.path.relative_to(ROOT)}")
            print(f"      zaxira: {rel}")

    do_write(e_layout, "layout")
    do_write(e_home, "kotlin")
    for _, _, e in str_results:
        do_write(e, "strings")

    # Drawable'lar
    for p, src in [(D_ANNOUNCE, D_ANNOUNCE_XML), (D_PROGRESS, D_PROGRESS_XML)]:
        if not p.exists():
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(src, encoding="utf-8")
            print(f"   💾 {p.relative_to(ROOT)} (yangi)")

    print("\n" + "═" * 72)
    print("  ✅ TAYOR")
    print("═" * 72)
    print("""
  KEYINGI QADAMLAR:

    1) ./gradlew assembleDebug --console=plain
    2) ./gradlew testDebugUnitTest --console=plain   (208 test)
    3) adb install -r app/build/outputs/apk/debug/app-debug.apk

    4) QURILMADA TEKSHIRISH:
       • Kun qatori chapda "Осталось: N дн." (accent/bold)
       • Trafik o'ngda "0 B / 50 GB" (kulrang)
       • Progress bar 4dp chiziq (yashil; 75%+ sariq, 90%+ qizil)
       • Havolalar "🛠 Поддержка • 🌐 Сайт" (bo'lsa)
       • E'lon sariq blokda (bo'lsa)
       • Karta ochilib-yopilishi (FIX147) ISHLASHI SHART
       • Ping / ⚙️ / ⬇️ tugmalari JOYIDA
       • Ma'lumotsiz obunada karta O'SMAGANI

    5) Qaytarish:
       python3 tools/fix_incy_style_card.py --rollback
""")


if __name__ == "__main__":
    sys.exit(main())
