package com.nurvpn.app.core

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

    /**
     * ★ FIX151-ANNOUNCE: `announce` uchun maksimal uzunlik.
     *
     * ═══ NEGA OSHIRILDI (220 → 2000) ═══
     *
     * Avval 220 belgi edi — provayder xabari O'RTASIDAN "…" bilan
     * kesilardi va INCY'dagidek to'liq o'qib bo'lmasdi.
     *
     * ⚠️ 2000 — "cheksiz" emas, ATAYLAB chegara:
     *    `announce` karta ichida chiziladi. 50 000 belgilik xabar
     *    kelsa karta balandligi bir necha ekranga cho'zilib,
     *    RecyclerView scroll'i sekinlashadi. 2000 belgi — eng uzun
     *    haqiqiy xabardan ancha katta, lekin xavfsiz.
     *
     * ponytail: qattiq chegara. Agar provayder undan uzun xabar
     * yuborsa — `announceLines()` bilan "ko'proq" tugmasi qo'shiladi.
     */
    const val MAX_ANNOUNCE = 2000

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
     * ★ FIX151-ANNOUNCE: `announce` — KO'P QATORLI e'lon.
     *
     * ═══ NEGA QAYTA YOZILDI ═══
     *
     * Avvalgi kod `\n` ni BO'SHLIQQA aylantirardi:
     *
     *     "Yangilanish bor\nServerlar qo'shildi"
     *         ↓
     *     "Yangilanish bor Serverlar qo'shildi"   ← qatorlar YO'QOLDI
     *
     * Natijada xabar bitta uzun qatorga aylanib, karta ichida
     * kesilardi — INCY'dagidek "to'liq xabar" ko'rinmasdi.
     *
     * Endi:
     *   • `\n` va `\r` SAQLANADI (Kotlin TextView ularni qator
     *     sifatida chizadi)
     *   • Escaped "\n" (ikki belgi: backslash + n) ham haqiqiy
     *     qatorga aylantiriladi — provayderlar shunday yuboradi
     *   • 3+ ketma-ket bo'sh qator → 1 ta (xabar siyrak cho'zilmasin)
     *   • Qator oxiridagi ortiqcha bo'shliq olib tashlanadi
     *   • `MAX_ANNOUNCE` dan uzun bo'lsa "…" (xavfsizlik chegarasi)
     */
    fun cleanAnnounce(raw: String?): String? {
        val s = decode(raw) ?: return null

        // Escaped "\n" (backslash + n) → haqiqiy qator belgisi
        var t = s.replace("\\r\\n", "\n").replace("\\n", "\n")
            .replace("\r\n", "\n").replace("\r", "\n")

        // Har bir qatorni tozalaymiz, bo'sh qatorlarni tashlab ketamiz.
        // `out` har bir qator oldidan '\n' qo'shadi, shuning uchun bo'sh
        // qatorni qo'shsak — ikkilangan ajratgich (bo'sh qator) paydo
        // bo'lardi. Provayder xabarlarida bo'sh qator mazmun tashimaydi.
        val lines = t.split("\n").map { it.trim() }
        val out = StringBuilder()
        for (line in lines) {
            if (line.isEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        t = out.toString().trim()

        // Ketma-ket bo'shliqlarni bittaga (qator ichida)
        t = t.split("\n").joinToString("\n") { l ->
            l.replace(Regex("""[ \t]{2,}"""), " ")
        }

        if (t.isEmpty()) return null
        if (t.length > MAX_ANNOUNCE) {
            t = t.take(MAX_ANNOUNCE - 1).trimEnd() + "…"
        }
        return t
    }

    /**
     * ★ FIX151-ANNOUNCE: e'londagi qatorlar soni (UI uchun yordamchi).
     *
     * Karta balandligini oldindan hisoblash yoki "juda uzun xabar"
     * holatida qisqartirish kerak bo'lsa ishlatiladi.
     */
    fun announceLines(announce: String): Int =
        if (announce.isBlank()) 0 else announce.count { it == '\n' } + 1
}
