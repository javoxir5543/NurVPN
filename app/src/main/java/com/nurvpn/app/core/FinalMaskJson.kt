package com.nurvpn.app.core

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
