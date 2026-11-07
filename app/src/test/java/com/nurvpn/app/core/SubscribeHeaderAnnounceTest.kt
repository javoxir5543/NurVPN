package com.nurvpn.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ FIX151-ANNOUNCE: `SubscribeHeader.cleanAnnounce()` testlari.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NEGA BU TESTLAR MUHIM
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Provayder e'loni (`announce` header) foydalanuvchi uchun muhim xabar:
 * "obuna tugayapti", "serverlar yangilandi", "to'lov qiling" va h.k.
 *
 * Eski kod `\n` ni bo'shliqqa aylantirardi va matnni 220 belgida
 * kesardi — natijada xabar yarim yo'lda uzilib qolardi.
 *
 * Bu testlar quyidagini KAFOLATLAYDI:
 *   • Qatorlar saqlanadi (ko'p qatorli xabar buzilmaydi)
 *   • Escaped `\n` (backslash+n) haqiqiy qatorga aylanadi
 *   • Uzun xabar 220 emas, 2000 belgigacha saqlanadi
 *   • Bo'sh/`null` kirish xavfsiz qayta ishlanadi
 */
class SubscribeHeaderAnnounceTest {

    // ═══════════════════════════════════════════════════════════════════
    //  1) Qatorlar SAQLANISHI (asosiy tuzatish)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `haqiqiy qatorlar saqlanadi`() {
        val r = SubscribeHeader.cleanAnnounce("Birinchi qator\nIkkinchi qator")
        assertEquals("Birinchi qator\nIkkinchi qator", r)
    }

    @Test
    fun `escaped backslash-n haqiqiy qatorga aylanadi`() {
        // Provayderlar ko'pincha "\\n" (ikki belgi) yuboradi
        val r = SubscribeHeader.cleanAnnounce("Birinchi\\nIkkinchi")
        assertEquals("Birinchi\nIkkinchi", r)
    }

    @Test
    fun `escaped backslash-r-backslash-n qatorga aylanadi`() {
        val r = SubscribeHeader.cleanAnnounce("A\\r\\nB")
        assertEquals("A\nB", r)
    }

    @Test
    fun `windows uslubidagi qatorlar normallashtiriladi`() {
        val r = SubscribeHeader.cleanAnnounce("A\r\nB")
        assertEquals("A\nB", r)
    }

    @Test
    fun `announceLines qator sonini togri hisoblaydi`() {
        assertEquals(0, SubscribeHeader.announceLines(""))
        assertEquals(1, SubscribeHeader.announceLines("bir qator"))
        assertEquals(3, SubscribeHeader.announceLines("a\nb\nc"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2) UZUN XABAR SAQLANISHI (220 → 2000)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `220 belgidan uzun xabar kesilmaydi`() {
        // 500 belgi — eski kod buni 220 da "…" bilan kesardi
        val uzun = "X".repeat(500)
        val r = SubscribeHeader.cleanAnnounce(uzun)
        assertEquals(500, r?.length)
    }

    @Test
    fun `2000 belgidan uzun xabar xavfsiz chegaralanadi`() {
        val juda = "Y".repeat(5000)
        val r = SubscribeHeader.cleanAnnounce(juda)
        assertEquals(SubscribeHeader.MAX_ANNOUNCE, r?.length)
        assertTrue("kesilgani ko'rinishi kerak", r!!.endsWith("…"))
    }

    @Test
    fun `limit qiymati 2000 ga teng`() {
        // Qo'riqchi: kimdir chegarani tasodifan kichraytirmasin
        assertEquals(2000, SubscribeHeader.MAX_ANNOUNCE)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3) TOZALASH — bo'shliq va bo'sh qatorlar
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `qator ichidagi ortiqcha bosliqlar siqiladi`() {
        val r = SubscribeHeader.cleanAnnounce("A     B")
        assertEquals("A B", r)
    }

    @Test
    fun `ketma-ket bosliq qatorlar bittaga tushadi`() {
        val r = SubscribeHeader.cleanAnnounce("A\n\n\n\nB")
        assertEquals("A\nB", r)
    }

    @Test
    fun `qator chetidagi bosliqlar olib tashlanadi`() {
        val r = SubscribeHeader.cleanAnnounce("   A   \n   B   ")
        assertEquals("A\nB", r)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4) BO'SH / NULL — xavfsizlik
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `null kirish null qaytaradi`() {
        assertNull(SubscribeHeader.cleanAnnounce(null))
    }

    @Test
    fun `bosliq kirish null qaytaradi`() {
        assertNull(SubscribeHeader.cleanAnnounce("   "))
        assertNull(SubscribeHeader.cleanAnnounce("\n\n\n"))
    }

    @Test
    fun `base64 prefiksisiz oddiy matn ozgarmaydi`() {
        // ⚠️ `base64:` yo'lini unit-testda sinab bo'lmaydi:
        //    `android.util.Base64` — Android SDK klassi, JVM testda
        //    stub bo'lib qoladi (RuntimeException). Uni faqat qurilmada
        //    yoki instrumentation testda sinash mumkin.
        val r = SubscribeHeader.cleanAnnounce("Salom dunyo")
        assertEquals("Salom dunyo", r)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5) HAQIQIY XABAR NAMUNASI (regressiya)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `haqiqiy provayder xabari toliq saqlanadi`() {
        val xabar = "Обновление серверов\\n" +
            "Добавлены новые локации: Германия, Франция\\n" +
            "Продлите подписку до 10 января"
        val r = SubscribeHeader.cleanAnnounce(xabar)
        assertEquals(3, SubscribeHeader.announceLines(r!!))
        assertTrue(r.contains("Обновление серверов"))
        assertTrue(r.contains("Продлите подписку"))
    }
}
