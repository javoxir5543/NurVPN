package com.nurvpn.app

import org.junit.Assert.*
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  v1.12.2 — TASDIQLANGAN FAKTLAR UCHUN REGRESSIYA TESTLARI
 *  (BUILD.VERSION + restart cooldown + scroll teskari mantiq)
 * ═══════════════════════════════════════════════════════════════════════════
 */
class V112FixesTest {

    private fun src(rel: String): String {
        for (f in listOf(java.io.File(rel), java.io.File("../$rel"))) {
            if (f.exists()) return f.readText()
        }
        fail("Fayl topilmadi: $rel")
        return ""
    }

    // ═══════════════ BUG #2: VERSION BUMP ═══════════════

    @Test
    fun `bug2 - versionName 1_12_2 ga kotarilgan`() {
        val g = src("app/build.gradle")
        assertTrue(
            "⚠️ BUG#2: versionName 1.12.2 bo'lishi shart. " +
                "Telefonda eski versiya ishlayapti — yangi APK " +
                "o'rnatilmaydi. Topilgan: " +
                Regex("versionName\\s+\"([^\"]+)\"").find(g)?.value,
            g.contains("versionName \"1.12.2\"")
        )
    }

    @Test
    fun `bug2 - versionCode 24 ga kotarilgan (19 emas)`() {
        val g = src("app/build.gradle")
        val m = Regex("versionCode\\s+(\\d+)").find(g)
        assertNotNull("versionCode topilmadi", m)
        assertEquals(
            "⚠️ BUG#2: versionCode 24 bo'lishi shart — aks holda Android " +
                "APK'ni yangilash deb hisoblamaydi (VERSION_DOWNGRADE)",
            "24", m!!.groupValues[1]
        )
    }

    @Test
    fun `bug2 - versionCode va versionName izchil`() {
        val g = src("app/build.gradle")
        val code = Regex("versionCode\\s+(\\d+)").find(g)!!.groupValues[1].toInt()
        val name = Regex("versionName\\s+\"([^\"]+)\"").find(g)!!
            .groupValues[1]
        assertEquals("versionName 1.12.2 bo'lishi kerak", "1.12.2", name)
        assertTrue("versionCode 24 bo'lishi kerak, topildi: $code",
            code == 24)
    }

    @Test
    fun `bug2 - Manifest'da qolda versiya yoq (ziddiyat bolmasin)`() {
        val m = src("app/src/main/AndroidManifest.xml")
        assertFalse("Manifest'da versionCode bo'lmasligi kerak " +
            "(Gradle boshqaradi)", m.contains("android:versionCode="))
        assertFalse("Manifest'da versionName bo'lmasligi kerak",
            m.contains("android:versionName="))
    }

    @Test
    fun `bug2 - URL yashirish butunlay olib tashlangan`() {
        val home = src("app/src/main/java/com/nurvpn/app/ui/home/HomeFragment.kt")
        assertFalse("encrypted_url_hidden qaytgan!",
            home.contains("encrypted_url_hidden"))
        assertTrue("setMessage(url) bo'lishi kerak — URL ko'rinadi",
            home.contains(".setMessage(url)"))
    }

    // ═══════════════ BUG #3: RESTART COOLDOWN ═══════════════

    @Test
    fun `bug3 - restartVpn da cooldown konstantasi bor`() {
        val m = src("app/src/main/java/com/nurvpn/app/ui/MainActivity.kt")
        assertTrue("⚠️ BUG#3: RESTART_COOLDOWN_MS yo'q",
            m.contains("RESTART_COOLDOWN_MS"))
        val v = Regex("RESTART_COOLDOWN_MS\\s*=\\s*([\\d_]+)L").find(m)
        assertNotNull("cooldown qiymati o'qilmadi", v)
        val ms = v!!.groupValues[1].replace("_", "").toLong()
        assertTrue(
            "⚠️ BUG#3: cooldown 2500ms restart kechikishidan KATTA " +
                "bo'lishi shart, aks holda loop davom etadi. Topildi: ${ms}ms",
            ms > 2500L
        )
    }

    @Test
    fun `bug3 - cooldown 5000ms (logcat sikli 2-3s edi)`() {
        val m = src("app/src/main/java/com/nurvpn/app/ui/MainActivity.kt")
        val v = Regex("RESTART_COOLDOWN_MS\\s*=\\s*([\\d_]+)L").find(m)
        assertEquals("Cooldown 5000ms bo'lishi kerak",
            "5000", v!!.groupValues[1].replace("_", ""))
    }

    @Test
    fun `bug3 - cooldown tekshiruvi mavjud (lastRestartAt)`() {
        val m = src("app/src/main/java/com/nurvpn/app/ui/MainActivity.kt")
        assertTrue("lastRestartAt yo'q", m.contains("lastRestartAt"))
        assertTrue("cooldown log yozuvi yo'q",
            m.contains("COOLDOWN ("))
    }

    @Test
    fun `bug3 - kechiktirilgan sorov yoqolmaydi (pending)`() {
        val m = src("app/src/main/java/com/nurvpn/app/ui/MainActivity.kt")
        assertTrue(
            "⚠️ BUG#3: cooldown ichida kelgan so'rov yo'qolmasligi kerak " +
                "— pendingRestartReason orqali eslab qolinadi",
            m.contains("pendingRestartReason"))
    }

    @Test
    fun `bug3 - START_NOT_STICKY saqlangan (ozgartirilmasin)`() {
        val s = src("app/src/main/java/com/nurvpn/app/service/NurVpnService.kt")
        val n = Regex("START_NOT_STICKY").findAll(s).count()
        assertTrue("START_NOT_STICKY kamida 5 joyda bo'lishi kerak, " +
            "topildi: $n", n >= 5)
        // Yalang'och START_STICKY bo'lmasin
        val bare = s.replace("START_NOT_STICKY", "")
        assertFalse("START_STICKY qaytgan!", bare.contains("START_STICKY"))
    }

    @Test
    fun `bug3 - connectLocked himoyasi saqlangan`() {
        val s = src("app/src/main/java/com/nurvpn/app/service/NurVpnService.kt")
        assertTrue("connectLocked yo'q", s.contains("connectLocked"))
        assertTrue("crash guard yo'q", s.contains("installCrashGuard"))
    }

}
