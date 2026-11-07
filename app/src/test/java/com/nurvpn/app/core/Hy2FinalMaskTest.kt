package com.nurvpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  ★ FIX153 — Hysteria2 FinalMask aylanma yo'li (round-trip)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  NEGA KERAK:
 *      FinalMask JSON sing-box ga YOZILMAYDI (libbox'da `final_mask`
 *      kaliti yo'q), lekin foydalanuvchi kiritgan matn YO'QOLMASLIGI
 *      kerak. U link ichida saqlanadi:
 *
 *          ServerConfig.finalMask  →  link: finalmask=...
 *          link: finalmask=...     →  ServerConfig.finalMask
 *
 *      Bu test o'sha aylanma yo'lni tekshiradi. Buzilsa — foydalanuvchi
 *      kiritgan JSON jimgina yo'qoladi (eng yomon xato turi).
 *
 *  ⚠️ NEGA ROBOELECTRIC:
 *     `LinkBuilder.parse()` → `ServerLinkParser` → `android.util.Log.e()`
 *     ni chaqiradi. Oddiy JVM testda bu "not mocked" xatosi beradi
 *     (`android.util.Log` stub'i `throw RuntimeException` qiladi).
 *     Robolectric haqiqiy implementatsiyani beradi —
 *     `ServerLinkParserTest` ham xuddi shunday qiladi.
 *
 *  ⚠️ `SingBoxConfig.buildFullConfig()` JVM testda chaqirilmaydi —
 *     u `android.content.Context` talab qiladi. Shuning uchun
 *     "JSON ga yozilmasligi" shartnomasi `LinkBuilder` darajasida
 *     tekshiriladi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Hy2FinalMaskTest {

    private fun hy2Link(finalMask: String, obfs: String = "salamander") =
        "hysteria2://pass123@example.com:443" +
            "?sni=example.com" +
            "&obfs=$obfs" +
            "&obfs-password=secret" +
            (if (finalMask.isNotEmpty()) "&finalmask=$finalMask" else "") +
            "#TestHY2"

    /**
     * ═══ ASOSIY TEST: link → cfg → link ═══
     *
     * Eng muhim yo'nalish: linkdan o'qib, keyin qayta yozganda
     * finalmask SAQLANIB QOLADI.
     */
    @Test
    fun `finalmask linkdan o'qiladi va qayta yoziladi`() {
        val json = "{\"udp\":[{\"type\":\"salamander\"}]}"
        val cfg = LinkBuilder.parse(hy2Link(json))

        assertEquals("Protokol", Protocol.HYSTERIA2, cfg.protocol)
        assertEquals("finalMask o'qildi", json, cfg.finalMask)

        // Qayta yozish — saqlanib qolishi kerak
        val rebuilt = LinkBuilder.build(cfg)
        assertTrue("Qayta yozilgan linkda finalmask bor:\n$rebuilt",
            "finalmask=" in rebuilt)

        // Ikkinchi aylanish — barqaror bo'lishi kerak (idempotent)
        val cfg2 = LinkBuilder.parse(rebuilt)
        assertEquals("2-aylanishda ham saqlanadi", json, cfg2.finalMask)
    }

    /** finalmask bo'lmasa — maydon bo'sh qoladi, crash yo'q. */
    @Test
    fun `finalmask yo'q linkda bo'sh qoladi`() {
        val cfg = LinkBuilder.parse(hy2Link(""))
        assertEquals("Bo'sh finalMask", "", cfg.finalMask)

        val rebuilt = LinkBuilder.build(cfg)
        assertFalse("Keraksiz finalmask= qo'shilmasin:\n$rebuilt",
            "finalmask=" in rebuilt)
    }

    /**
     * ═══ ENG MUHIM XAVFSIZLIK TESTI ═══
     *
     * FinalMask **sing-box JSON kaliti sifatida ishlatilmasligi** kerak.
     * libbox'da `final_mask` yo'q — noto'g'ri kalit Hysteria2
     * ulanishini buzadi.
     *
     * Tekshiruv: linkni qayta yozganimizda FAQAT `finalmask=`
     * (link parametri) chiqadi, JSON kaliti `final_mask` esa
     * hech qachon paydo bo'lmaydi.
     */
    @Test
    fun `finalmask JSON kaliti sifatida ishlatilmaydi`() {
        val json = "{\"udp\":[{\"type\":\"salamander\"}]}"
        val cfg = LinkBuilder.parse(hy2Link(json))
        val rebuilt = LinkBuilder.build(cfg)

        assertFalse(
            "`final_mask` (JSON/link kaliti) ISHLATILMASLIGI kerak — " +
                "libbox uni tushunmaydi:\n$rebuilt",
            "final_mask" in rebuilt)
        assertTrue(
            "`finalmask=` (link parametri) bo'lishi kerak:\n$rebuilt",
            "finalmask=" in rebuilt)
    }

    /** Hysteria2 ning ASOSIY sozlamalari FIX153 dan keyin ham ishlaydi. */
    @Test
    fun `hysteria2 asosiy sozlamalar buzilmagan`() {
        val cfg = LinkBuilder.parse(hy2Link(""))

        assertEquals("host", "example.com", cfg.host)
        assertEquals("port", 443, cfg.port)
        assertEquals("password", "pass123", cfg.password)
        // ⚠️ `obfs` — HAQIQIY ishlaydigan obfuscator (salamanderConn)
        assertEquals("obfs turi", "salamander", cfg.obfs)
        assertEquals("obfs paroli", "secret", cfg.obfsPassword)
        assertEquals("sni", "example.com", cfg.sni)
    }

    /** TUIC/VMess — FinalMask ularga TEGMASLIGI kerak. */
    @Test
    fun `finalmask boshqa protokollarga ta'sir qilmaydi`() {
        val tuic = LinkBuilder.parse(
            "tuic://uuid-1:pass@h.example.com:443?congestion_control=bbr#TUIC")
        assertEquals("TUIC finalMask bo'sh", "", tuic.finalMask)
        assertEquals("TUIC protokoli", Protocol.TUIC, tuic.protocol)

        val rebuilt = LinkBuilder.build(tuic)
        assertFalse("TUIC linkda finalmask bo'lmasin", "finalmask=" in rebuilt)
    }
}
