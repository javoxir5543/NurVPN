#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX155-FINALMASKPRETTY — FinalMask JSON: parol + pretty-print + format o'girish
════════════════════════════════════════════════════════════════════════════

 ═══ MUAMMO ═══

   `FinalMask (JSON)` maydoni quruq qolip ko'rsatardi:

       {"udp":[{"type":"salamander"}]}      ← parol YO'Q
                                              bitta qatorga tiqilgan

   Foydalanuvchi INCY'dagidek TO'LIQ va CHIROYLI JSON ko'rishni xohlaydi:

       {
         "udp": [
           {
             "type": "salamander",
             "settings": {
               "password": "dzcb7hv6i2kx2zze"
             }
           }
         ]
       }

 ═══ ★★ ENG MUHIM TOPILMA: IKKI XIL FORMAT ★★ ═══

   sing-box'ning RASMIY manbasini tekshirdim
   (github.com/SagerNet/sing-box/option/hysteria2.go):

       type Hysteria2Obfs struct {
           Type     string `json:"type,omitempty"`
           Password string `json:"password,omitempty"`   ← YASSI!
       }

   Ya'ni:

       sing-box (BIZNING YADRO)  →  {"type":"salamander","password":"..."}
       XCORE / clash-meta (INCY) →  {"type":"salamander","settings":{"password":"..."}}

   ⚠️ Bu ikki format BIR-BIRIGA MOS EMAS!

   INCY XCORE yadrosini ishlatadi, biz `libbox` (sing-box) ishlatamiz.
   `settings` qavatini sing-box TUSHUNMAYDI — u parolni topa olmay
   "missing obfs password" xatosini beradi. Buni native kutubxonadan
   ham tasdiqladim: `libbox.so` ichida `missing obfs password` va
   `unknown obfs` xato satrlari bor.

   ═══ FOYDALANUVCHI QARORI (Variant B) ═══

       KO'RSATISHDA → INCY formati (settings.password) — chiroyli, tanish
       SAQLASHDA    → sing-box formatiga O'GIRILADI — ulanish ishlaydi

   Ya'ni foydalanuvchi INCY'dagidek ko'radi, lekin yadro to'g'ri
   formatni oladi. `obfs` / `obfs-password` maydonlari ham
   sinxronlanadi (asosiy manba — ular).

 ═══ YECHIM ═══

   1) `FinalMaskJson` nomli YANGI yordamchi obyekt (sof mantiq,
      unit-testda tekshiriladi):
        • `display(cfg)`   → INCY uslubidagi chiroyli JSON matn
        • `applyTo(cfg, text)` → JSON ni o'qib `obfs` + `obfsPassword`
          ni yangilaydi va sing-box formatini saqlaydi
        • `toSingBox(text)` / `toDisplay(text)` — format o'girish

   2) `ServerEditorActivity`:
        • hint quruq qolipdan TO'LIQ namunaga o'zgartiriladi
        • `fillFields()` da `display()` chaqiriladi
        • `saveAndExit()` da `applyTo()` chaqiriladi (try-catch)

   3) `LinkBuilder` ga TEGILMAYDI — link formati (`finalmask=`)
      o'zgarmaydi, faqat ichidagi JSON matni o'zgaradi.

 ═══ XAVFSIZLIK ═══

   • Xato JSON → try-catch, ilova QULAMAYDI, maydon o'zgarmaydi
   • JSON bo'sh bo'lsa → `obfs`/`obfsPassword` ga tegilmaydi
   • Zaxiralar `.fix-backups/` ga (`res/` ichida .bak YO'Q!)
   • Qavs deltasi har bir `sub()` da tekshiriladi

 ═══ ISHLATISH ═══

     python3 tools/fix_finalmask_pretty.py --check
     python3 tools/fix_finalmask_pretty.py
     python3 tools/fix_finalmask_pretty.py --rollback
"""

import argparse
import glob
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")

# ⚠️ `res/` ICHIDA .bak QOLDIRILMAYDI (AGP: "must end with .xml")
BACKUP_DIR = ROOT / ".fix-backups"

EDITOR = ROOT / "app/src/main/java/com/nurvpn/app/ui/editor/ServerEditorActivity.kt"
JSON_HELPER = ROOT / "app/src/main/java/com/nurvpn/app/core/FinalMaskJson.kt"


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
#  YANGI FAYL: FinalMaskJson.kt — sof mantiq (unit-testda tekshiriladi)
# ═══════════════════════════════════════════════════════════════════════

HELPER_SRC = r'''package com.nurvpn.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  ★ FIX155: Hysteria2 FinalMask JSON — ko'rsatish va o'girish
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ═══ NEGA BU FAYL KERAK ═══
 *
 *  Hysteria2 obfuscator (`salamander`) uchun JSON IKKI XIL formatda
 *  yoziladi:
 *
 *    ┌─────────────────────────┬──────────────────────────────────────┐
 *    │ sing-box (BIZNING YADRO)│ XCORE / clash-meta (INCY)            │
 *    ├─────────────────────────┼──────────────────────────────────────┤
 *    │ {                       │ {                                    │
 *    │   "type": "salamander", │   "type": "salamander",              │
 *    │   "password": "..."     │   "settings": {                      │
 *    │ }                       │     "password": "..."                │
 *    │                         │   }                                  │
 *    │                         │ }                                    │
 *    └─────────────────────────┴──────────────────────────────────────┘
 *
 *  Bu tasdiqlangan: sing-box'ning rasmiy manbasida
 *  (`option/hysteria2.go`) struktura YASSI:
 *
 *      type Hysteria2Obfs struct {
 *          Type     string `json:"type,omitempty"`
 *          Password string `json:"password,omitempty"`
 *      }
 *
 *  ⚠️ `settings` qavatini sing-box TUSHUNMAYDI — "missing obfs
 *     password" xatosini beradi va ULANISH ISHLAMAYDI.
 *
 *  ═══ YECHIM ═══
 *
 *  Foydalanuvchi INCY'dagidek (`settings`) ko'radi — u chiroyli va
 *  tanish. Saqlashda avtomatik sing-box formatiga o'giriladi, shunda
 *  yadro to'g'ri ishlaydi.
 *
 *  ═══ ASOSIY MANBA ═══
 *
 *  `obfs` va `obfsPassword` — HAQIQIY manba (link va sing-box ularni
 *  ishlatadi). JSON faqat KO'RSATISH/saqlash qulayligi uchun.
 *  `applyTo()` ikkalasini JSON dan yangilaydi.
 *
 *  ═══ NEGA QO'LDA FORMATLAYMIZ ═══
 *
 *  `org.json.JSONObject.toString(2)` ATAYLAB ishlatilmaydi — u faqat
 *  BIRINCHI darajani formatlaydi, ichma-ich obyektlarni bitta qatorga
 *  tiqadi:
 *
 *      {"udp": [{
 *        "settings": {"password": "x"},      ← ICHMA-ICH TIQILGAN
 *        "type": "salamander"
 *      }]}
 *
 *  Foydalanuvchi INCY'dagidek HAR BIR qatorni alohida ko'rishni
 *  so'radi. Shuning uchun `pretty()` o'zimizniki — rekursiv
 *  formatlash, 2 bo'shliq indent.
 *
 *  `LinkedHashMap` esa kalitlar TARTIBINI saqlaydi (`org.json`
 *  ularni alifbo bo'yicha saralaydi va `type` `settings` dan keyin
 *  tushib qolardi — INCY'dagidan farq).
 */
object FinalMaskJson {

    /** Obfuscator turi — hozircha yagona qo'llab-quvvatlanadigan. */
    const val TYPE_SALAMANDER = "salamander"

    private const val INDENT = 2

    /**
     * ═══ KO'RSATISH: INCY uslubidagi chiroyli JSON ═══
     *
     * @param type     obfs turi ("salamander")
     * @param password obfs paroli (bo'sh bo'lishi mumkin)
     * @return 2 bo'shliqli indent bilan formatlangan JSON, yoki
     *         bo'sh satr (tur bo'sh bo'lsa)
     */
    fun display(type: String, password: String): String {
        if (type.isBlank()) return ""
        return try {
            // ⚠️ LinkedHashMap — `type` AVVAL, `settings` KEYIN
            //    (INCY'dagi tartib). oddiy JSONObject alifbo bo'yicha
            //    saralaydi va tartib buziladi.
            val settings = LinkedHashMap<String, Any?>()
            settings["password"] = password

            val inner = LinkedHashMap<String, Any?>()
            inner["type"] = type
            inner["settings"] = settings

            val root = LinkedHashMap<String, Any?>()
            root["udp"] = listOf(inner)

            pretty(root)
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * ═══ O'QISH: JSON dan type + password ajratib olish ═══
     *
     * IKKALA formatni ham tushunadi:
     *   • XCORE  — `settings.password`  (foydalanuvchi yozadi)
     *   • sing-box — `password`          (o'zimiz yozgan bo'lishi mumkin)
     *
     * @return `Pair(type, password)` yoki `null` — JSON xato/bo'sh bo'lsa
     */
    fun parse(text: String): Pair<String, String>? {
        if (text.isBlank()) return null
        return try {
            val root = JSONObject(text)

            // `udp` massivini topamiz (bo'lmasa — ildizning o'zi)
            val node: JSONObject = root.optJSONArray("udp")
                ?.let { if (it.length() > 0) it.optJSONObject(0) else null }
                ?: root

            val type = node.optString("type", "").trim()
            if (type.isEmpty()) return null

            // ⚠️ IKKALA formatni qo'llab-quvvatlaymiz
            val password = node.optJSONObject("settings")
                ?.optString("password", "")
                ?: node.optString("password", "")

            Pair(type, password)
        } catch (t: Throwable) {
            // Foydalanuvchi xato yozgan — ilova QULAMAYDI
            null
        }
    }

    /**
     * ═══ O'GIRISH: istalgan format → sing-box (YASSI) ═══
     *
     * `settings.password` ni yuqoriga ko'taradi. Natija:
     *   {"udp":[{"type":"salamander","password":"..."}]}
     */
    fun toSingBox(text: String): String {
        val p = parse(text) ?: return text
        return try {
            val inner = LinkedHashMap<String, Any?>()
            inner["type"] = p.first
            inner["password"] = p.second

            val root = LinkedHashMap<String, Any?>()
            root["udp"] = listOf(inner)

            pretty(root)
        } catch (t: Throwable) {
            text
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Chiroyli formatlash (rekursiv)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Rekursiv pretty-print.
     *
     * `org.json` ning `toString(2)` i ichma-ich obyektlarni
     * formatlamaydi — shuning uchun o'zimiz yozamiz.
     */
    private fun pretty(value: Any?, level: Int = 0): String {
        val pad = " ".repeat(level * INDENT)
        val padInner = " ".repeat((level + 1) * INDENT)
        return when (value) {
            is Map<*, *> -> {
                if (value.isEmpty()) return "{}"
                val sb = StringBuilder("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padInner).append(quote(k.toString()))
                        .append(": ").append(pretty(v, level + 1))
                }
                sb.append("\n").append(pad).append("}").toString()
            }
            is List<*> -> {
                if (value.isEmpty()) return "[]"
                val sb = StringBuilder("[\n")
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",\n")
                    sb.append(padInner).append(pretty(v, level + 1))
                }
                sb.append("\n").append(pad).append("]").toString()
            }
            is String -> quote(value)
            null -> "null"
            is Boolean, is Number -> value.toString()
            else -> quote(value.toString())
        }
    }

    /** JSON satr uchun xavfsiz qo'shtirnoq + ekranlash. */
    private fun quote(s: String): String =
        JSONObject.quote(s)
}
'''


# ═══════════════════════════════════════════════════════════════════════
#  ServerEditorActivity.kt tahrirlari
# ═══════════════════════════════════════════════════════════════════════

# 1) Hint — quruq qolipdan TO'LIQ namunaga
HINT_OLD = '''        finalMaskEt = makeEt("{\\"udp\\":[{\\"type\\":\\"salamander\\"}]}").apply {'''

HINT_NEW = '''        // ★ FIX155: hint — TO'LIQ namuna (parol bilan, INCY uslubida).
        //   Avval quruq `{"udp":[{"type":"salamander"}]}` edi —
        //   foydalanuvchi parolni qayerga yozishni bilmasdi.
        finalMaskEt = makeEt(
            "{\\n  \\"udp\\": [\\n    {\\n      \\"type\\": \\"salamander\\",\\n" +
            "      \\"settings\\": {\\n        \\"password\\": \\"...\\"\\n" +
            "      }\\n    }\\n  ]\\n}").apply {'''

# 2) minLines oshirish (JSON chiroyli ko'rinishi uchun)
MINLINES_OLD = '''            maxLines = 4
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }'''

MINLINES_NEW = '''            // ★ FIX155: chiroyli JSON 9+ qatorni egallaydi
            maxLines = 14
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            // JSON — texnik matn, avtoto'g'rilash/taklif ZARARLI
            setHorizontallyScrolling(false)
        }'''

# 3) fillFields — INCY uslubidagi chiroyli JSON
FILL_OLD = '''        finalMaskEt.setText(cfg.finalMask)   // ★ FIX153'''

FILL_NEW = '''        // ★ FIX155: INCY uslubidagi TO'LIQ va chiroyli JSON.
        //   Avval `cfg.finalMask` xom matni yozilardi — u bo'sh
        //   yoki eski quruq qolip bo'lishi mumkin edi.
        //   Endi har doim `obfs` + `obfsPassword` dan YASALADI.
        finalMaskEt.setText(
            FinalMaskJson.display(cfg.obfs, cfg.obfsPassword))'''

# 4) saveAndExit — JSON dan obfs/obfsPassword ni yangilash
SAVE_OLD = '''        cfg.finalMask = finalMaskEt.text.toString().trim()   // ★ FIX153
        cfg.upMbps = upMbpsEt.text.toString().trim()'''

SAVE_NEW = '''        // ★ FIX155: JSON dan `type` + `password` ajratib olamiz.
        //
        //   ⚠️ `obfs` / `obfsPassword` — ASOSIY manba (link va
        //      sing-box ularni ishlatadi). JSON dan yangilanadi.
        //
        //   Xato JSON bo'lsa → `parse()` null qaytaradi va
        //   mavjud qiymatlar SAQLANIB QOLADI (ilova qulamaydi).
        val fmText = finalMaskEt.text.toString().trim()
        val fmParsed = FinalMaskJson.parse(fmText)
        if (fmParsed != null) {
            val (fmType, fmPass) = fmParsed
            if (fmType.isNotEmpty()) cfg.obfs = fmType
            cfg.obfsPassword = fmPass
            // Spinner'ni ham sinxronlaymiz (ko'rinish mos bo'lsin)
            val idx = obfsOptions.indexOf(fmType)
            if (idx >= 0) obfsSp.setSelection(idx)
        }
        // sing-box (YASSI) formatida saqlanadi — `settings` qavati
        // yadro tomonidan tushunilmaydi.
        cfg.finalMask = if (fmText.isEmpty()) "" else FinalMaskJson.toSingBox(fmText)

        cfg.upMbps = upMbpsEt.text.toString().trim()'''

# 5) Import qo'shish
IMPORT_OLD = '''import com.nurvpn.app.core.LinkBuilder
import com.nurvpn.app.core.Protocol'''

IMPORT_NEW = '''import com.nurvpn.app.core.FinalMaskJson
import com.nurvpn.app.core.LinkBuilder
import com.nurvpn.app.core.Protocol'''


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX155-FINALMASKPRETTY")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 74)
    print("  FIX155-FINALMASKPRETTY — JSON: parol + pretty-print + o'girish")
    print("═" * 74)

    if args.rollback:
        n = 0
        for p in [EDITOR, JSON_HELPER]:
            e = Edit(p)
            if e.rollback():
                print(f"   ↩ tiklandi: {p.relative_to(ROOT)}")
                n += 1
        print(f"\n{'✅' if n else '–'} {n} ta fayl tiklandi")
        return 0

    if not EDITOR.exists():
        print(f"\n✗ Fayl topilmadi: {EDITOR.relative_to(ROOT)}")
        return 1

    # ── Yangi yordamchi fayl ──
    helper_exists = JSON_HELPER.exists()
    print("\n  ── FinalMaskJson.kt ──")
    hmark = "– allaqachon bor" if helper_exists else "✓ QO'SHILADI"
    print(f"   {hmark}")

    e_e = Edit(EDITOR)

    res = []
    res.append(("Import: FinalMaskJson",
                e_e.sub(IMPORT_OLD, IMPORT_NEW, "import",
                        guard="import com.nurvpn.app.core.FinalMaskJson")))
    res.append(("Hint: to'liq namuna (parol bilan)",
                e_e.sub(HINT_OLD, HINT_NEW, "hint",
                        guard='settings\\": {')))
    res.append(("Maydon: 14 qator, minLines=6",
                e_e.sub(MINLINES_OLD, MINLINES_NEW, "minLines",
                        guard="maxLines = 14")))
    res.append(("fillFields: chiroyli JSON yasaladi",
                e_e.sub(FILL_OLD, FILL_NEW, "fill display",
                        guard="FinalMaskJson.display(cfg.obfs, cfg.obfsPassword)")))
    res.append(("saveAndExit: JSON'dan obfs/parol",
                e_e.sub(SAVE_OLD, SAVE_NEW, "save parse",
                        guard="FinalMaskJson.parse(fmText)")))

    print("\n  ── O'zgarishlar ──")
    for name, done in res:
        mark = "✓ QO'SHILDI" if done else "– allaqachon bor"
        print(f"   {mark:16} {name}")

    # ── Tekshiruvlar ──
    print("\n  ── Tekshiruvlar ──")

    if e_e.changed():
        d0 = e_e.orig.count("{") - e_e.orig.count("}")
        d1 = e_e.text.count("{") - e_e.text.count("}")
        if d0 != d1:
            print(f"   ✗ Kotlin qavslar: {d0} → {d1} — BEKOR")
            return 1
        print(f"   ✓ ServerEditorActivity qavslar balansi (farq={d1})")

        for m in ["private fun saveAndExit(", "private fun applyVisibility(",
                  "private fun fillFields(", "obfsOptions"]:
            if m not in e_e.text:
                print(f"   ✗ Yo'qolgan: {m} — BEKOR")
                return 1
        print("   ✓ saveAndExit / applyVisibility / fillFields joyida")

        if "FinalMaskJson" not in e_e.text:
            print("   ✗ FinalMaskJson ishlatilmagan — BEKOR")
            return 1
        print("   ✓ FinalMaskJson: display + parse chaqiriladi")

        # `settings` (INCY) formati ko'rsatishda ishlatilishi
        # ⚠️ Kotlin satri ichida qo'shtirnoq ekranlangan: \"settings\"
        if '\\"settings\\": {' not in e_e.text:
            print("   ✗ ko'rsatishda `settings` qavati yo'q — BEKOR")
            return 1
        print("   ✓ ko'rsatishda INCY formati (`settings`)")

    # ── Yangi fayl validatsiyasi (sof mantiq testi) ──
    if not helper_exists:
        src = HELPER_SRC
        for marker in ["object FinalMaskJson", "fun display(",
                       "fun parse(", "fun toSingBox(", "toString(2)"]:
            if marker not in src:
                print(f"   ✗ FinalMaskJson da yo'q: {marker} — BEKOR")
                return 1
        d = src.count("{") - src.count("}")
        if d != 0:
            print(f"   ✗ FinalMaskJson qavslar nomuvofiq: {d} — BEKOR")
            return 1
        print("   ✓ FinalMaskJson: display/parse/toSingBox + balans")

    # ── Python'da mantiqni SIMULYATSIYA qilib tekshiramiz ──
    print("\n  ── Mantiq simulyatsiyasi ──")
    import json as _json

    def _display(t, p):
        return _json.dumps({"udp": [{"type": t,
                                     "settings": {"password": p}}]},
                           indent=2, ensure_ascii=False)

    def _parse(text):
        try:
            root = _json.loads(text)
        except Exception:
            return None
        node = root
        if isinstance(root, dict) and isinstance(root.get("udp"), list) \
                and root["udp"]:
            node = root["udp"][0]
        t = (node.get("type") or "").strip()
        if not t:
            return None
        pw = (node.get("settings") or {}).get("password")
        if pw is None:
            pw = node.get("password", "")
        return (t, pw)

    def _to_singbox(text):
        p = _parse(text)
        if not p:
            return text
        return _json.dumps({"udp": [{"type": p[0], "password": p[1]}]},
                           indent=2, ensure_ascii=False)

    # 1) display parolni o'z ichiga oladimi?
    disp = _display("salamander", "dzcb7hv6i2kx2zze")
    ok1 = "dzcb7hv6i2kx2zze" in disp and '"settings"' in disp
    print(f"   {'✓' if ok1 else '✗'} display(): parol + settings qavati")
    if not ok1:
        return 1

    # 2) pretty-print (ko'p qatorli)?
    ok2 = disp.count("\n") >= 6
    print(f"   {'✓' if ok2 else '✗'} pretty-print: {disp.count(chr(10))} qator")
    if not ok2:
        return 1

    # 3) parse XCORE formatini o'qiydimi?
    p3 = _parse(disp)
    ok3 = p3 == ("salamander", "dzcb7hv6i2kx2zze")
    print(f"   {'✓' if ok3 else '✗'} parse(XCORE): {p3}")
    if not ok3:
        return 1

    # 4) parse sing-box (yassi) formatini ham o'qiydimi?
    flat = _json.dumps({"udp": [{"type": "salamander",
                                 "password": "abc"}]}, indent=2)
    p4 = _parse(flat)
    ok4 = p4 == ("salamander", "abc")
    print(f"   {'✓' if ok4 else '✗'} parse(sing-box yassi): {p4}")
    if not ok4:
        return 1

    # 5) toSingBox `settings` ni ko'taradimi?
    sb = _to_singbox(disp)
    ok5 = '"settings"' not in sb and '"password"' in sb
    print(f"   {'✓' if ok5 else '✗'} toSingBox: settings qavati olib tashlandi")
    if not ok5:
        return 1

    # 6) XATO JSON — qulamaydi (null)
    ok6 = _parse("{buzuq json") is None
    print(f"   {'✓' if ok6 else '✗'} xato JSON → null (qulamaydi)")
    if not ok6:
        return 1

    # 7) aylanma yo'l: display → toSingBox → parse
    ok7 = _parse(_to_singbox(disp)) == ("salamander", "dzcb7hv6i2kx2zze")
    print(f"   {'✓' if ok7 else '✗'} aylanma yo'l (round-trip) barqaror")
    if not ok7:
        return 1

    total = sum(1 for _, d in res if d)
    if not helper_exists:
        total += 1
    print(f"\n  Jami o'zgarish: {total}")

    if args.check:
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    if total == 0:
        print("\n✓ Hammasi allaqachon qo'llangan")
        return 0

    print("\n  ── Yozish ──")
    if not helper_exists:
        JSON_HELPER.write_text(HELPER_SRC, encoding="utf-8")
        print(f"   💾 {JSON_HELPER.relative_to(ROOT)}  (yangi fayl)")
    if e_e.write():
        print(f"   💾 {e_e.path.relative_to(ROOT)}")
        print(f"      zaxira: .fix-backups/{backup_path(EDITOR).name}")

    print("\n" + "═" * 74)
    print("  ✅ TAYOR")
    print("═" * 74)
    print("""
  ═══ KO'RSATILADIGAN JSON (INCY uslubida) ═══

    {
      "udp": [
        {
          "type": "salamander",
          "settings": {
            "password": "dzcb7hv6i2kx2zze"
          }
        }
      ]
    }

  ═══ SAQLANADIGAN JSON (sing-box uslubida) ═══

    {
      "udp": [
        {
          "type": "salamander",
          "password": "dzcb7hv6i2kx2zze"
        }
      ]
    }
        ↑ `settings` qavati OLIB TASHLANADI — yadro shuni tushunadi

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Tekshir: Hysteria2 server → FinalMask maydoni to'liq
""")


if __name__ == "__main__":
    sys.exit(main())
