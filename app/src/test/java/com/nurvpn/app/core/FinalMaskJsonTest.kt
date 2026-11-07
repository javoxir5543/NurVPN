package com.nurvpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  ★ FIX155 — FinalMaskJson: ko'rsatish va format o'girish
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ═══ NEGA BU TESTLAR MUHIM ═══
 *
 *  Hysteria2 obfuscator JSON ikki xil formatda bo'ladi:
 *
 *      sing-box (BIZNING YADRO) → {"type":"salamander","password":"..."}
 *      XCORE / INCY             → {"type":"salamander",
 *                                  "settings":{"password":"..."}}
 *
 *  Foydalanuvchi INCY formatini ko'radi, lekin yadro sing-box
 *  formatini talab qiladi. O'girish buzilsa:
 *
 *      • "missing obfs password" → ULANISH ISHLAMAYDI
 *      • yoki parol jimgina yo'qoladi
 *
 *  ⚠️ `org.json` JVM testda ishlaydi (`testImplementation` da bor).
 *     `android.util.Log` ishlatilmaydi, shuning uchun Robolectric
 *     SHART EMAS.
 */
class FinalMaskJsonTest {

    private val pass = "dzcb7hv6i2kx2zze"

    // ═══════════════════════════════════════════════════════════════════
    //  display() — INCY uslubidagi JSON
    // ═══════════════════════════════════════════════════════════════════

    /** ★ ASOSIY: parol JSON ICHIDA bo'lishi shart (bu bug edi). */
    @Test
    fun `display parolni o'z ichiga oladi`() {
        val out = FinalMaskJson.display("salamander", pass)
        assertTrue("Parol JSON da bo'lishi kerak:\n$out", pass in out)
        assertTrue("`settings` qavati bo'lishi kerak:\n$out",
            "\"settings\"" in out)
        assertTrue("`type` bo'lishi kerak:\n$out", "salamander" in out)
    }

    /**
     * Chiroyli formatlangan — HAR BIR daraja yangi qatorda.
     *
     * ⚠️ `org.json.toString(2)` ATAYLAB ishlatilmaydi: u faqat
     *    birinchi darajani formatlaydi va ichma-ich obyektlarni
     *    bitta qatorga tiqadi. Bu test o'sha xatoni ushlaydi.
     */
    @Test
    fun `display chiroyli formatlangan`() {
        val out = FinalMaskJson.display("salamander", pass)
        val lines = out.lines()

        assertTrue("Ko'p qatorli bo'lishi kerak:\n$out", lines.size >= 9)
        assertTrue("2 bo'shliqli indent bo'lishi kerak:\n$out",
            "\n  \"udp\"" in out)
        assertTrue("`settings` alohida qatorda bo'lishi kerak " +
            "(ichma-ich tiqilmasin):\n$out",
            "\n      \"settings\": {" in out)
        assertTrue("`password` alohida qatorda bo'lishi kerak:\n$out",
            "\n        \"password\":" in out)
        assertTrue("Yopiluvchi qavslar chiroyli:\n$out", out.trimEnd().endsWith("}"))
    }

    /**
     * ★ KALITLAR TARTIBI — `type` AVVAL, `settings` KEYIN.
     *
     * `org.json` kalitlarni alifbo bo'yicha saralaydi va `settings`
     * `type` dan oldin tushardi — INCY'dagidan farq. Shuning uchun
     * `LinkedHashMap` ishlatiladi.
     */
    @Test
    fun `display kalitlar tartibi INCY kabi`() {
        val out = FinalMaskJson.display("salamander", pass)
        val iType = out.indexOf("\"type\"")
        val iSettings = out.indexOf("\"settings\"")
        assertTrue("`type` bo'lishi kerak:\n$out", iType >= 0)
        assertTrue("`settings` bo'lishi kerak:\n$out", iSettings >= 0)
        assertTrue("`type` `settings` dan OLDIN kelishi kerak " +
            "(INCY tartibi):\n$out", iType < iSettings)
    }

    /** Tur bo'sh bo'lsa — bo'sh satr (maydon ko'rsatilmaydi). */
    @Test
    fun `display tur bo'sh bo'lsa bo'sh satr`() {
        assertEquals("", FinalMaskJson.display("", pass))
    }

    /** Parol bo'sh bo'lsa ham JSON yasaladi (type bor). */
    @Test
    fun `display parolsiz ham ishlaydi`() {
        val out = FinalMaskJson.display("salamander", "")
        assertTrue("type bo'lishi kerak:\n$out", "salamander" in out)
        assertTrue("settings bo'lishi kerak:\n$out", "\"settings\"" in out)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  parse() — IKKALA formatni o'qish
    // ═══════════════════════════════════════════════════════════════════

    /** XCORE (INCY) formati — `settings.password`. */
    @Test
    fun `parse XCORE formatini o'qiydi`() {
        val r = FinalMaskJson.parse(FinalMaskJson.display("salamander", pass))
        assertEquals("salamander", r?.first)
        assertEquals(pass, r?.second)
    }

    /** sing-box (yassi) formati — `password`. */
    @Test
    fun `parse sing-box yassi formatini o'qiydi`() {
        val flat = """{"udp":[{"type":"salamander","password":"$pass"}]}"""
        val r = FinalMaskJson.parse(flat)
        assertEquals("salamander", r?.first)
        assertEquals(pass, r?.second)
    }

    /** ★ XATO JSON — null qaytaradi, QULAMAYDI. */
    @Test
    fun `parse xato JSON da null qaytaradi`() {
        assertNull(FinalMaskJson.parse("{buzuq json"))
        assertNull(FinalMaskJson.parse("bu JSON emas"))
        assertNull(FinalMaskJson.parse(""))
        assertNull(FinalMaskJson.parse("   "))
    }

    /** `type` yo'q bo'lsa — null (foydasiz JSON). */
    @Test
    fun `parse type bo'lmasa null`() {
        assertNull(FinalMaskJson.parse("""{"udp":[{"password":"x"}]}"""))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  toSingBox() — YADRO uchun o'girish
    // ═══════════════════════════════════════════════════════════════════

    /** ★★ ENG MUHIM: `settings` qavati OLIB TASHLANISHI kerak. */
    @Test
    fun `toSingBox settings qavatini olib tashlaydi`() {
        val sb = FinalMaskJson.toSingBox(FinalMaskJson.display("salamander", pass))

        assertFalse(
            "`settings` qavati qolmasligi kerak — sing-box uni " +
                "tushunmaydi va \"missing obfs password\" beradi:\n$sb",
            "\"settings\"" in sb)
        assertTrue("`type` bo'lishi kerak:\n$sb", "salamander" in sb)
        assertTrue("parol YUQORIGA ko'chirilishi kerak:\n$sb", pass in sb)
    }

    /** ★★ AYLANMA YO'L: display → toSingBox → parse barqaror. */
    @Test
    fun `aylanma yo'l barqaror`() {
        val disp = FinalMaskJson.display("salamander", pass)
        val sb = FinalMaskJson.toSingBox(disp)
        val back = FinalMaskJson.parse(sb)
        assertEquals("Tur saqlanadi", "salamander", back?.first)
        assertEquals("Parol saqlanadi", pass, back?.second)
    }

    /** Xato JSON — o'zgartirilmasdan qaytariladi (yo'qotmaymiz). */
    @Test
    fun `toSingBox xato JSON ni o'zgartirmaydi`() {
        val bad = "{buzuq"
        assertEquals(bad, FinalMaskJson.toSingBox(bad))
    }

    /** ★★ YADRO FORMATI: `password` `type` bilan BIR QATAVDA. */
    @Test
    fun `toSingBox natijasi yadro formatiga mos`() {
        val sb = FinalMaskJson.toSingBox(FinalMaskJson.display("salamander", pass))
        // sing-box Hysteria2Obfs: {type, password} — yassi
        val r = FinalMaskJson.parse(sb)
        assertEquals("salamander", r?.first)
        assertEquals(pass, r?.second)
        // `settings` yo'qligini yana bir bor tasdiqlaymiz
        assertFalse("settings bo'lmasin", "settings" in sb)
    }
}
