#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX149-SUBINFO — Obuna kartasidagi BARCHA metama'lumotlarni tiklash
════════════════════════════════════════════════════════════════════════════

 ═══ MUAMMO ═══

 FIX141-CARDSTYLE (RecyclerView'ga o'tish) dan keyin obuna kartasida
 faqat NOM va SERVER SONI ko'rinadi. Avvalgi versiyada esa:

     25 kun qoldi  |  📊 7.62 / 210.63 GB

 kabi ma'lumot bor edi.

 Sabab: `CardVH.bind()` faqat `R.id.card_count` ga son yozadi.
 Eski `makeExpandableCard()` dagi UserInfo kodi (2397-2434) yangi
 adapter yo'lida ISHLATILMAYDI.

 Bundan tashqari 4 ta HTTP header o'qiladi-yu, LEKIN SAQLANMAYDI:

     profile-update-interval  → headerUpdateInt   (yo'qoladi)
     profile-web-page-url     → headerWebPage     (yo'qoladi)
     support-url              → headerSupport     (yo'qoladi)
     announce                 → headerAnnounce    (yo'qoladi)

 ═══ YECHIM (6 qadam) ═══

  1) Subscription.kt          — 4 ta yangi maydon: updateIntervalDays,
                                webPage, supportUrl, announce
  2) SubscriptionStore.kt     — shu 4 maydonni saqlash/o'qish
  3) SubscribeHeader.kt (YANGI) — 4 ta header uchun yagona yordamchi
                                (tahlil + tozalash + qisqartirish)
  4) HomeFragment.kt          — header'larni Subscription'ga yozish
  5) item_home_card_header.xml — 3 ta yangi TextView
  6) HomeFragment.kt          — `CardVH.bind()` da ularni to'ldirish

 ═══ XAVFSIZLIK KAFOLATLARI ═══

  • HECH QANDAY mavjud o'lcham/padding/rang O'ZGARMAYDI
  • Hysteria2 / JSON import / Scroll / 200-limit kodiga TEGILMAYDI
  • `card_ping`, `card_settings`, `card_arrow`, `card_icon` — TEGILMAYDI
  • Yangi elementlar `View.GONE` — ma'lumot yo'q bo'lsa karta O'SMAYDI
  • `card_count` o'z joyida qoladi (Server bo'limi bilan mos)

 ═══ ISHLATISH ═══

     python3 tools/fix_sub_info_ui.py --check     # faqat tahlil
     python3 tools/fix_sub_info_ui.py             # qo'llash
     python3 tools/fix_sub_info_ui.py --rollback  # bekor qilish

 Idempotent: 2-marta ishga tushirilsa hech narsa o'zgarmaydi.
 Har bir fayl uchun: .bak.<timestamp> zaxira nusxasi.
"""

import argparse
import glob
import hashlib
import os
import re
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")

# ⚠️ JUDA MUHIM: zaxira nusxalari `app/src/` ICHIDA TURMASLIGI KERAK!
#
#   Android Gradle Plugin `app/src/main/res/` ichidagi HAR QANDAY faylni
#   resurs deb o'qishga urinadi. `.xml.bak.123` nomli fayl quyidagi
#   xatoga olib keladi va BUILD BUTUNLAY YIQILADI:
#
#       ERROR: item_home_card_header.xml.bak.123:
#       Resource and asset merger: The file name must end with .xml
#       > Execution failed for task ':app:mergeDebugResources'.
#
#   Shuning uchun barcha zaxiralar loyiha ildizidagi `.fix-backups/`
#   papkasiga yoziladi (u `.gitignore` da ham bor).
BACKUP_DIR = ROOT / ".fix-backups"


def backup_path(src: Path) -> Path:
    """`app/src/.../Foo.kt` → `.fix-backups/Foo.kt.<STAMP>.bak`."""
    BACKUP_DIR.mkdir(exist_ok=True)
    return BACKUP_DIR / f"{src.name}.{STAMP}.bak"


def latest_backup(src: Path) -> Path | None:
    """Shu fayl uchun eng oxirgi zaxirani topadi."""
    pats = sorted(glob.glob(str(BACKUP_DIR / f"{src.name}.*.bak")))
    return Path(pats[-1]) if pats else None


HOME = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
SUB = ROOT / "app/src/main/java/com/nurvpn/app/core/Subscription.kt"
STORE = ROOT / "app/src/main/java/com/nurvpn/app/storage/SubscriptionStore.kt"
HELPER = ROOT / "app/src/main/java/com/nurvpn/app/core/SubscribeHeader.kt"
LAYOUT = ROOT / "app/src/main/res/layout/item_home_card_header.xml"


# ═══════════════════════════════════════════════════════════════════════
#  Yordamchi: xavfsiz matn almashtirish
# ═══════════════════════════════════════════════════════════════════════

class Edit:
    """Bitta fayl ustida xavfsiz, idempotent tahrir."""

    def __init__(self, path: Path):
        self.path = path
        self.orig = path.read_text(encoding="utf-8")
        self.text = self.orig
        self.changes = []

    def sub(self, old: str, new: str, what: str, guard: str = None) -> bool:
        """
        `old` ni `new` bilan almashtiradi.

        guard — idempotentlik kaliti: agar allaqachon shu matn bo'lsa,
        qayta yozilmaydi. (Guard tekshiruvi BIRINCHI — `old` yangi
        matnning ichida qolishi mumkinligi uchun.)

        ⚠️ XAVFSIZLIK: `{`/`}` farqi O'ZGARMASLIGI shart. Bu — buzilgan
        kodni erta ushlashning eng arzon usuli (Kotlin'da string
        shablonlari tufayli mutlaq balans emas, DELTA muhim).
        """
        if guard and guard in self.text:
            return False
        if old not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] topilmadi ({what}):\n"
                f"    {old[:120]!r}")
        d0 = old.count("{") - old.count("}")
        d1 = new.count("{") - new.count("}")
        if d0 != d1:
            raise SystemExit(
                f"✗ [{self.path.name}] QAVSLAR NOMUVOFIQ ({what}):\n"
                f"    eski delta={d0}, yangi delta={d1}\n"
                f"    → bu kodni buzadi, blok qayta ko'rilishi kerak")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        return True

    def changed(self) -> bool:
        return self.text != self.orig

    def write(self, dry: bool):
        if not self.changed():
            return False
        if dry:
            return True
        bak = backup_path(self.path)
        shutil.copy2(self.path, bak)
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self):
        bak = latest_backup(self.path)
        if bak is None:
            return False
        shutil.copy2(bak, self.path)
        return True


# ═══════════════════════════════════════════════════════════════════════
#  1) Subscription.kt — 4 ta yangi maydon
# ═══════════════════════════════════════════════════════════════════════

SUB_OLD = """    @JvmField var serverCount: Int = 0
"""

SUB_NEW = """    @JvmField var serverCount: Int = 0

    // ═══ ★ FIX149-SUBINFO: obuna HTTP header'laridan keladigan metama'lumot
    //
    //   Bular HECH QAYERDA saqlanmasdi — shuning uchun karta qayta
    //   chizilganda (fragment qayta yaratilganda, tab almashganda)
    //   ma'lumot YO'QOLARDI. Endi diskda saqlanadi.

    /** `profile-update-interval` — yangilanish intervali (kun). 0 = noma'lum. */
    @JvmField var updateIntervalDays: Int = 0

    /** `profile-web-page-url` — provayder sayti. */
    @JvmField var webPage: String = ""

    /** `support-url` — qo'llab-quvvatlash havolasi. */
    @JvmField var supportUrl: String = ""

    /** `announce` — provayder e'loni. */
    @JvmField var announce: String = ""

    /** Karta uchun qulay ko'rinish: "domen.com" (sxemasiz). */
    fun webPageShort(): String = shortUrl(webPage)

    /** Karta uchun qulay ko'rinish: support domeni yoki "Support". */
    fun supportShort(): String = shortUrl(supportUrl)

    private fun shortUrl(u: String): String {
        if (u.isBlank()) return ""
        var s = u.trim()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.removePrefix("t.me/").removePrefix("telegram.me/")
        s = s.trimEnd('/')
        if (s.length > 26) s = s.take(25) + "…"
        return s
    }
"""


# ═══════════════════════════════════════════════════════════════════════
#  2) SubscriptionStore.kt — saqlash + o'qish
# ═══════════════════════════════════════════════════════════════════════

STORE_SAVE_OLD = """            o.put("serverCount", sub.serverCount)
"""

STORE_SAVE_NEW = """            o.put("serverCount", sub.serverCount)
            // ★ FIX149-SUBINFO: header metama'lumotlari
            if (sub.updateIntervalDays > 0) {
                o.put("updateIntervalDays", sub.updateIntervalDays)
            }
            if (sub.webPage.isNotEmpty()) o.put("webPage", sub.webPage)
            if (sub.supportUrl.isNotEmpty()) o.put("supportUrl", sub.supportUrl)
            if (sub.announce.isNotEmpty()) o.put("announce", sub.announce)
"""

STORE_LOAD_OLD = """                sub.serverCount = o.optInt("serverCount", 0)
"""

STORE_LOAD_NEW = """                sub.serverCount = o.optInt("serverCount", 0)
                // ★ FIX149-SUBINFO: header metama'lumotlari
                sub.updateIntervalDays = o.optInt("updateIntervalDays", 0)
                sub.webPage = o.optString("webPage", "")
                sub.supportUrl = o.optString("supportUrl", "")
                sub.announce = o.optString("announce", "")
"""


# ═══════════════════════════════════════════════════════════════════════
#  3) SubscribeHeader.kt — YANGI fayl (yagona yordamchi)
# ═══════════════════════════════════════════════════════════════════════

HELPER_SRC = r'''package com.nurvpn.app.core

import android.util.Base64
import android.util.Log

/**
 * ★ FIX149-SUBINFO: obuna HTTP header'larini tahlil qilish uchun
 * YAGONA yordamchi.
 *
 * ═══ NEGA KERAK ═══
 *
 * HomeFragment'da header'lar o'qilardi, lekin 4 tasi HECH QAYERDA
 * saqlanmasdi va karta qayta chizilganda yo'qolardi. Bundan tashqari
 * tahlil mantiqi kod ichida sochilib ketgan edi.
 *
 * Bu yerda:
 *   • `profile-title` decode (base64: prefiksi bilan yoki oddiy)
 *   • `announce` decode (ko'p qatorli / escaped)
 *   • URL tozalash (sxema, ortiqcha bo'shliq)
 *   • intervalni oqilona chegaralash (0..90 kun)
 *
 * ⚠️ Bu fayl FAQAT o'qiydi — hech qanday yon ta'sir yo'q.
 */
object SubscribeHeader {

    private const val TAG = "NurVPN-HDR"

    /** Chegaralar — provayder bema'ni qiymat yuborsa UI buzilmasin. */
    private const val MIN_INTERVAL_DAYS = 1
    private const val MAX_INTERVAL_DAYS = 90

    /** `announce` uchun maksimal uzunlik (kartada 2 qatorga sig'adi). */
    const val MAX_ANNOUNCE = 220

    /**
     * `profile-title` / `announce` — provayder base64 bilan yuborishi mumkin.
     * `base64:XXXX` shaklida bo'lsa ochiladi, aks holda matn qaytariladi.
     */
    fun decode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (!s.startsWith("base64:", ignoreCase = true)) return s
        return try {
            val bytes = Base64.decode(s.substring(7).trim(), Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).trim().ifEmpty { null }
        } catch (t: Throwable) {
            Log.w(TAG, "base64 decode xato: ${t.message}")
            null
        }
    }

    /**
     * `profile-update-interval` — kun. 0 yoki bema'ni qiymat → 0 (noma'lum).
     *
     * ⚠️ Ba'zi provayderlar SEKUNDDA yuboradi (86400). Agar qiymat
     *    chegaradan katta bo'lsa — sekund deb hisoblab kunlarga o'giramiz.
     */
    fun parseIntervalDays(raw: String?): Int {
        val v = raw?.trim()?.toIntOrNull() ?: return 0
        if (v <= 0) return 0
        // 90 kundan katta → ehtimol sekund (86400 = 1 kun)
        val days = if (v > MAX_INTERVAL_DAYS) v / 86400 else v
        return days.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
    }

    /** URL: bo'shliq/sxema tozalanadi. Noto'g'ri bo'lsa `null`. */
    fun cleanUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        if (s.isEmpty()) return null
        // Faqat http(s) yoki telegram havolalari qabul qilinadi
        val ok = s.startsWith("http://", true) ||
            s.startsWith("https://", true) ||
            s.startsWith("tg://", true) ||
            s.startsWith("t.me/", true)
        if (!ok) return null
        return s
    }

    /**
     * `announce` — ko'p qatorli e'lon. Qatorlar birlashtiriladi va
     * `MAX_ANNOUNCE` gacha qisqartiriladi (kartada 2 qator).
     */
    fun cleanAnnounce(raw: String?): String? {
        val s = decode(raw) ?: return null
        // Escaped \n va haqiqiy qatorlarni bo'shliqqa aylantiramiz
        var t = s.replace("\n", " ").replace("\r", " ")
            .lineSequence().joinToString(" ") { it.trim() }
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        if (t.isEmpty()) return null
        if (t.length > MAX_ANNOUNCE) t = t.take(MAX_ANNOUNCE - 1).trimEnd() + "…"
        return t
    }
}
'''


def ensure_helper(dry: bool) -> bool:
    """
    `SubscribeHeader.kt` ni yaratadi (mavjud bo'lsa tegilmaydi).

    ⚠️ `dry=True` (--check) da fayl YOZILMAYDI — faqat "yaratilishi
    kerak" deb xabar qaytariladi. Avvalgi versiyada bu yerda fayl
    baribir yozilardi — `--check` kafolati buzilardi.
    """
    if HELPER.exists():
        return False
    if dry:
        return True          # faqat xabar — YOZILMAYDI
    HELPER.parent.mkdir(parents=True, exist_ok=True)
    HELPER.write_text(HELPER_SRC, encoding="utf-8")
    return True


# ═══════════════════════════════════════════════════════════════════════
#  4) HomeFragment.kt — header'larni Subscription'ga yozish
# ═══════════════════════════════════════════════════════════════════════

# 4a) Ikkala userinfo saqlash joyiga header yozish (2 ta joy bor!)
SETHDR_OLD = """                localUserInfo?.let { info ->
                    if (info.trafficTotal > 0) {
                        currentSub.trafficUsed = info.trafficUsed
                        currentSub.trafficTotal = info.trafficTotal
                    }
                    if (info.expireAt > 0) {
                        currentSub.expireAt = info.expireAt
                    }
                }
"""

SETHDR_NEW = """                localUserInfo?.let { info ->
                    if (info.trafficTotal > 0) {
                        currentSub.trafficUsed = info.trafficUsed
                        currentSub.trafficTotal = info.trafficTotal
                    }
                    if (info.expireAt > 0) {
                        currentSub.expireAt = info.expireAt
                    }
                }
                // ★ FIX149-SUBINFO: header metama'lumotlarini SAQLAYMIZ.
                //   Avval ular o'qilardi-yu, yo'qolardi — karta qayta
                //   chizilganda "25 kun qoldi | 7.62 / 210.63 GB"
                //   yozuvi ko'rinmasdi.
                applySubHeaders(currentSub, headerUpdateInt, headerWebPage,
                    headerSupport, headerAnnounce)
"""

# 4b) JSON filiali (subUpd) uchun — faqat bir marta mos keladi
SETHDR_JSON_OLD = """                                if (info.expireAt > 0) {
                                    subUpd.expireAt = info.expireAt
                                }
                                android.util.Log.i("NurVPN-DBG",
                                    "JSON userinfo saqlandi: total=${info.trafficTotal}, " +
                                    "expire=${info.expireAt}, used=${info.trafficUsed}")
"""

SETHDR_JSON_NEW = """                                if (info.expireAt > 0) {
                                    subUpd.expireAt = info.expireAt
                                }
                                android.util.Log.i("NurVPN-DBG",
                                    "JSON userinfo saqlandi: total=${info.trafficTotal}, " +
                                    "expire=${info.expireAt}, used=${info.trafficUsed}")
                                // ★ FIX149-SUBINFO: header metama'lumotlari
                                applySubHeaders(subUpd, headerUpdateInt, headerWebPage,
                                    headerSupport, headerAnnounce)
"""

# 4c) Yordamchi funksiya — applySubHeaders
APPLY_ANCHOR = """    private fun decodeSubHeader(raw: String?): String? {"""

APPLY_NEW = """    /**
     * ★ FIX149-SUBINFO: obuna header metama'lumotlarini `Subscription` ga
     * yozadi. Faqat MAVJUD va TO'G'RI qiymatlar saqlanadi — eski qiymat
     * noto'g'ri ma'lumot bilan O'CHIRILMAYDI.
     */
    private fun applySubHeaders(
        sub: com.nurvpn.app.core.Subscription,
        updateInt: Int?,
        webPage: String?,
        support: String?,
        announce: String?
    ) {
        try {
            com.nurvpn.app.core.SubscribeHeader.parseIntervalDays(updateInt?.toString())
                .takeIf { it > 0 }?.let { sub.updateIntervalDays = it }
            com.nurvpn.app.core.SubscribeHeader.cleanUrl(webPage)
                ?.let { sub.webPage = it }
            com.nurvpn.app.core.SubscribeHeader.cleanUrl(support)
                ?.let { sub.supportUrl = it }
            com.nurvpn.app.core.SubscribeHeader.cleanAnnounce(announce)
                ?.let { sub.announce = it }
            android.util.Log.i("NurVPN-DBG",
                "sub headers: interval=${sub.updateIntervalDays}k " +
                "web=${sub.webPage.take(40)} support=${sub.supportUrl.take(40)} " +
                "announce=${sub.announce.length} belgi")
        } catch (t: Throwable) {
            android.util.Log.w("NurVPN-DBG", "applySubHeaders xato", t)
        }
    }

    private fun decodeSubHeader(raw: String?): String? {"""


# ═══════════════════════════════════════════════════════════════════════
#  5) item_home_card_header.xml — 3 ta yangi TextView
# ═══════════════════════════════════════════════════════════════════════

LAYOUT_OLD = """            <TextView
                android:id="@+id/card_count"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textColor="@color/text_tertiary"
                android:textSize="11sp"/>
        </LinearLayout>
"""

LAYOUT_NEW = """            <TextView
                android:id="@+id/card_count"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:textColor="@color/text_tertiary"
                android:textSize="11sp"/>

            <!--
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
                android:maxLines="1"
                android:ellipsize="end"
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
        </LinearLayout>
"""


# ═══════════════════════════════════════════════════════════════════════
#  6) CardVH.bind() — elementlarni to'ldirish
# ═══════════════════════════════════════════════════════════════════════

BIND_OLD = """            itemView.findViewById<TextView>(R.id.card_count).text =
                c.count.toString()
            itemView.findViewById<TextView>(R.id.card_arrow).text =
                if (c.expanded) "\\u2B06" else "\\u2B07"
"""

BIND_NEW = """            itemView.findViewById<TextView>(R.id.card_count).text =
                c.count.toString()
            itemView.findViewById<TextView>(R.id.card_arrow).text =
                if (c.expanded) "\\u2B06" else "\\u2B07"

            bindSubInfo(c)
"""

BIND_FN_ANCHOR = """            itemView.setOnClickListener {
                toggleHomeCard(c.subId)
            }
            itemView.setOnLongClickListener {
                if (c.subId != null && c.subId != "fav_card") {
                    showHomeSubMenu(c.subId, c.title); true
                } else false
            }
        }
    }
"""

BIND_FN_NEW = """            itemView.setOnClickListener {
                toggleHomeCard(c.subId)
            }
            itemView.setOnLongClickListener {
                if (c.subId != null && c.subId != "fav_card") {
                    showHomeSubMenu(c.subId, c.title); true
                } else false
            }
        }

        /**
         * ★ FIX149-SUBINFO: karta sarlavhasidagi metama'lumot qatorlarini
         * to'ldiradi. Ma'lumot yo'q bo'lsa element `GONE` — karta O'SMАЙДИ.
         *
         *  ═══ KO'RSATILADIGAN MA'LUMOT ═══
         *
         *   3-qator : "📅 25 kun qoldi  |  📊 7.62 / 210.63 GB"
         *             (muddati tugagan bo'lsa — "📅 Muddati tugagan")
         *   4-qator : "🌐 domen.com"  +  "🛠 support"
         *   5-qator : "📢 e'lon matni"
         */
        private fun bindSubInfo(c: HomeItem.Card) {
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
"""


# ═══════════════════════════════════════════════════════════════════════
#  Tekshiruvlar
# ═══════════════════════════════════════════════════════════════════════

def check_xml(path: Path) -> bool:
    try:
        ET.parse(str(path))
        return True
    except Exception as e:  # noqa: BLE001
        print(f"   ✗ XML xato: {e}")
        return False


def check_braces(edits) -> bool:
    """
    Kotlin'da `{`/`}` farqi saqlanishini tekshiradi.
    (String shablonlari tufayli farq nolga teng bo'lishi shart emas —
    shuning uchun ASL farq bilan solishtiramiz.)
    """
    ok = True
    for e in edits:
        d0 = e.orig.count("{") - e.orig.count("}")
        d1 = e.text.count("{") - e.text.count("}")
        if d0 != d1:
            print(f"   ✗ [{e.path.name}] qavslar balansi: {d0} → {d1}")
            ok = False
    return ok


# ═══════════════════════════════════════════════════════════════════════
#  MAIN
# ═══════════════════════════════════════════════════════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(description="FIX149-SUBINFO")
    ap.add_argument("--check", action="store_true", help="faqat tahlil")
    ap.add_argument("--rollback", action="store_true", help="zaxiradan tiklash")
    args = ap.parse_args()

    print("═" * 70)
    print("  FIX149-SUBINFO — obuna kartasi metama'lumotlarini tiklash")
    print("═" * 70)

    # ── ROLLBACK ──
    if args.rollback:
        n = 0
        for p in [HOME, SUB, STORE, LAYOUT]:
            e = Edit(p)
            if e.rollback():
                print(f"   ↩ tiklandi: {p.relative_to(ROOT)}")
                n += 1
        if HELPER.exists():
            HELPER.unlink()
            print(f"   ↩ o'chirildi: {HELPER.relative_to(ROOT)}")
            n += 1
        print(f"\n{'✅' if n else '–'} {n} ta fayl tiklandi")
        return 0

    for p in [HOME, SUB, STORE, LAYOUT]:
        if not p.exists():
            print(f"✗ Fayl topilmadi: {p}")
            return 1

    # ── TAHRIRLAR ──
    e_sub = Edit(SUB)
    e_store = Edit(STORE)
    e_home = Edit(HOME)
    e_layout = Edit(LAYOUT)

    results = []

    # 1) Subscription.kt
    results.append(("Subscription.kt: 4 maydon + shortUrl()",
                    e_sub.sub(SUB_OLD, SUB_NEW, "yangi maydonlar",
                              guard="updateIntervalDays")))

    # 2) SubscriptionStore.kt — saqlash
    results.append(("SubscriptionStore: saqlash",
                    e_store.sub(STORE_SAVE_OLD, STORE_SAVE_NEW, "save",
                                guard='"webPage"')))

    # 3) SubscriptionStore.kt — o'qish
    results.append(("SubscriptionStore: o'qish",
                    e_store.sub(STORE_LOAD_OLD, STORE_LOAD_NEW, "load",
                                guard='optString("webPage"')))

    # 4) HomeFragment — header yozish (JSON bo'lmagan filial)
    results.append(("HomeFragment: header saqlash (asosiy)",
                    e_home.sub(SETHDR_OLD, SETHDR_NEW, "applySubHeaders asosiy",
                               guard="applySubHeaders(currentSub")))

    # 4b) HomeFragment — header yozish (JSON filiali)
    if "applySubHeaders(subUpd" not in e_home.text:
        if SETHDR_JSON_OLD in e_home.text:
            results.append(("HomeFragment: header saqlash (JSON filiali)",
                            e_home.sub(SETHDR_JSON_OLD, SETHDR_JSON_NEW,
                                       "applySubHeaders JSON",
                                       guard="applySubHeaders(subUpd")))
        else:
            # JSON filiali boshqacha yozilgan bo'lishi mumkin — ogohlantiramiz
            print("   ⚠ JSON filiali topilmadi — faqat asosiy filial "
                  "yangilanadi (tanqidiy emas)")

    # 5) HomeFragment — applySubHeaders funksiyasi
    results.append(("HomeFragment: applySubHeaders()",
                    e_home.sub(APPLY_ANCHOR, APPLY_NEW, "yordamchi funksiya",
                               guard="private fun applySubHeaders(")))

    # 6) Layout — 3 ta TextView
    results.append(("item_home_card_header: 3 TextView",
                    e_layout.sub(LAYOUT_OLD, LAYOUT_NEW, "yangi elementlar",
                                 guard='id="@+id/card_info_traffic"')))

    # 7) CardVH.bind — bindSubInfo() chaqiruvi
    results.append(("HomeFragment: bindSubInfo() chaqiruvi",
                    e_home.sub(BIND_OLD, BIND_NEW, "chaqiruv",
                               guard="bindSubInfo(c)")))

    # 8) CardVH — bindSubInfo() tanasi
    results.append(("HomeFragment: bindSubInfo() tanasi",
                    e_home.sub(BIND_FN_ANCHOR, BIND_FN_NEW, "funksiya tanasi",
                               guard="private fun bindSubInfo(")))

    print("\n  ── O'zgarishlar ──")
    for name, done in results:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {name}")

    # ── YANGI FAYL ──
    helper_new = ensure_helper(args.check)
    print(f"   {'✓ YARATILADI' if helper_new else '– mavjud':16} "
          f"SubscribeHeader.kt")

    # ── TEKSHIRUVLAR ──
    print("\n  ── Tekshiruvlar ──")
    if not check_braces([e_sub, e_store, e_home]):
        print("\n✗ Qavslar balansi buzildi — BEKOR QILINDI")
        return 1
    print("   ✓ Kotlin qavslar balansi saqlandi")

    if e_layout.changed():
        # Vaqtinchalik faylga yozib XML tekshiramiz
        tmp = Path("/tmp/_fix149_layout.xml")
        tmp.write_text(e_layout.text, encoding="utf-8")
        if not check_xml(tmp):
            print("\n✗ XML buzildi — BEKOR QILINDI")
            return 1
        os.unlink(tmp)
    print("   ✓ XML tuzilishi to'g'ri (ET.parse)")

    # Element ID'lari joyidami?
    for rid in ["card_icon", "card_title", "card_count", "card_ping",
                "card_settings", "card_arrow"]:
        if f'@+id/{rid}"' not in e_layout.text:
            print(f"\n✗ MUHIM element yo'qoldi: {rid} — BEKOR QILINDI")
            return 1
    print("   ✓ Barcha eski elementlar joyida (icon/title/count/ping/settings/arrow)")

    # ═══ XULOSA ═══
    total = sum(1 for _, d in results if d) + (1 if helper_new else 0)
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        print("   Ishlatish uchun:  python3 tools/fix_sub_info_ui.py")
        return 0

    if total == 0:
        print("\n✓ Hech narsa qilish kerak emas (hammasi allaqachon qo'llangan)")
        return 0

    # ── YOZISH ──
    print("\n  ── Yozish ──")
    for e in [e_sub, e_store, e_home, e_layout]:
        if e.write(dry=False):
            bak = backup_path(e.path)
            print(f"   💾 {e.path.relative_to(ROOT)}")
            print(f"      zaxira: {bak.relative_to(ROOT)}")
    if helper_new:
        ensure_helper(dry=False)
        print(f"   💾 {HELPER.relative_to(ROOT)} (yangi)")

    print("\n" + "═" * 70)
    print("  ✅ TAYOR")
    print("═" * 70)
    print("""
  KEYINGI QADAMLAR:

    1) ./gradlew assembleDebug --console=plain

    2) ./gradlew testDebugUnitTest --console=plain
       (208 test o'tishi kerak)

    3) Qurilmaga o'rnatish:
       adb install -r app/build/outputs/apk/debug/app-debug.apk

    4) TEKSHIRISH (qurilmada):
       • Obuna kartasida "📅 .. kun qoldi | 📊 x.xx / y.yy GB" ko'rinishi
       • Sayt/support qatori (agar provayder yuborsa)
       • E'lon (agar bo'lsa)
       • Ping ⚙️ va ⬇️ tugmalari JOYIDA qolgani
       • Karta ochilib-yopilishi (FIX147) ishlashi
       • Serverni almashtirish (VPN qayta ulanishi) ishlashi

    5) Qaytarish kerak bo'lsa:
       python3 tools/fix_sub_info_ui.py --rollback
""")


if __name__ == "__main__":
    sys.exit(main())
