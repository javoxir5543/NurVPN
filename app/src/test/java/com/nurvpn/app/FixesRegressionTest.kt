package com.nurvpn.app

import com.nurvpn.app.config.SingBoxConfig
import com.nurvpn.app.parser.ServerLinkParser
import com.nurvpn.app.parser.SubscriptionLinkExtractor
import com.nurvpn.app.parser.decodeBase64Safely
import com.nurvpn.app.util.CountryLookup
import com.nurvpn.app.util.PingTester
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  v1.13.0 — 7 TA TUZATISH UCHUN REGRESSIYA TESTLARI
 * ═══════════════════════════════════════════════════════════════════════════
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FixesRegressionTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    // ═══════════ #1: PING — cheklangan parallellik ═══════════
    @Test
    fun `fix1 - MAX_CONCURRENT cheklangan va oqilona`() {
        assertTrue("MAX_CONCURRENT juda katta bo'lmasin",
            PingTester.MAX_CONCURRENT in 4..40)
    }

    @Test
    fun `fix1 - tcpPing yopiq portga -1 qaytaradi (bloklanmaydi)`() {
        val t0 = System.currentTimeMillis()
        val r = PingTester.tcpPing("127.0.0.1", 1, 1500)
        val dt = System.currentTimeMillis() - t0
        assertEquals(-1, r)
        assertTrue("timeout hurmat qilinmadi: ${dt}ms", dt < 3500)
    }

    // ═══════════ #2: DAVLAT BAYROG'I ═══════════
    @Test
    fun `fix2 - ingliz davlat nomidan bayroq aniqlanadi`() {
        assertEquals("RU", CountryLookup.ccFromRemark("Russia Moscow"))
        assertEquals("JP", CountryLookup.ccFromRemark("Japan Tokyo"))
        assertEquals("NL", CountryLookup.ccFromRemark("Netherlands Amsterdam"))
        assertEquals("DE", CountryLookup.ccFromRemark("Germany Frankfurt"))
        assertEquals("FR", CountryLookup.ccFromRemark("France Paris"))
        assertEquals("US", CountryLookup.ccFromRemark("United States"))
        assertEquals("TR", CountryLookup.ccFromRemark("Turkey Istanbul"))
    }

    @Test
    fun `fix2 - rus tilidagi nomlardan ham aniqlanadi`() {
        assertEquals("RU", CountryLookup.ccFromRemark("Россия Москва"))
        assertEquals("DE", CountryLookup.ccFromRemark("Германия Франкфурт"))
        assertEquals("NL", CountryLookup.ccFromRemark("Нидерланды"))
    }

    @Test
    fun `fix2 - emoji bayroq ustunlik qiladi`() {
        assertEquals("CA", CountryLookup.ccFromRemark("🇨🇦 Kanada"))
        assertEquals("DE", CountryLookup.ccFromRemark("🇩🇪 Germaniya"))
    }

    @Test
    fun `fix2 - noma'lum nom uchun bayroq qaytmaydi`() {
        assertEquals("", CountryLookup.ccFromRemark("Some Random Node 42"))
    }

    @Test
    fun `fix2 - flag() nomdan bayroq chiqaradi (ServerItem orqali)`() {
        val si = ServerLinkParser.parse(
            "vless://u@a.com:443?security=tls#Japan%20Tokyo")
        assertNotNull(si)
        assertEquals("🇯🇵", si!!.flag())
    }

    @Test
    fun `fix2 - lookup 2 harflik TLD bilan ishlaydi`() {
        assertEquals("DE", CountryLookup.lookup("de.example.com").first())
        assertEquals("RU", CountryLookup.lookup("node.ru").first())
    }

    // ═══════════ #3: BASE64 OBUNA ═══════════
    private fun b64(s: String) =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `fix3 - base64 obuna dekodlanib linklar topiladi`() {
        val plain = "vless://uuid@a.com:443?security=tls#S1\n" +
                    "trojan://p@b.com:443#S2\n"
        val sub = b64(plain)
        // Avval: extract(sub) → 0 ta. Endi: decode qilinadi.
        assertEquals(0, SubscriptionLinkExtractor.extract(sub).size)
        val dec = decodeBase64Safely(sub)
        assertEquals(2, SubscriptionLinkExtractor.extract(dec).size)
    }

    @Test
    fun `fix3 - ko'p qatorli (wrapped) base64 ham ishlaydi`() {
        val plain = "vless://uuid@a.com:443?security=tls#S1\n" +
                    "vmess://eyJ2IjoiMiJ9\n"
        val one = b64(plain)
        val wrapped = one.chunked(64).joinToString("\n")
        val dec = decodeBase64Safely(wrapped)
        assertEquals(2, SubscriptionLinkExtractor.extract(dec).size)
    }

    @Test
    fun `fix3 - URL-safe base64 ham ishlaydi`() {
        val plain = "vless://uuid@a.com:443?security=tls#S1\n"
        val u = Base64.getUrlEncoder().encodeToString(plain.toByteArray())
        assertTrue(SubscriptionLinkExtractor.extract(decodeBase64Safely(u)).isNotEmpty())
    }

    @Test
    fun `fix3 - 14 protokol TO'LIQ qo'llab-quvvatlanadi`() {
        val links = listOf(
            "vless://u@a.com:443#x", "vmess://eyJ2IjoiMiJ9",
            "trojan://p@a.com:443#x", "ss://YWVzOnB3@a.com:443#x",
            "ssr://c29tZXRoaW5n", "hysteria2://p@a.com:443#x",
            "hy2://p@a.com:443#x", "tuic://u:p@a.com:443#x",
            "juicity://u:p@a.com:443#x", "socks5://u:p@a.com:1080#x",
            "http-proxy://a.com:8080#x", "ssh://u@a.com:22#x",
            "shadowtls://p@a.com:443#x", "anytls://p@a.com:443#x"
        )
        for (l in links) {
            assertTrue("Topilmadi: $l",
                SubscriptionLinkExtractor.extract(l).isNotEmpty())
        }
    }

    // ═══════════ #5: DEEP LINK ═══════════
    @Test
    fun `fix5 - oddiy vless linki endi anqlanadi (avval rad etilardi)`() {
        val uri = "vless://uuid@a.com:443?security=tls#X"
        assertTrue(SubscriptionLinkExtractor.extract(uri).isNotEmpty())
    }

    @Test
    fun `fix5 - 13 ta scheme manifest orqali qabul qilinadi`() {
        val schemes = listOf("vless", "vmess", "trojan", "ss", "ssr",
            "hysteria2", "hy2", "tuic", "juicity", "anytls", "shadowtls",
            "socks5", "ssh")
        for (s in schemes) {
            assertTrue("scheme ishlamaydi: $s",
                SubscriptionLinkExtractor.extract("$s://test@a.com:443#x")
                    .isNotEmpty() || s in setOf("ssr", "vmess"))
        }
    }

    // ═══════════ #6: YANGI OBUNA ═══════════
    @Test
    fun `fix6 - radikal_hy2 katalogda mavjud va to'g'ri`() {
        val sub = com.nurvpn.app.storage.OpenSourceCatalog.byId("radikal_hy2")
        assertNotNull("radikal_hy2 topilmadi", sub)
        assertEquals("🌐", sub!!.flag)
        assertEquals(
            "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs" +
                "/main/protocols/hysteria2.txt", sub.url)
        assertEquals("Radikal Hysteria2", sub.name(ctx))
        assertEquals("Hysteria2 configs by 0xRadikal", sub.description(ctx))
        assertFalse(sub.isAwg)
    }

    @Test
    fun `fix6 - barcha katalog yozuvlarida nom va tavsif bor`() {
        for (s in com.nurvpn.app.storage.OpenSourceCatalog.ALL) {
            assertTrue("${s.id}: nom bo'sh", s.name(ctx).isNotBlank())
            assertTrue("${s.id}: tavsif bo'sh", s.description(ctx).isNotBlank())
        }
    }

    // ═══════════ #7: XAVFSIZLIK CONFIG'GA BOG'LANDI ═══════════
    private val link =
        "vless://11111111-1111-1111-1111-111111111111@a.com:443" +
        "?security=tls&sni=a.com#T"

    private fun dnsServers(cfg: JSONObject): Map<String, JSONObject> {
        val arr = cfg.getJSONObject("dns").getJSONArray("servers")
        val m = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.has("tag")) m[o.getString("tag")] = o
        }
        return m
    }

    @Test
    fun `fix7 - dnsLeakProtect YOQILGANda bootstrap ham proxy orqali`() {
        val cfg = SingBoxConfig.buildFullConfig(
            link, "1.1.1.1", "", false, ctx, dnsLeakProtect = true)
        val d = dnsServers(cfg)["dns-direct"]
        assertNotNull("dns-direct yo'q", d)
        assertEquals(
            "⚠️ DNS LEAK: bootstrap DNS to'g'ridan-to'g'ri ketyapti!",
            "proxy", d!!.optString("detour"))
    }

    @Test
    fun `fix7 - dnsLeakProtect O'CHIRILGANda bootstrap direct`() {
        val cfg = SingBoxConfig.buildFullConfig(
            link, "1.1.1.1", "", false, ctx, dnsLeakProtect = false)
        val d = dnsServers(cfg)["dns-direct"]
        assertNotNull(d)
        assertTrue("detour bo'lmasligi kerak",
            d!!.optString("detour").isEmpty())
    }

    @Test
    fun `fix7 - dns-remote har doim proxy orqali`() {
        for (leak in listOf(true, false)) {
            val cfg = SingBoxConfig.buildFullConfig(
                link, "1.1.1.1", "", false, ctx, dnsLeakProtect = leak)
            assertEquals("proxy",
                dnsServers(cfg)["dns-remote"]!!.optString("detour"))
        }
    }

    @Test
    fun `fix7 - ipv6Blocked=true strategy ipv4_only qiladi`() {
        val cfg = SingBoxConfig.buildFullConfig(
            link, "1.1.1.1", "", true, ctx)
        assertEquals("ipv4_only",
            cfg.getJSONObject("dns").getString("strategy"))
    }

    @Test
    fun `fix7 - ipv6Blocked=false strategy prefer_ipv4`() {
        val cfg = SingBoxConfig.buildFullConfig(
            link, "1.1.1.1", "", false, ctx)
        assertEquals("prefer_ipv4",
            cfg.getJSONObject("dns").getString("strategy"))
    }

    @Test
    fun `fix7 - ipv6Blocked=true TUN'da IPv6 manzil yo'q`() {
        val cfg = SingBoxConfig.buildFullConfig(
            link, "1.1.1.1", "", true, ctx)
        val addr = cfg.getJSONArray("inbounds")
            .getJSONObject(0).getJSONArray("address")
        for (i in 0 until addr.length()) {
            assertFalse("IPv6 manzil qolgan: ${addr.getString(i)}",
                addr.getString(i).contains(":"))
        }
    }

    @Test
    fun `fix7 - default dnsLeakProtect XAVFSIZ (true)`() {
        val cfg = SingBoxConfig.buildFullConfig(link, "1.1.1.1", "", false, ctx)
        assertEquals("proxy",
            dnsServers(cfg)["dns-direct"]!!.optString("detour"))
    }
}
