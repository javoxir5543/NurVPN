package com.nurvpn.app.parser

import com.nurvpn.app.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64 as JBase64

/**
 * ServerLinkParser testlari.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NEGA ROBOELECTRIC
 * ═══════════════════════════════════════════════════════════════════════════
 * Parser `android.net.Uri.decode()` va `android.util.Base64` ishlatadi.
 * Oddiy JVM unit testda bu API'lar "not mocked" xatosi beradi (stub'lar
 * `throw RuntimeException` qiladi). Robolectric ularning HAQIQIY
 * implementatsiyasini beradi, shuning uchun testlar Android runtime
 * xatti-harakatini tekshiradi.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NIMA UCHUN BU TESTLAR MUHIM
 * ═══════════════════════════════════════════════════════════════════════════
 * Bu parser Rossiyadagi (TSPU/DPI) foydalanuvchilar uchun obuna linklarini
 * o'qiydi. Xato parse = "server qo'shilmadi" yoki noto'g'ri host/port.
 * v1.12.0 da Hysteria2/TUIC/Juicity (QUIC) uchun tuzatish kiritilgan —
 * bu testlar o'sha xatti-harakatni qulflaydi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServerLinkParserTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. VLESS / Reality
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `vless - host port remark to'g'ri o'qiladi`() {
        val si = ServerLinkParser.parse(
            "vless://uuid-1234@example.com:443?security=reality&sni=cdn.example.com#Frankfurt%20DE"
        )
        assertNotNull("VLESS link parse bo'lishi kerak", si)
        assertEquals("example.com", si!!.host)
        assertEquals(443, si.port)
        assertEquals("Frankfurt DE", si.remark)
        assertEquals(Protocol.VLESS_REALITY, si.protocol)
    }

    @Test
    fun `vless - reality xavfsizlik aniqlanadi`() {
        val si = ServerLinkParser.parse(
            "vless://uuid@1.2.3.4:443?security=reality&pbk=abc&fp=chrome"
        )
        assertNotNull(si)
        assertEquals("reality", si!!.security)
    }

    @Test
    fun `vless - transport turlari aniqlanadi`() {
        val cases = mapOf(
            "type=ws" to "ws",
            "type=grpc" to "grpc",
            "type=xhttp" to "xhttp",
            "type=httpupgrade" to "httpupgrade",
            "type=kcp" to "kcp"
        )
        for ((param, expected) in cases) {
            val si = ServerLinkParser.parse("vless://uuid@host.com:443?$param")
            assertNotNull("$param parse bo'lishi kerak", si)
            assertEquals("transport $param uchun noto'g'ri", expected, si!!.transport)
        }
    }

    @Test
    fun `vless - transport ko'rsatilmasa tcp bo'ladi`() {
        val si = ServerLinkParser.parse("vless://uuid@host.com:443")
        assertNotNull(si)
        assertEquals("tcp", si!!.transport)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. IPv6 — splitHostPort chegaraviy holati
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `IPv6 - qavs ichidagi host to'g'ri ajratiladi`() {
        val si = ServerLinkParser.parse("vless://uuid@[2001:db8::1]:8443")
        assertNotNull(si)
        assertEquals("2001:db8::1", si!!.host)
        assertEquals(8443, si.port)
    }

    @Test
    fun `IPv6 - to'liq manzil`() {
        val si = ServerLinkParser.parse("vless://uuid@[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:443")
        assertNotNull(si)
        assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", si!!.host)
        assertEquals(443, si.port)
    }

    @Test
    fun `IPv6 - localhost`() {
        val si = ServerLinkParser.parse("vless://uuid@[::1]:1080")
        assertNotNull(si)
        assertEquals("::1", si!!.host)
        assertEquals(1080, si.port)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. QUIC protokollari (v1.12.0 fix)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `hysteria2 - quic transport va tls aniqlanadi`() {
        val si = ServerLinkParser.parse("hysteria2://pass@hy.example.com:443?sni=hy.example.com#HY2")
        assertNotNull(si)
        assertEquals(Protocol.HYSTERIA2, si!!.protocol)
        assertEquals("quic", si.transport)
        assertEquals("tls", si.security)
    }

    @Test
    fun `hy2 - qisqa sxema ham ishlaydi`() {
        val si = ServerLinkParser.parse("hy2://pass@hy.example.com:443")
        assertNotNull(si)
        assertEquals(Protocol.HYSTERIA2, si!!.protocol)
    }

    @Test
    fun `tuic - quic va tls`() {
        val si = ServerLinkParser.parse("tuic://uuid:pass@tuic.example.com:443")
        assertNotNull(si)
        assertEquals(Protocol.TUIC, si!!.protocol)
        assertEquals("quic", si.transport)
        assertEquals("tls", si.security)
    }

    @Test
    fun `juicity - quic va tls`() {
        val si = ServerLinkParser.parse("juicity://uuid:pass@j.example.com:443")
        assertNotNull(si)
        assertEquals(Protocol.JUICITY, si!!.protocol)
        assertEquals("quic", si.transport)
        assertEquals("tls", si.security)
    }

    @Test
    fun `barcha QUIC protokollari isUdp flagiga ega`() {
        for (p in listOf(Protocol.HYSTERIA2, Protocol.TUIC, Protocol.JUICITY)) {
            assertTrue("$p UDP bo'lishi kerak", p.isUdp)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Trojan, SSH, SOCKS, ShadowTLS, AnyTLS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `trojan - tls avtomatik`() {
        val si = ServerLinkParser.parse("trojan://pass@tr.example.com:443")
        assertNotNull(si)
        assertEquals(Protocol.TROJAN, si!!.protocol)
        assertEquals("tls", si.security)
    }

    @Test
    fun `socks5 - host va port`() {
        val si = ServerLinkParser.parse("socks5://s.example.com:1080")
        assertNotNull(si)
        assertEquals(Protocol.SOCKS, si!!.protocol)
        assertEquals("s.example.com", si.host)
        assertEquals(1080, si.port)
    }

    @Test
    fun `anytls - aniqlanadi (v1_11_1 da qo'shilgan)`() {
        val si = ServerLinkParser.parse("anytls://pass@a.example.com:443")
        assertNotNull(si)
        assertEquals(Protocol.ANYTLS, si!!.protocol)
    }

    @Test
    fun `shadowtls - aniqlanadi`() {
        val si = ServerLinkParser.parse("shadowtls://pass@st.example.com:443")
        assertNotNull(si)
        assertEquals(Protocol.SHADOWTLS, si!!.protocol)
    }

    @Test
    fun `ssh - aniqlanadi`() {
        val si = ServerLinkParser.parse("ssh://user:pass@ssh.example.com:22")
        assertNotNull(si)
        assertEquals(Protocol.SSH, si!!.protocol)
    }

    @Test
    fun `http-proxy - aniqlanadi`() {
        val si = ServerLinkParser.parse("http-proxy://user:pass@p.example.com:8080")
        assertNotNull(si)
        assertEquals(Protocol.HTTP, si!!.protocol)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. VMess (base64 JSON)
    // ═══════════════════════════════════════════════════════════════════════

    private fun vmessLink(json: String): String =
        "vmess://" + JBase64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `vmess - base64 JSON parse bo'ladi`() {
        val link = vmessLink(
            """{"v":"2","ps":"Test Server","add":"v.example.com","port":"443",
               "id":"uuid-here","aid":"0","net":"ws","type":"none",
               "host":"v.example.com","path":"/ws","tls":"tls"}"""
        )
        val si = ServerLinkParser.parse(link)
        assertNotNull("VMess parse bo'lishi kerak", si)
        assertEquals(Protocol.VMESS, si!!.protocol)
        assertEquals("v.example.com", si.host)
        assertEquals(443, si.port)
        assertEquals("Test Server", si.remark)
    }

    @Test
    fun `vmess - port satr sifatida keladi`() {
        val link = vmessLink("""{"add":"h.com","port":"8443","id":"x","net":"tcp"}""")
        val si = ServerLinkParser.parse(link)
        assertNotNull(si)
        assertEquals(8443, si!!.port)
    }

    @Test
    fun `vmess - buzuq base64 null qaytaradi`() {
        assertNull(ServerLinkParser.parse("vmess://!!!not-base64!!!"))
    }

    @Test
    fun `vmess - buzuq JSON yiqilishga olib kelmaydi`() {
        val link = vmessLink("{bu-json-emas}")
        // Yiqilmasligi shart — null yoki fallback parse
        ServerLinkParser.parse(link)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Shadowsocks
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `ss - SIP002 format`() {
        val si = ServerLinkParser.parse("ss://YWVzLTI1Ni1nY206cGFzcw==@ss.example.com:8388#SS-Server")
        assertNotNull(si)
        assertEquals(Protocol.SS_2022, si!!.protocol)
        assertEquals("ss.example.com", si.host)
        assertEquals(8388, si.port)
    }

    @Test
    fun `ss - to'liq base64 format`() {
        val inner = "aes-256-gcm:password@ss2.example.com:8388"
        val encoded = JBase64.getEncoder().encodeToString(inner.toByteArray())
        val si = ServerLinkParser.parse("ss://$encoded")
        assertNotNull(si)
        assertEquals("ss2.example.com", si!!.host)
        assertEquals(8388, si.port)
    }

    @Test
    fun `ss - bo'sh link null`() {
        assertNull(ServerLinkParser.parse(""))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Placeholder filtr (muhim — "o'lik" serverlarni tashlaydi)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `placeholder - nol UUID rad etiladi`() {
        val si = ServerLinkParser.parse(
            "vless://00000000-0000-0000-0000-000000000000@example.com:443"
        )
        assertNull("Nol UUID placeholder rad etilishi kerak", si)
    }

    @Test
    fun `placeholder - 0_0_0_0 port 1 rad etiladi`() {
        assertNull(ServerLinkParser.parse("vless://uuid@0.0.0.0:1?type=tcp"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. Noma'lum / buzuq kirish — hech qachon crash bo'lmasligi kerak
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `noma'lum protokol null qaytaradi`() {
        assertNull(ServerLinkParser.parse("ftp://example.com:21"))
        assertNull(ServerLinkParser.parse("bu-shunchaki-matn"))
    }

    @Test
    fun `buzuq kirishlar crash qilmaydi`() {
        val garbage = listOf(
            "vless://",
            "vless://@",
            "vless://uuid@",
            "://",
            "###",
            "ss://",
            "vmess://",
            "ssr://",
            "vless://uuid@host:99999999999",
            "vless://uuid@host:-1",
            "vless://uuid@[::1",
            "vless://uuid@host:port",
            "\u0000\u0001\u0002",
            "vless://" + "a".repeat(10000) + "@host:443"
        )
        for (g in garbage) {
            try {
                ServerLinkParser.parse(g)
            } catch (t: Throwable) {
                throw AssertionError(
                    "parse() yiqildi: '${g.take(50)}' -> ${t::class.simpleName}: ${t.message}", t
                )
            }
        }
    }

    @Test
    fun `port - noto'g'ri qiymat 0 bo'ladi, crash emas`() {
        val si = ServerLinkParser.parse("vless://uuid@host.com:abc")
        // host parse bo'lishi mumkin, lekin port 0 dan katta bo'lmasligi kerak
        if (si != null) {
            assertTrue("Port manfiy bo'lmasligi kerak", si.port >= 0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. Remark va URL-decode
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `remark - URL encoded qiymat decode qilinadi`() {
        val si = ServerLinkParser.parse("vless://uuid@h.com:443#Moskva%20%F0%9F%87%B7%F0%9F%87%BA")
        assertNotNull(si)
        assertEquals("Moskva 🇷🇺", si!!.remark)
    }

    @Test
    fun `remark - bo'lmasa host ishlatiladi`() {
        val si = ServerLinkParser.parse("vless://uuid@fallback-host.com:443")
        assertNotNull(si)
        assertEquals("fallback-host.com", si!!.remark)
    }

    @Test
    fun `remark - kirill matn`() {
        val si = ServerLinkParser.parse("vless://uuid@h.com:443#Германия%20Франкфурт")
        assertNotNull(si)
        assertEquals("Германия Франкфурт", si!!.remark)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10. subId uzatilishi
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `subId - saqlanadi`() {
        val si = ServerLinkParser.parse("vless://uuid@h.com:443", "sub-42")
        assertNotNull(si)
        assertEquals("sub-42", si!!.subId)
    }

    @Test
    fun `subId - berilmasa null`() {
        val si = ServerLinkParser.parse("vless://uuid@h.com:443")
        assertNotNull(si)
        assertNull(si!!.subId)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 11. displayName / flag
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `displayName - link bilan boshlanadigan remark host bilan almashtiriladi`() {
        val si = ServerLinkParser.parse("vless://uuid@real-host.com:443#vless://xato")
        assertNotNull(si)
        assertEquals("real-host.com", si!!.displayName())
    }

    @Test
    fun `displayName - juda uzun remark host bilan almashtiriladi`() {
        val si = ServerLinkParser.parse("vless://uuid@h.com:443#" + "a".repeat(100))
        assertNotNull(si)
        assertEquals("h.com", si!!.displayName())
    }

    @Test
    fun `flag - hech bo'lmaganda biror qiymat qaytaradi`() {
        val si = ServerLinkParser.parse("vless://uuid@h.com:443")
        assertNotNull(si)
        assertTrue("flag() bo'sh bo'lmasligi kerak", si!!.flag().isNotEmpty())
    }
}
