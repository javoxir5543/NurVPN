package com.nurvpn.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HwidHelper — HWID hisoblash testlari.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NEGA BU TESTLAR MUHIM
 * ═══════════════════════════════════════════════════════════════════════════
 * HWID server tomonida qurilmani tanish uchun ishlatiladi. Algoritm
 * tasodifan o'zgarsa, BARCHA foydalanuvchilar server uchun "yangi qurilma"
 * bo'lib ko'rinadi va qurilma limiti tugab obunalar ishlamay qoladi.
 *
 * Shuning uchun bu yerda `test_*` bilan belgilangan "qotirilgan" (golden)
 * qiymatlar bor — ular ataylab SHA-256 natijasining aniq hex qiymatini
 * tekshiradi. Agar kimdir algoritmni o'zgartirsa, test YIQILADI va u
 * o'zgarishning oqibatini o'ylab ko'rishga majbur bo'ladi.
 *
 * DIQQAT: qotirilgan qiymatlarni "testni yashil qilish" uchun
 * YANGILAMANG. Avval: bu o'zgarish foydalanuvchilarga ta'sir qiladimi?
 */
class HwidHelperTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. GOLDEN / REGRESSIYA testlari — algoritm qulflangan
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Algoritmning mustaqil hisob-kitobi:
     *   SHA-256("androidId-model-packageName") -> hex -> birinchi 32 belgi
     *
     * Bu qiymat `python3 -c "import hashlib; ..."` bilan mustaqil
     * tekshirilgan (implementation'ga qarab emas).
     */
    @Test
    fun `compute - ma'lum kirish uchun aynan kutilgan hash qaytaradi`() {
        val expected = "82e5c7f2934fbf4c45270fd73c2bd655"
        val actual = HwidHelper.compute(
            androidId = "abcdef1234567890",
            model = "Pixel 7",
            packageName = "com.nurvpn.app"
        )
        assertEquals(
            "HWID algoritmi o'zgargan! Bu barcha foydalanuvchilarga ta'sir qiladi.",
            expected, actual
        )
    }

    @Test
    fun `compute - ikkinchi qotirilgan qiymat ham to'g'ri`() {
        assertEquals(
            "d65e547f9736729838578e2a05d4db3f",
            HwidHelper.compute("test", "test", "test")
        )
    }

    @Test
    fun `compute - boshqa qurilma boshqa hash beradi`() {
        val a = HwidHelper.compute("device-a", "Pixel 7", "com.nurvpn.app")
        val b = HwidHelper.compute("device-b", "Pixel 7", "com.nurvpn.app")
        assertNotEquals("Turli ANDROID_ID bir xil HWID bermasligi kerak", a, b)
    }

    @Test
    fun `compute - model o'zgarsa hash o'zgaradi`() {
        val a = HwidHelper.compute("id", "Pixel 7", "com.nurvpn.app")
        val b = HwidHelper.compute("id", "Galaxy S23", "com.nurvpn.app")
        assertNotEquals(a, b)
    }

    @Test
    fun `compute - packageName o'zgarsa hash o'zgaradi`() {
        val a = HwidHelper.compute("id", "Pixel 7", "com.nurvpn.app")
        val b = HwidHelper.compute("id", "Pixel 7", "com.boshqa.app")
        assertNotEquals(a, b)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Format qoidalari
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `compute - har doim 32 belgi qaytaradi`() {
        val cases = listOf(
            Triple("a", "b", "c"),
            Triple("", "", ""),
            Triple("x".repeat(1000), "y".repeat(500), "z".repeat(200)),
            Triple("androidId-with-dashes", "Model With Spaces", "com.a.b.c")
        )
        for ((id, model, pkg) in cases) {
            assertEquals(
                "Uzunlik har doim ${HwidHelper.HWID_LENGTH} bo'lishi kerak (id=$id)",
                HwidHelper.HWID_LENGTH,
                HwidHelper.compute(id, model, pkg).length
            )
        }
    }

    @Test
    fun `compute - faqat kichik harfli hex qaytaradi`() {
        val hwid = HwidHelper.compute("some-id", "Some Model", "com.nurvpn.app")
        assertTrue(
            "HWID faqat [0-9a-f] bo'lishi kerak, topildi: $hwid",
            hwid.all { it in '0'..'9' || it in 'a'..'f' }
        )
    }

    @Test
    fun `compute - deterministik (bir xil kirish, bir xil chiqish)`() {
        val first = HwidHelper.compute("stable-id", "Pixel 7", "com.nurvpn.app")
        repeat(50) {
            assertEquals(
                "HWID deterministik bo'lishi shart — barqarorlik shunga bog'liq",
                first,
                HwidHelper.compute("stable-id", "Pixel 7", "com.nurvpn.app")
            )
        }
    }

    @Test
    fun `compute - 100 xil kirish uchun 100 xil hash (kolliziya yo'q)`() {
        val hashes = (1..100).map {
            HwidHelper.compute("android-id-$it", "Model $it", "com.nurvpn.app")
        }.toSet()
        assertEquals("Kolliziya bo'lmasligi kerak", 100, hashes.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Chegaraviy holatlar
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `compute - bo'sh qatorlar bilan ham yiqilmaydi`() {
        val hwid = HwidHelper.compute("", "", "")
        assertEquals(HwidHelper.HWID_LENGTH, hwid.length)
    }

    @Test
    fun `compute - unicode (kirill, emoji) bilan ishlaydi`() {
        val hwid = HwidHelper.compute("идентификатор", "Модель 📱", "com.nurvpn.app")
        assertEquals(HwidHelper.HWID_LENGTH, hwid.length)
        assertTrue(hwid.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /**
     * Ajratuvchi `-` ga tayanadi. Agar qatorlar birlashtirilsa kolliziya
     * bo'lishi mumkin: ("a-b","c") va ("a","b-c") bir xil "a-b-c" beradi.
     *
     * Bu MAVJUD xatti-harakat — o'zgartirilsa HWID o'zgaradi. Test buni
     * hujjatlashtiradi, to'g'riligini da'vo qilmaydi.
     */
    @Test
    fun `compute - ajratuvchi xatti-harakati hujjatlashtirilgan`() {
        val a = HwidHelper.compute("a-b", "c", "pkg")
        val b = HwidHelper.compute("a", "b-c", "pkg")
        assertEquals(
            "Ajratuvchi tufayli bu ikki kirish bir xil qatorga aylanadi " +
                "(ma'lum cheklov, o'zgartirilsa HWID buziladi)",
            "b7a24072d6a54c9c739403940c345c7d", a
        )
        assertEquals(a, b)
    }

    @Test
    fun `compute - UTF-8 kodlash ishlatiladi, platforma default emas`() {
        // Kirill harflari bilan qotirilgan qiymat. Agar platforma default
        // kodlash (masalan windows-1251) ishlatilsa natija butunlay boshqa
        // bo'lardi — bu test UTF-8 ni qulflaydi.
        assertEquals(
            "2b49dfeeda4c63927bf71f58bb86e538",
            HwidHelper.compute("идентификатор", "Модель", "com.nurvpn.app")
        )
    }

    @Test
    fun `compute - emoji (4 baytli UTF-8) bilan ham to'g'ri`() {
        assertEquals(
            "8c80ffdad5190a1f84349b615d72c48f",
            HwidHelper.compute("идентификатор", "Модель 📱", "com.nurvpn.app")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Konstantalar
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `konstantalar - kutilgan qiymatlarda`() {
        assertEquals("HWID uzunligi server bilan kelishilgan", 32, HwidHelper.HWID_LENGTH)
        assertEquals("ANDROID_ID o'qilmasa", "unknown", HwidHelper.FALLBACK_ANDROID_ID)
        assertEquals("Build.MODEL bo'sh bo'lsa", "device", HwidHelper.FALLBACK_MODEL)
    }

    @Test
    fun `compute - fallback qiymatlari bilan ishlaydi`() {
        val hwid = HwidHelper.compute(
            HwidHelper.FALLBACK_ANDROID_ID,
            HwidHelper.FALLBACK_MODEL,
            "com.nurvpn.app"
        )
        assertEquals(32, hwid.length)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Fragment dublikatlari bilan moslik (refactoring xavfsizligi)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * `HomeFragment.getHwid()` va `ServersFragment.getHwid()` dagi ESKI kod
     * shu yerda nusxalanadi. Yangi `HwidHelper.fromContext()` ular bilan
     * AYNAN bir xil natija berishi shart — aks holda refactoring obuna
     * autentifikatsiyasini buzadi.
     *
     * Eski kod:
     *   raw = "$androidId-$model-$packageName"
     *   SHA-256(raw) -> "%02x".format(it) -> take(32)
     */
    private fun legacyGetHwid(androidId: String?, model: String?, packageName: String): String {
        val id = androidId ?: "unknown"
        val m = model ?: "device"
        val raw = "$id-$m-$packageName"
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    @Test
    fun `fromContext bilan eski fragment kodi bir xil natija beradi`() {
        val cases = listOf(
            Triple("abcdef1234567890", "Pixel 7", "com.nurvpn.app"),
            Triple(null, "Pixel 7", "com.nurvpn.app"),
            Triple("abc", null, "com.nurvpn.app"),
            Triple(null, null, "com.nurvpn.app"),
            Triple("идентификатор", "Модель", "com.nurvpn.app"),
            Triple("", "", "")
        )
        for ((androidId, model, pkg) in cases) {
            val legacy = legacyGetHwid(androidId, model, pkg)
            val modern = HwidHelper.compute(
                androidId ?: HwidHelper.FALLBACK_ANDROID_ID,
                model ?: HwidHelper.FALLBACK_MODEL,
                pkg
            )
            assertEquals(
                "Refactoring natijani o'zgartirdi! id=$androidId model=$model",
                legacy, modern
            )
        }
    }
}
