package com.nurvpn.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ C5 REGRESSIYA TESTI — Kill-switch mantiqi.
 *
 * MUAMMO (audit + qurilmada tasdiqlangan):
 *   Redmi Note 10S, Android 13:
 *       always_on_vpn_app      = null   ← ★ Android BERMAYDI!
 *       always_on_vpn_lockdown = 1      ← himoya BOR
 *
 *   Ilgari `isFullyEnabled = isAlwaysOn && isLockdown` edi → `false` →
 *   foydalanuvchi "himoya YO'Q" deb o'ylardi, aslida LOCKDOWN ISHLAYAPTI.
 *
 * YECHIM: `isFullyEnabled() = isLockdown()` — `always_on_vpn_app` ga
 *   TAYANMAYMIZ (u ishonchsiz).
 *
 * Bu test `Settings.Secure` o'qimasdan, faqat MANTIQNI qulflaydi
 * (Robolectric talab qilinmaydi — sof funksiya).
 */
class KillSwitchLogicTest {

    /**
     * [KillSwitch] mantig'ining toza nusxasi — Android API'siz.
     * Haqiqiy kod bilan bir xil bo'lishi SHART.
     */
    private fun isLockdown(raw: String?): Boolean =
        raw?.toIntOrNull() == 1

    private fun isThisAppSelected(raw: String?, pkg: String): Boolean =
        !raw.isNullOrEmpty() && raw == pkg

    private fun isFullyEnabled(rawLockdown: String?): Boolean =
        isLockdown(rawLockdown)

    // ═══════════════════════════════════════════════════════════════
    // ASOSIY REGRESSIYA: Android 13 `always_on_vpn_app = null`
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `lockdown=1 va app=null — himoya BOR deb hisoblanadi`() {
        // ★ Bu qurilmada o'lchangan HAQIQIY holat.
        // Ilgari bu test YIQILARDI (`isAlwaysOn && isLockdown` → false).
        assertTrue(
            "Android 13 da app=null bo'lsa ham lockdown=1 himoyani bildiradi",
            isFullyEnabled("1")
        )
    }

    @Test
    fun `lockdown=0 — himoya YO'Q`() {
        assertFalse(isFullyEnabled("0"))
    }

    @Test
    fun `lockdown=null — himoya YO'Q (xavfsiz tomon)`() {
        assertFalse(isFullyEnabled(null))
    }

    @Test
    fun `lockdown noto'g'ri qiymat — himoya YO'Q`() {
        for (bad in listOf("", "abc", "2", "-1", "true")) {
            assertFalse("lockdown=$bad → false bo'lishi kerak", isFullyEnabled(bad))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // `isThisAppSelected` — always_on_vpn_app ISHONCHSIZ
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `isThisAppSelected — oz paketi`() {
        assertTrue(isThisAppSelected("com.nurvpn.app", "com.nurvpn.app"))
    }

    @Test
    fun `isThisAppSelected — boshqa ilova`() {
        assertFalse(isThisAppSelected("com.other.vpn", "com.nurvpn.app"))
    }

    @Test
    fun `isThisAppSelected — null (Android 13)`() {
        assertFalse(isThisAppSelected(null, "com.nurvpn.app"))
    }

    @Test
    fun `isThisAppSelected — bosh satr`() {
        assertFalse(isThisAppSelected("", "com.nurvpn.app"))
    }

    // ═══════════════════════════════════════════════════════════════
    // TO'LIQ JADVAL — regressiya qulfi
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `holatlar jadvali`() {
        data class Case(
            val app: String?,
            val lockdown: String?,
            val expected: Boolean,
            val note: String
        )

        val cases = listOf(
            Case(null, "0", false, "hech narsa yo'q"),
            Case(null, "1", true, "★ Android 13 REAL holati"),
            Case("com.nurvpn.app", "1", true, "to'liq himoya"),
            Case("com.nurvpn.app", "0", false, "always-on bor, lockdown yo'q"),
            Case("com.other.vpn", "1", true, "boshqa ilova + lockdown"),
        )

        for (c in cases) {
            assertEquals(
                "${c.note} (app=${c.app}, lockdown=${c.lockdown})",
                c.expected,
                isFullyEnabled(c.lockdown)
            )
        }
    }

    @Test
    fun `State enum mantigi`() {
        // state(): ON = lockdown, PARTIAL = o'z paketi, OFF = hech narsa
        fun state(app: String?, lockdown: String?, pkg: String) = when {
            isLockdown(lockdown) -> "ON"
            isThisAppSelected(app, pkg) -> "PARTIAL"
            else -> "OFF"
        }
        assertEquals("ON", state(null, "1", "com.nurvpn.app"))
        assertEquals("PARTIAL", state("com.nurvpn.app", "0", "com.nurvpn.app"))
        assertEquals("OFF", state(null, "0", "com.nurvpn.app"))
        assertEquals("OFF", state("com.other.vpn", "0", "com.nurvpn.app"))
    }
}
