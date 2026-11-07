package com.nurvpn.app.config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hysteria2 obfs (salamander) konfiguratsiyasi.
 *
 * Bu test INCY (Xray) formati bilan ADASHTIRMASLIKNI oldini oladi:
 *   - Xray:      "finalmask": { "udp": [ { "type": "salamander", ... } ] }
 *   - sing-box:  "obfs": { "type": "salamander", "password": "..." }
 *
 * NurVPN sing-box (libbox) yadrosida ishlaydi, shuning uchun `obfs` TO'G'RI.
 * `finalmask` sing-box'da MAVJUD EMAS — qo'shilsa konfiguratsiya rad etiladi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Hysteria2ObfsTest {

    private val ctx get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun outbound(link: String) = SingBoxConfig.outboundFor(link, ctx)

    @Test
    fun `obfs - salamander to'g'ri JSON chiqaradi`() {
        val o = outbound("hysteria2://pass@h.com:443?obfs=salamander&obfs-password=secret123")
        assertTrue("obfs bo'lishi kerak", o.has("obfs"))
        val obfs = o.getJSONObject("obfs")
        assertEquals("salamander", obfs.getString("type"))
        assertEquals("secret123", obfs.getString("password"))
    }

    @Test
    fun `obfs - finalmask ISHLATILMAYDI (sing-box uni bilmaydi)`() {
        val o = outbound("hysteria2://pass@h.com:443?obfs=salamander&obfs-password=x")
        assertFalse(
            "⚠️ XATO: 'finalmask' Xray/INCY formati. sing-box (libbox) uni " +
                "tushunmaydi va konfiguratsiyani rad etadi. Faqat 'obfs' ishlatilsin.",
            o.has("finalmask")
        )
        assertFalse(o.toString().contains("finalmask"))
    }

    @Test
    fun `obfs - password bo'sh bo'lsa ham struktura to'g'ri`() {
        val o = outbound("hysteria2://pass@h.com:443?obfs=salamander")
        assertTrue(o.has("obfs"))
        assertEquals("salamander", o.getJSONObject("obfs").getString("type"))
    }

    @Test
    fun `obfs - ko'rsatilmasa maydon qo'shilmaydi`() {
        val o = outbound("hysteria2://pass@h.com:443")
        assertFalse("obfs yo'q bo'lsa maydon qo'shilmasligi kerak", o.has("obfs"))
    }

    @Test
    fun `obfs - none qiymati e'tiborsiz qoldiriladi`() {
        val o = outbound("hysteria2://pass@h.com:443?obfs=none")
        assertFalse("obfs=none -> maydon qo'shilmasligi kerak", o.has("obfs"))
    }

    @Test
    fun `obfs - QUIC protokollarida utls YO'Q (salamander ishlashi uchun)`() {
        val o = outbound("hysteria2://pass@h.com:443?obfs=salamander&obfs-password=x")
        val tls = o.getJSONObject("tls")
        assertFalse(
            "⚠️ utls yoqilsa salamander obfs ishlamaydi (v1.12.0 fix)",
            tls.has("utls")
        )
    }

    @Test
    fun `obfs - baza tuzilma to'g'ri (type, server, password)`() {
        val o = outbound("hysteria2://mypass@h.com:8443?obfs=salamander&obfs-password=x")
        assertEquals("hysteria2", o.getString("type"))
        assertEquals("h.com", o.getString("server"))
        assertEquals(8443, o.getInt("server_port"))
        assertEquals("mypass", o.getString("password"))
    }
}
