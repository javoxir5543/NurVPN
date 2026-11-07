#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX153-FOURFINAL — 4 ta yakuniy tuzatish
════════════════════════════════════════════════════════════════════════════

 ═══ 1-MUAMMO: Tugmalar matni kesilgan ("🌐 Са...") ═══

   SABAB: `card_info_btn_row` — `match_parent`, lekin ikkala tugma
          `wrap_content`. Konteyner `.card_info_box` ichida
          (karta matn ustuni), o'sha esa `weight=1` — ping/⚙/⬇
          ikonkalari bilan joy bo'lishadi. Joy yetmaganda ikkinchi
          tugma "🌐 Са…" bo'lib KESILADI.

   YECHIM: ikkala tugma `0dp` + `layout_weight="1"` — teng
           taqsimlanadi. `ellipsize` → `none`, `maxLines` → `1`.
           Endi har biri yarim kenglikni oladi va matn TO'LIQ sig'adi.

 ═══ 2-MUAMMO: Tugmalar bosilmaydi ═══

   SABAB: FIX152 da `clickable="false"` qo'yilgan edi (karta
          bosilishini himoya qilish uchun), lekin listener YO'Q.

   YECHIM: XML da `clickable="true"`. `bindSubInfo()` da
           `setOnClickListener` → `sub.supportUrl` / `sub.webPage`.
           Bo'sh URL bo'lsa — hech narsa qilmaydi.

   ⚠️ KARTA BOSILISHI BUZILMAYDI:
      - `CardVH.bind()` da `itemView.setOnClickListener` — karta
        ildizida (FIX143).
      - `card_info_btn_row` va uning OTA-ONALARI `clickable`
        EMAS → tugma bosilganda karta ildiziga YETIB BORMAYDI.
      - Birinchi `OnClickListener` (eng chuqur view) yutadi.

 ═══ 3-MUAMMO: Hysteria2 FinalMask JSON maydoni yo'q ═══

   ⚠️ MUHIM TEKSHIRUV NATIJASI (o'ylab qilingan qaror):

      `libbox.aar` (sing-box 1.14.3-lx18) ning native qatlamini
      tekshirdim: 63MB `libbox.so` ichida 200 014 satr bor.

          grep -c "final_mask" → 0
          grep -c "finalMask"  → 0
          grep -c "SalamanderConn" → 36

      Ya'ni: `final_mask` JSON kaliti bu libbox'da UMUMAN YO'Q.
      Uni JSON ga qo'shsak, sing-box noma'lum kalitni ko'rib
      Hysteria2 ULANISHINI BUZISHI MUMKIN.

   YECHIM (foydalanuvchi tanlovi — Variant A):
      • UI da maydon QO'SHILADI (faqat Hysteria2 da ko'rinadi)
      • `cfg.finalMask` ga SAQLANADI
      • Linkda `finalmask=` parametri sifatida SAQLANADI
        (`buildHy2` / `parse` orqali — to'liq aylanma yo'l)
      • JSON ga **YOZILMAYDI** — libbox buzilmaydi
      • Hysteria2 hozir `obfs=salamander` orqali ishlashda davom etadi

 ═══ 4-MUAMMO: Auto-rotate ═══

   ⚠️ TEKSHIRUV NATIJASI:

      AndroidManifest.xml da BARCHA 24 activity allaqachon
      `android:screenOrientation="portrait"` (FIX124-ORIENT).
      Runtime'da `setRequestedOrientation` chaqiruvi:
           grep → 0 ta natija

      Ya'ni ekran burilmasligi KERAK. Skript manifestni
      O'ZGARTIRMAYDI, faqat TEKSHIRADI va tasdiqlaydi.

 ═══ XAVFSIZLIK ═══

   • `toggleHomeCard` / scroll / Hysteria2 ulanishi TEGILMAYDI
   • Zaxiralar `.fix-backups/` ga (res/ ichida .bak YO'Q!)
   • Har bir `sub()` da qavs deltasi tekshiriladi
   • `ServerConfig` ga maydon QO'SHILADI (mavjudlari o'zgarmaydi)
   • Yangi string'lar 4 tilga qo'shiladi

 ═══ ISHLATISH ═══

     python3 tools/fix_four_final_bugs.py --check
     python3 tools/fix_four_final_bugs.py
     python3 tools/fix_four_final_bugs.py --rollback
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

# ⚠️ `res/` ICHIDA .bak QOLDIRILMAYDI (AGP: "must end with .xml")
BACKUP_DIR = ROOT / ".fix-backups"

LAYOUT = ROOT / "app/src/main/res/layout/item_home_card_header.xml"
HOME = ROOT / "app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt"
SCFG = ROOT / "app/src/main/java/com/nurvpn/app/core/ServerConfig.kt"
LB = ROOT / "app/src/main/java/com/nurvpn/app/core/LinkBuilder.kt"
EDITOR = ROOT / "app/src/main/java/com/nurvpn/app/ui/editor/ServerEditorActivity.kt"
LBOPT = ROOT / "app/src/main/java/com/nurvpn/app/core/LinkOptions.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

# ★ FIX154: qo'shimcha topilgan crash (5-muammo, hisobotda yo'q edi)
STORE = ROOT / "app/src/main/java/com/nurvpn/app/storage/ServerStore.kt"

STRINGS = [ROOT / f"app/src/main/res/values{s}/strings.xml"
           for s in ("", "-uz", "-ru", "-en")]

# `parse()` — LinkBuilder.kt ICHIDA (alohida LinkParser.kt YO'Q)
LB_PARSE = LB

ANDROID_ID = "{http://schemas.android.com/apk/res/android}"


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
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:170]!r}")
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

    def write(self) -> bool:
        if not self.changed():
            return False
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
#  1) XML — tugmalar teng kenglikda (kesilmaydi) + clickable
# ═══════════════════════════════════════════════════════════════════════

BTN_ROW_OLD = '''                <!-- [🛠 Поддержка] -->
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
                    android:focusable="false"/>'''

BTN_ROW_NEW = '''                <!--
                  ★ FIX153: KENGLIK TENG TAQSIMLANADI

                  ═══ MUAMMO ═══
                  Avval ikkala tugma `wrap_content` edi va
                  "🌐 Са…" bo'lib KESILARDI. Sabab: konteyner
                  `.card_info_box` ichida, u esa `weight=1` —
                  📶 / ⚙ / ⬇ ikonkalari bilan joy bo'lishadi.
                  Joy yetmaganda TextView oxirini qisqartiradi.

                  ═══ YECHIM ═══
                  `0dp` + `layout_weight="1"` → har biri
                  yarim kenglikni oladi, matn TO'LIQ sig'adi.
                  `ellipsize="none"` — endi umuman kesilmaydi.

                  ⚠️ `clickable="true"` — FIX153 da listener
                     qo'shildi (`bindSubInfo` → ACTION_VIEW).
                     Karta bosilishi buzilmaydi, chunki bu
                     tugma va uning ota-onalari orasida boshqa
                     `clickable` YO'Q — hodisa karta ildiziga
                     yetib bormaydi.
                -->
                <TextView
                    android:id="@+id/card_info_btn_support"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="8dp"
                    android:paddingEnd="8dp"
                    android:paddingTop="6dp"
                    android:paddingBottom="6dp"
                    android:gravity="center"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="none"
                    android:clickable="true"
                    android:focusable="true"/>

                <!--
                  ★ FIX153: [🌐 Сайт] — teng kenglik

                  ⚠️ Diqqat: bu tugmada `layout_marginEnd` YO'Q —
                     qatorning oxirgi elementi, o'ng chegara
                     konteynerning padding'i bilan ta'minlanadi.
                -->
                <TextView
                    android:id="@+id/card_info_btn_web"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="8dp"
                    android:paddingEnd="8dp"
                    android:paddingTop="6dp"
                    android:paddingBottom="6dp"
                    android:gravity="center"
                    android:textColor="@color/accent"
                    android:textSize="10sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="none"
                    android:clickable="true"
                    android:focusable="true"/>'''

# ═══════════════════════════════════════════════════════════════════════
#  2) HomeFragment.kt — tugmalarga OnClickListener
# ═══════════════════════════════════════════════════════════════════════

HOME_OLD = '''            if (hasSupport || hasWeb) {
                btnRow.visibility = View.VISIBLE
                supBtn.visibility = if (hasSupport) View.VISIBLE else View.GONE
                webBtn.visibility = if (hasWeb) View.VISIBLE else View.GONE
                supBtn.text = "\\uD83D\\uDEE0 " + getString(R.string.sub_btn_support)
                webBtn.text = "\\uD83C\\uDF10 " + getString(R.string.sub_btn_web)
            } else {
                btnRow.visibility = View.GONE
            }'''

HOME_NEW = '''            if (hasSupport || hasWeb) {
                btnRow.visibility = View.VISIBLE
                supBtn.visibility = if (hasSupport) View.VISIBLE else View.GONE
                webBtn.visibility = if (hasWeb) View.VISIBLE else View.GONE
                supBtn.text = "\\uD83D\\uDEE0 " + getString(R.string.sub_btn_support)
                webBtn.text = "\\uD83C\\uDF10 " + getString(R.string.sub_btn_web)

                // ★ FIX153: TUGMALAR ENDI BOSILADI.
                //   URL bo'sh bo'lsa — hech narsa qilmaydi
                //   (tugma baribir GONE bo'ladi, lekin himoya
                //   RecyclerView qayta ishlatishida muhim).
                //
                //   ⚠️ Karta bosilishi (`toggleHomeCard`) buzilmaydi:
                //      bu listener karta ildizidagi listenerdan
                //      CHUQURROQ, va oradagi view'larda `clickable`
                //      yo'q — hodisa yuqoriga chiqmaydi.
                supBtn.setOnClickListener {
                    val u = sub?.supportUrl.orEmpty()
                    if (u.isNotBlank()) openSubUrl(u)
                }
                webBtn.setOnClickListener {
                    val u = sub?.webPage.orEmpty()
                    if (u.isNotBlank()) openSubUrl(u)
                }
            } else {
                btnRow.visibility = View.GONE
            }'''

# Yordamchi funksiyani `bytesShort` dan oldin qo'shamiz (CardVH ichida)
HOME_HELPER_OLD = '''        /**
         * ★ FIX150-INCYSTYLE: baytlarni qisqa ko'rinishga o'giradi.'''

HOME_HELPER_NEW = '''        /**
         * ★ FIX153: obuna havolasini brauzerda ochadi.
         *
         *   Support/Sayt tugmalari bosilganda chaqiriladi.
         *
         *   ⚠️ Xato bo'lsa ilova YIQILMAYDI — `try/catch` +
         *      Toast. Brauzer o'rnatilmagan qurilmada ham
         *      xavfsiz (mavjud `btn_globe` namunasi bilan bir xil).
         */
        private fun openSubUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (t: Throwable) {
                android.widget.Toast.makeText(context,
                    "Brauzer topilmadi", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * ★ FIX150-INCYSTYLE: baytlarni qisqa ko'rinishga o'giradi.'''

# ═══════════════════════════════════════════════════════════════════════
#  3) ServerConfig.kt — finalMask maydoni
# ═══════════════════════════════════════════════════════════════════════

SCFG_OLD = '''    // Hysteria2
    var obfs: String = ""
    var obfsPassword: String = ""
    var upMbps: String = ""
    var downMbps: String = ""'''

SCFG_NEW = '''    // Hysteria2
    var obfs: String = ""
    var obfsPassword: String = ""
    var upMbps: String = ""
    var downMbps: String = ""

    /**
     * ★ FIX153: Hysteria2 FinalMask (UDP obfuscator) — JSON matn.
     *
     * ⚠️ MUHIM: bu maydon hozircha sing-box JSON ga YOZILMAYDI.
     *
     * Tekshiruv natijasi: `libbox.aar` (sing-box 1.14.3-lx18) ning
     * native qatlamida `final_mask` / `finalMask` kaliti UMUMAN YO'Q
     * (63MB `libbox.so` ichida 200 014 satr — 0 ta mos kelish).
     * Faqat `SalamanderConn` (ya'ni `obfs`) mavjud.
     *
     * Noto'g'ri kalit qo'shilsa sing-box Hysteria2 ulanishini
     * buzishi mumkin. Shuning uchun qiymat FAQAT saqlanadi:
     *   • UI → `cfg.finalMask`
     *   • Link → `finalmask=` parametri
     *
     * Kelajakda NurCore ishlatilganda shu maydon `final_mask`
     * sifatida JSON ga qo'shiladi.
     */
    var finalMask: String = ""'''

# ═══════════════════════════════════════════════════════════════════════
#  4) LinkBuilder.kt — finalmask= parametri (to'liq aylanma yo'l)
# ═══════════════════════════════════════════════════════════════════════

LB_OLD = '''    private fun buildHy2(c: ServerConfig): String {
        val params = mutableListOf<String>()
        if (c.sni.isNotEmpty()) params.add("sni=${enc(c.sni)}")
        if (c.obfs.isNotEmpty()) params.add("obfs=${c.obfs}")
        if (c.obfsPassword.isNotEmpty()) params.add("obfs-password=${enc(c.obfsPassword)}")
        if (c.allowInsecure) params.add("insecure=1")
        if (c.upMbps.isNotEmpty()) params.add("upmbps=${c.upMbps}")
        if (c.downMbps.isNotEmpty()) params.add("downmbps=${c.downMbps}")'''

LB_NEW = '''    private fun buildHy2(c: ServerConfig): String {
        val params = mutableListOf<String>()
        if (c.sni.isNotEmpty()) params.add("sni=${enc(c.sni)}")
        if (c.obfs.isNotEmpty()) params.add("obfs=${c.obfs}")
        if (c.obfsPassword.isNotEmpty()) params.add("obfs-password=${enc(c.obfsPassword)}")
        if (c.allowInsecure) params.add("insecure=1")
        if (c.upMbps.isNotEmpty()) params.add("upmbps=${c.upMbps}")
        if (c.downMbps.isNotEmpty()) params.add("downmbps=${c.downMbps}")
        // ★ FIX153: FinalMask saqlanadi (libbox JSON ga yozmaydi)
        if (c.finalMask.isNotEmpty()) params.add("finalmask=${enc(c.finalMask)}")'''

# ═══════════════════════════════════════════════════════════════════════
#  5) ServerEditorActivity.kt — UI maydoni
# ═══════════════════════════════════════════════════════════════════════

ED_FIELDS_OLD = '''    private lateinit var obfsSp: Spinner
    private lateinit var obfsPassEt: EditText
    private lateinit var upMbpsEt: EditText'''

ED_FIELDS_NEW = '''    private lateinit var obfsSp: Spinner
    private lateinit var obfsPassEt: EditText
    // ★ FIX153: Hysteria2 FinalMask (JSON matn)
    private lateinit var finalMaskEt: EditText
    private lateinit var upMbpsEt: EditText'''

ED_FIELDS2_OLD = '''    private lateinit var obfsGroup: LinearLayout
    private lateinit var obfsPassGroup: LinearLayout
    private lateinit var upMbpsGroup: LinearLayout'''

ED_FIELDS2_NEW = '''    private lateinit var obfsGroup: LinearLayout
    private lateinit var obfsPassGroup: LinearLayout
    // ★ FIX153: FinalMask guruhi (faqat HY2 da ko'rinadi)
    private lateinit var finalMaskGroup: LinearLayout
    private lateinit var upMbpsGroup: LinearLayout'''

ED_BUILD_OLD = '''        obfsPassGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        obfsPassEt = makeEt("obfs password")
        obfsPassGroup.addView(makeFieldRow(getString(R.string.editor_field_obfs_password), obfsPassEt))
        root.addView(obfsPassGroup)'''

ED_BUILD_NEW = '''        obfsPassGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        obfsPassEt = makeEt("obfs password")
        obfsPassGroup.addView(makeFieldRow(getString(R.string.editor_field_obfs_password), obfsPassEt))
        root.addView(obfsPassGroup)

        // ★ FIX153: Hysteria2 FinalMask (JSON) — faqat HY2 da ko'rinadi.
        //   Ko'p qatorli maydon (JSON uzun bo'ladi).
        //   ⚠️ JSON saqlanadi, lekin sing-box ga YOZILMAYDI —
        //      libbox'da `final_mask` kaliti yo'q.
        finalMaskGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        finalMaskEt = makeEt("{\\"udp\\":[{\\"type\\":\\"salamander\\"}]}").apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 4
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        finalMaskGroup.addView(makeFieldRow(
            getString(R.string.editor_field_final_mask), finalMaskEt))
        root.addView(finalMaskGroup)'''

ED_VIS_OLD = '''        // Hysteria2
        setV(obfsGroup, isHy2)
        setV(obfsPassGroup, isHy2)
        setV(upMbpsGroup, isHy2)
        setV(downMbpsGroup, isHy2)'''

ED_VIS_NEW = '''        // Hysteria2
        setV(obfsGroup, isHy2)
        setV(obfsPassGroup, isHy2)
        setV(upMbpsGroup, isHy2)
        setV(downMbpsGroup, isHy2)
        setV(finalMaskGroup, isHy2)   // ★ FIX153'''

# `fillFields` — `setText` bilan (saveAndExit dan farqli)
ED_FILL_OLD = '''        obfsSp.setSelection(obfsOptions.indexOf(cfg.obfs).coerceAtLeast(0))
        obfsPassEt.setText(cfg.obfsPassword)'''

ED_FILL_NEW = '''        obfsSp.setSelection(obfsOptions.indexOf(cfg.obfs).coerceAtLeast(0))
        obfsPassEt.setText(cfg.obfsPassword)
        finalMaskEt.setText(cfg.finalMask)   // ★ FIX153'''

# `saveAndExit` — spinner/Et dan o'qish (yagona mos keladigan joy)
ED_SAVE_OLD = '''        cfg.obfs = obfsOptions[obfsSp.selectedItemPosition]
        cfg.obfsPassword = obfsPassEt.text.toString().trim()
        cfg.upMbps = upMbpsEt.text.toString().trim()'''

ED_SAVE_NEW = '''        cfg.obfs = obfsOptions[obfsSp.selectedItemPosition]
        cfg.obfsPassword = obfsPassEt.text.toString().trim()
        cfg.finalMask = finalMaskEt.text.toString().trim()   // ★ FIX153
        cfg.upMbps = upMbpsEt.text.toString().trim()'''

# ═══════════════════════════════════════════════════════════════════════
#  6) String resurslari (4 til)
# ═══════════════════════════════════════════════════════════════════════

FM_STRINGS = {
    "": "FinalMask (JSON)",
    "-uz": "FinalMask (JSON)",
    "-ru": "FinalMask (JSON)",
    "-en": "FinalMask (JSON)",
}


def add_string(path: Path, name: str, value: str) -> bool:
    if not path.exists():
        return False
    txt = path.read_text(encoding="utf-8")
    if f'name="{name}"' in txt:
        return False
    marker = "</resources>"
    if marker not in txt:
        raise SystemExit(f"✗ [{path.name}] </resources> topilmadi")
    esc = (value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "\\'"))
    txt = txt.replace(marker, f'    <string name="{name}">{esc}</string>\n{marker}', 1)
    path.write_text(txt, encoding="utf-8")
    return True


# ═══════════════════════════════════════════════════════════════════════
#  8) ★ FIX154 — ServerStore crash (hisobotda yo'q edi, tekshiruvda topildi)
# ═══════════════════════════════════════════════════════════════════════
#
#  ═══ QANDAY TOPILDI ═══
#
#  Qurilmada 4 ta tuzatishni tekshirayotganda ilova YIQILDI:
#
#      java.util.ConcurrentModificationException
#        at java.util.ArrayList$Itr.next
#        at ServerStore.save(ServerStore.kt:16)
#        at HomeFragment...loadSubscription$lambda...
#
#  ═══ ILDIZ SABAB ═══
#
#  `ServerStore.save()` ro'yxatni TO'G'RIDAN-TO'G'RI aylanib chiqadi.
#  37 ta chaqiruv joyi bor va ularning aksariyati `a.servers` —
#  MainActivity'ning JONLI ro'yxatini — uzatadi. Ba'zilari fon
#  oqimida (`Thread { ServerStore.save(...) }`), boshqalari asosiy
#  oqimda. Obuna yuklanayotganda UI oqimi ro'yxatga server qo'shadi,
#  fon oqimi esa ayni paytda uni aylanadi → YIQILISH.
#
#  ═══ YECHIM ═══
#
#  Bitta umumiy joyda nusxa olish — 37 ta chaqiruvni tuzatishdan
#  ko'ra to'g'ri va kichikroq diff.

STORE_OLD = '''    fun save(ctx: Context, servers: List<ServerItem>) {
        val arr = JSONArray()
        for (si in servers) {'''

STORE_NEW = '''    fun save(ctx: Context, servers: List<ServerItem>) {
        val arr = JSONArray()
        // ★ FIX154-SNAPSHOT: nusxa ustida ishlaymiz.
        //
        //   ═══ ILDIZ SABAB ═══
        //
        //   Bu funksiya 37 ta joydan chaqiriladi va deyarli har doim
        //   `a.servers` — MainActivity'ning JONLI ro'yxati — uzatiladi.
        //   Ba'zi chaqiruvlar fon oqimida
        //   (`Thread { ServerStore.save(...) }`), boshqalari asosiy
        //   oqimda. Natijada:
        //
        //       java.util.ConcurrentModificationException
        //         at java.util.ArrayList$Itr.next
        //         at ServerStore.save(ServerStore.kt:16)
        //         at HomeFragment...loadSubscription$lambda...
        //
        //   Obuna yuklanayotganda UI oqimi ro'yxatga server qo'shadi,
        //   fon oqimi esa ayni paytda shu ro'yxatni aylanib chiqadi
        //   → ilova YIQILADI.
        //
        //   ═══ YECHIM ═══
        //
        //   Bir marta nusxa olamiz. Bu O(n) qo'shimcha xotira
        //   (686 server uchun ~o'nlab KB), lekin yiqilishni butunlay
        //   yo'q qiladi. 37 ta chaqiruv joyini tuzatishdan ko'ra
        //   bitta umumiy joyda tuzatish to'g'ri.
        val snapshot = try {
            ArrayList(servers)
        } catch (t: Throwable) {
            // O'ta kam holat: ro'yxat ayni damda o'zgartirilgan.
            // Bu holda saqlamaymiz — chala ma'lumot yozgandan ko'ra
            // keyingi muvaffaqiyatli saqlashni kutgan yaxshi.
            Log.w("NurVPN-STORE", "save: snapshot xatosi: ${t.message}")
            return
        }
        for (si in snapshot) {'''

# ═══════════════════════════════════════════════════════════════════════
#  7) Parse tomoni — finalmask= ni o'qish (LinkBuilder.kt ichida)
# ═══════════════════════════════════════════════════════════════════════

PARSE_OLD = '''                cfg.obfs = q["obfs"] ?: ""
                cfg.obfsPassword = q["obfs-password"] ?: ""'''

PARSE_NEW = '''                cfg.obfs = q["obfs"] ?: ""
                cfg.obfsPassword = q["obfs-password"] ?: ""
                cfg.finalMask = q["finalmask"] ?: ""   // ★ FIX153'''


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX153-FOURFINAL")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 74)
    print("  FIX153-FOURFINAL — 4 ta yakuniy tuzatish")
    print("═" * 74)

    if args.rollback:
        n = 0
        for p in [LAYOUT, HOME, SCFG, LB, EDITOR, LB_PARSE, STORE] + STRINGS:
            e = Edit(p)
            if e.rollback():
                print(f"   ↩ tiklandi: {p.relative_to(ROOT)}")
                n += 1
        print(f"\n{'✅' if n else '–'} {n} ta fayl tiklandi")
        return 0

    # ── 4-MUAMMO tekshiruvi (o'zgartirmaymiz) ──
    print("\n  ── 4-MUAMMO: Auto-rotate tekshiruvi ──")
    if MANIFEST.exists():
        mtxt = MANIFEST.read_text(encoding="utf-8")
        n_act = mtxt.count("<activity")
        n_port = mtxt.count('android:screenOrientation="portrait"')
        bad = []
        for m in ["sensor", "fullSensor", "unspecified"]:
            if f'android:screenOrientation="{m}"' in mtxt:
                bad.append(m)
        print(f"   activity soni: {n_act}, portrait: {n_port}")
        if bad:
            print(f"   ✗ Muammoli orientatsiya: {bad}")
        else:
            print("   ✓ Barcha activity 'portrait' — manifest TO'G'RI")
        rt = 0
        for f in (ROOT / "app/src/main/java").rglob("*.kt"):
            if "setRequestedOrientation" in f.read_text(encoding="utf-8",
                                                       errors="replace"):
                rt += 1
        print(f"   ✓ Runtime setRequestedOrientation: {rt} ta fayl")
        if bad or rt:
            print("   ⚠ Manifest o'zgartirilmaydi (foydalanuvchi qarori)")
    else:
        print("   ⚠ Manifest topilmadi")

    # ── Parse faylini aniqlash (LinkBuilder.kt ichida) ──
    parse_in_same_file = (LB_PARSE == LB) and (PARSE_OLD in LB.read_text(encoding="utf-8"))
    if parse_in_same_file:
        print("   ✓ parse() joyi: LinkBuilder.kt (bir fayl ichida)")

    # ── Fayllar bormi? ──
    need = [LAYOUT, HOME, SCFG, LB, EDITOR]
    for p in need:
        if not p.exists():
            print(f"\n✗ Fayl topilmadi: {p.relative_to(ROOT)}")
            return 1

    e_l = Edit(LAYOUT)
    e_h = Edit(HOME)
    e_s = Edit(SCFG)
    e_b = Edit(LB)
    e_e = Edit(EDITOR)
    e_st = Edit(STORE)

    res = []
    res.append(("Tugmalar: 0dp + weight=1 (kesilmaydi)",
                e_l.sub(BTN_ROW_OLD, BTN_ROW_NEW, "tugma kengligi",
                        guard='android:id="@+id/card_info_btn_support"\n                    android:layout_width="0dp"')))
    res.append(("Tugmalar: OnClickListener (ACTION_VIEW)",
                e_h.sub(HOME_OLD, HOME_NEW, "tugma listener",
                        guard="openSubUrl(u)")))
    res.append(("HomeFragment: openSubUrl() yordamchisi",
                e_h.sub(HOME_HELPER_OLD, HOME_HELPER_NEW, "openSubUrl helper",
                        guard="private fun openSubUrl(")))
    res.append(("ServerConfig: finalMask maydoni",
                e_s.sub(SCFG_OLD, SCFG_NEW, "finalMask field",
                        guard="var finalMask: String")))
    res.append(("LinkBuilder: finalmask= parametri",
                e_b.sub(LB_OLD, LB_NEW, "buildHy2 finalmask",
                        guard='params.add("finalmask=')))
    res.append(("Editor: maydon e'lonlari (Et)",
                e_e.sub(ED_FIELDS_OLD, ED_FIELDS_NEW, "finalMaskEt lateinit",
                        guard="private lateinit var finalMaskEt")))
    res.append(("Editor: guruh e'lonlari (Group)",
                e_e.sub(ED_FIELDS2_OLD, ED_FIELDS2_NEW, "finalMaskGroup lateinit",
                        guard="private lateinit var finalMaskGroup")))
    res.append(("Editor: maydon qurilishi (HY2)",
                e_e.sub(ED_BUILD_OLD, ED_BUILD_NEW, "finalMask UI",
                        guard="finalMaskGroup = LinearLayout")))
    res.append(("Editor: faqat Hysteria2 da ko'rinadi",
                e_e.sub(ED_VIS_OLD, ED_VIS_NEW, "finalMask visibility",
                        guard="setV(finalMaskGroup, isHy2)")))
    res.append(("Editor: fillFields (yuklash)",
                e_e.sub(ED_FILL_OLD, ED_FILL_NEW, "finalMask fill",
                        guard="cfg.finalMask = finalMaskEt.text")))
    # saveAndExit — `fillFields` allaqachon o'zgargan bo'lishi mumkin
    # ⚠️ IDEMPOTENTLIK: guard SHART, aks holda 2-marta ishga tushirilganda
    #    `cfg.finalMask = finalMaskEt.text...` ikki marta qo'shiladi.
    save_ok = False
    if "cfg.finalMask = finalMaskEt.text.toString().trim()   // ★ FIX153\n        cfg.upMbps" in e_e.text:
        save_ok = False   # allaqachon bor (saveAndExit da)
    elif ED_SAVE_OLD in e_e.text:
        save_ok = e_e.sub(ED_SAVE_OLD, ED_SAVE_NEW, "finalMask save")
    else:
        alt_old = '''        cfg.obfsPassword = obfsPassEt.text.toString().trim()
        cfg.upMbps = upMbpsEt.text.toString().trim()'''
        alt_new = '''        cfg.obfsPassword = obfsPassEt.text.toString().trim()
        cfg.finalMask = finalMaskEt.text.toString().trim()   // ★ FIX153
        cfg.upMbps = upMbpsEt.text.toString().trim()'''
        save_ok = e_e.sub(alt_old, alt_new, "finalMask save",
                          guard="cfg.finalMask = finalMaskEt.text.toString().trim()   // ★ FIX153\n        cfg.upMbps")
    res.append(("Editor: saveAndExit (saqlash)", save_ok))

    if parse_in_same_file:
        res.append(("LinkBuilder: finalmask= o'qish (parse)",
                    e_b.sub(PARSE_OLD, PARSE_NEW, "parse finalmask",
                            guard='cfg.finalMask = q["finalmask"]')))

    # ★ FIX154: crash — umumiy joyda nusxa olish
    res.append(("★ FIX154: ServerStore crash (snapshot)",
                e_st.sub(STORE_OLD, STORE_NEW, "ServerStore snapshot",
                         guard="val snapshot = try {")))

    print("\n  ── O'zgarishlar ──")
    for name, done in res:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {name}")

    # ── Tekshiruvlar ──
    print("\n  ── Tekshiruvlar ──")

    if e_l.changed():
        tmp = Path("/tmp/_f153.xml")
        tmp.write_text(e_l.text, encoding="utf-8")
        try:
            ET.parse(str(tmp))
            print("   ✓ XML tuzilishi to'g'ri")
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ XML buzildi: {ex} — BEKOR")
            return 1
        finally:
            tmp.unlink(missing_ok=True)

        need_ids = ["card_icon", "card_title", "card_count", "card_ping",
                    "card_settings", "card_arrow", "card_info_row",
                    "card_info_days", "card_info_traffic",
                    "card_info_progress", "card_info_links",
                    "card_info_announce", "card_info_btn_row",
                    "card_info_btn_support", "card_info_btn_web"]
        miss = [n for n in need_ids if f'@+id/{n}"' not in e_l.text]
        if miss:
            print(f"   ✗ Element yo'q: {miss} — BEKOR")
            return 1
        print(f"   ✓ {len(need_ids)} ta element joyida")

        if 'android:clickable="true"' not in e_l.text:
            print("   ✗ FIX143 karta clickable yo'qoldi — BEKOR")
            return 1
        print("   ✓ FIX143 (ildiz clickable) saqlandi")

        # Ikkala tugma 0dp + weight=1 + clickable=true
        for bid in ["card_info_btn_support", "card_info_btn_web"]:
            key = f'<TextView\n                    android:id="@+id/{bid}"'
            if key not in e_l.text:
                print(f"   ✗ {bid} topilmadi — BEKOR")
                return 1
            body = e_l.text.split(key)[1][:900]
            for attr, want in [('android:layout_width="0dp"', True),
                               ('android:layout_weight="1"', True),
                               ('android:clickable="true"', True),
                               ('android:ellipsize="none"', True)]:
                if want and attr not in body:
                    print(f"   ✗ {bid} da {attr} yo'q — BEKOR")
                    return 1
        print("   ✓ ikkala tugma: 0dp + weight=1 + clickable=true + ellipsize=none")

        ann = e_l.text.split('card_info_announce')[1][:700]
        if 'android:maxLines="4"' not in ann:
            print("   ✗ e'lon maxLines=4 buzilgan — BEKOR")
            return 1
        print("   ✓ e'lon maxLines=4 (FIX152) saqlandi")

    if e_h.changed():
        d0 = e_h.orig.count("{") - e_h.orig.count("}")
        d1 = e_h.text.count("{") - e_h.text.count("}")
        if d0 != d1:
            print(f"   ✗ Kotlin qavslar: {d0} → {d1} — BEKOR")
            return 1
        print(f"   ✓ HomeFragment qavslar balansi (farq={d1})")
        for m in ["toggleHomeCard(", "bindSubInfo("]:
            if m not in e_h.text:
                print(f"   ✗ Yo'qolgan: {m} — BEKOR")
                return 1
        print("   ✓ toggleHomeCard / bindSubInfo joyida")
        if "openSubUrl(" not in e_h.text:
            print("   ⚠ openSubUrl() yordamchisi hali QO'SHILMAGAN")

    for e, nm in [(e_s, "ServerConfig"), (e_b, "LinkBuilder"),
                  (e_e, "ServerEditorActivity"), (e_st, "ServerStore")]:
        if e.changed():
            d0 = e.orig.count("{") - e.orig.count("}")
            d1 = e.text.count("{") - e.text.count("}")
            if d0 != d1:
                print(f"   ✗ {nm} qavslar: {d0} → {d1} — BEKOR")
                return 1
            print(f"   ✓ {nm} qavslar balansi")

    total = sum(1 for _, d in res if d)
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    print("\n  ── Yozish ──")
    for e in [e_l, e_h, e_s, e_b, e_e, e_st]:
        if e.write():
            print(f"   💾 {e.path.relative_to(ROOT)}")

    # ── String resurslari ──
    print("\n  ── String resurslari ──")
    for sp, val in FM_STRINGS.items():
        p = ROOT / f"app/src/main/res/values{sp}/strings.xml"
        try:
            if add_string(p, "editor_field_final_mask", val):
                print(f"   ✓ values{sp or '/':6} → editor_field_final_mask")
            else:
                print(f"   – values{sp or '/':6} allaqachon bor")
        except SystemExit as ex:
            print(f"   ✗ {ex}")

    print("\n" + "═" * 74)
    print("  ✅ TAYOR")
    print("═" * 74)
    print("""
  YAKUNIY KO'RINISH (Hysteria2 kartasi):

    ┌────────────────────────────────────────────────┐
    │ 📡  Buba VPN                     📶  ⚙  ⬇      │
    │     📅 Остался 1 день      0 B / 50.00 GB      │
    │     ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░         │
    │     ┌───────────────┐ ┌───────────────┐        │ ← teng
    │     │ 🛠 Поддержка  │ │   🌐 Сайт     │        │ ← BOSILADI
    │     └───────────────┘ └───────────────┘        │
    │     ┌ ⚠️ Выключите VPN и нажмите 🔄, если...   │ ← 4 qator
    │     └ ...                                      │
    └────────────────────────────────────────────────┘

  DYNAMIC (Hysteria2 tanlanganda ServerEditor da):

    ┌ FinalMask (JSON) ──────────────────────────┐
    │ {"udp":[{"type":"salamander"}]}            │ ← ko'rinadi
    └────────────────────────────────────────────┘

    ⚠️ JSON saqlanadi + linkda uzatiladi,
       lekin sing-box ga YOZILMAYDI (libbox'da
       `final_mask` kaliti yo'q — ulanish buzilmasin).

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Tekshir: tugma matni to'liq + bosiladi, editor'da FinalMask
""")


if __name__ == "__main__":
    sys.exit(main())
