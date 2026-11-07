package com.nurvpn.app.config

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SingBoxConfig testlari — eng nozik fayl (1168+ qator, DPI chetlab o'tish).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NEGA BU TESTLAR JUDA MUHIM
 * ═══════════════════════════════════════════════════════════════════════════
 * Foydalanuvchilar Rossiyada (TSPU/DPI). Bu fayldagi xato = VPN ISHLAMAYDI
 * yoki IP oshkor bo'ladi. v1.12.0 da kiritilgan tuzatishlar:
 *
 *   1. Hysteria2/TUIC/Juicity (QUIC) uchun `utls` O'CHIRILDI.
 *      Sabab: QUIC protokollarida utls qo'llab-quvvatlanmaydi — yoqilsa
 *      "salamander obfs" ishlamaydi va ulanish uziladi.
 *
 *   2. STUN trafigi rad etiladi (`protocol: stun` -> reject).
 *      Sabab: WebRTC/STUN orqali haqiqiy (public) IP oshkor bo'lardi.
 *      MUHIM: 3478/5349 PORTLARI bloklanmaydi — aks holda WhatsApp/Telegram
 *      qo'ng'iroqlari buziladi.
 *
 *   3. YouTube QUIC bloklanadi — faqat YouTube domenlari uchun, port 443/udp.
 *
 * Bu testlar o'sha xatti-harakatni QULFLAYDI. Agar kimdir `utls` ni qayta
 * yoqsa yoki STUN qoidasini o'chirsa, test YIQILADI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SingBoxConfigTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. isIpAddress
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isIpAddress - to'g'ri IPv4`() {
        assertTrue(SingBoxConfig.isIpAddress("1.2.3.4"))
        assertTrue(SingBoxConfig.isIpAddress("0.0.0.0"))
        assertTrue(SingBoxConfig.isIpAddress("255.255.255.255"))
        assertTrue(SingBoxConfig.isIpAddress("192.168.1.1"))
    }

    @Test
    fun `isIpAddress - noto'g'ri IPv4 rad etiladi`() {
        assertFalse("256 chegara tashqarisida", SingBoxConfig.isIpAddress("256.1.1.1"))
        assertFalse("999 chegara tashqarisida", SingBoxConfig.isIpAddress("999.999.999.999"))
        assertFalse(SingBoxConfig.isIpAddress("1.2.3"))
        assertFalse(SingBoxConfig.isIpAddress("1.2.3.4.5"))
        assertFalse(SingBoxConfig.isIpAddress("a.b.c.d"))
        assertFalse(SingBoxConfig.isIpAddress(""))
    }

    @Test
    fun `isIpAddress - IPv6`() {
        assertTrue(SingBoxConfig.isIpAddress("2001:db8::1"))
        assertTrue(SingBoxConfig.isIpAddress("::1"))
        assertTrue(SingBoxConfig.isIpAddress("fe80::1"))
    }

    @Test
    fun `isIpAddress - qavsli IPv6 ham tan olinadi`() {
        assertTrue(SingBoxConfig.isIpAddress("[2001:db8::1]"))
    }

    @Test
    fun `isIpAddress - null xavfsiz`() {
        assertFalse(SingBoxConfig.isIpAddress(null))
    }

    @Test
    fun `isIpAddress - domen rad etiladi`() {
        assertFalse(SingBoxConfig.isIpAddress("example.com"))
        assertFalse(SingBoxConfig.isIpAddress("vpn.example.com"))
        assertFalse(SingBoxConfig.isIpAddress("localhost"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. cidrSuffix
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `cidrSuffix - IPv4 uchun 32`() {
        assertEquals("/32", SingBoxConfig.cidrSuffix("1.2.3.4"))
    }

    @Test
    fun `cidrSuffix - IPv6 uchun 128`() {
        assertEquals("/128", SingBoxConfig.cidrSuffix("2001:db8::1"))
        assertEquals("/128", SingBoxConfig.cidrSuffix("::1"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. isHysteria2Link
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `isHysteria2Link - hysteria2 va hy2 sxemalari`() {
        assertTrue(SingBoxConfig.isHysteria2Link("hysteria2://pass@h.com:443"))
        assertTrue(SingBoxConfig.isHysteria2Link("hy2://pass@h.com:443"))
    }

    @Test
    fun `isHysteria2Link - registrga sezgir emas`() {
        assertTrue(SingBoxConfig.isHysteria2Link("HYSTERIA2://x"))
        assertTrue(SingBoxConfig.isHysteria2Link("HY2://x"))
    }

    @Test
    fun `isHysteria2Link - boshqa protokollar false`() {
        assertFalse(SingBoxConfig.isHysteria2Link("vless://x"))
        assertFalse(SingBoxConfig.isHysteria2Link("tuic://x"))
        assertFalse(SingBoxConfig.isHysteria2Link(""))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. ⚠️ QUIC FIX — utls o'chirilgan bo'lishi SHART
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * QUIC protokollari uchun outbound'da `tls.utls` BO'LMASLIGI kerak.
     *
     * v1.12.0 dan oldin: utls yoqilgan edi -> Hysteria2 salamander obfs
     * bilan ishlamasdi. Bu regressiya testi.
     */
    private fun outboundTls(link: String): JSONObject {
        val o = SingBoxConfig.outboundFor(link, ctx)
        assertNotNull("outbound bo'lishi kerak: $link", o)
        assertTrue("tls bo'lishi kerak: $link", o.has("tls"))
        return o.getJSONObject("tls")
    }

    private val ctx: android.content.Context
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `QUIC - hysteria2 da utls BO'LMASLIGI kerak (v1_12_0 fix)`() {
        val tls = outboundTls("hysteria2://pass@hy.example.com:443?sni=hy.example.com")
        assertFalse(
            "⚠️ REGRESSIYA: Hysteria2 uchun utls yoqilgan! " +
                "QUIC protokollarida utls qo'llab-quvvatlanmaydi — " +
                "salamander obfs ishlamaydi. tls=$tls",
            tls.has("utls")
        )
    }

    @Test
    fun `QUIC - tuic da utls BO'LMASLIGI kerak`() {
        val tls = outboundTls("tuic://uuid:pass@tuic.example.com:443?sni=tuic.example.com")
        assertFalse(
            "⚠️ REGRESSIYA: TUIC uchun utls yoqilgan! tls=$tls",
            tls.has("utls")
        )
    }

    @Test
    fun `QUIC - juicity da utls BO'LMASLIGI kerak`() {
        val tls = outboundTls("juicity://uuid:pass@j.example.com:443?sni=j.example.com")
        assertFalse(
            "⚠️ REGRESSIYA: Juicity uchun utls yoqilgan! tls=$tls",
            tls.has("utls")
        )
    }

    @Test
    fun `QUIC - hy2 qisqa sxemada ham utls yo'q`() {
        val tls = outboundTls("hy2://pass@h.com:443")
        assertFalse("hy2 uchun utls yoqilmasligi kerak", tls.has("utls"))
    }

    @Test
    fun `QUIC - tls yoqilgan va server_name to'g'ri`() {
        val tls = outboundTls("hysteria2://pass@hy.example.com:443?sni=custom.sni.com")
        assertTrue("TLS yoqilgan bo'lishi kerak", tls.getBoolean("enabled"))
        assertEquals("custom.sni.com", tls.getString("server_name"))
    }

    @Test
    fun `QUIC - alpn h3`() {
        val tls = outboundTls("hysteria2://pass@hy.example.com:443")
        assertTrue("QUIC uchun alpn bo'lishi kerak", tls.has("alpn"))
        val alpn = tls.get("alpn")
        val arr = if (alpn is JSONArray) alpn else JSONArray().put(alpn)
        assertTrue("alpn h3 bo'lishi kerak: $alpn", arr.toString().contains("h3"))
    }

    /**
     * TCP protokollari uchun utls YOQILGAN bo'lishi kerak — bu DPI
     * chetlab o'tishning asosiy usuli (Chrome fingerprint).
     */
    @Test
    fun `TCP - vless reality uchun utls YOQILGAN (DPI chetlab o'tish)`() {
        val tls = outboundTls("vless://uuid@v.example.com:443?security=reality&sni=v.example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&fp=chrome")
        assertTrue(
            "⚠️ REGRESSIYA: VLESS/Reality uchun utls yo'q! " +
                "DPI chetlab o'tish ishlamaydi. tls=$tls",
            tls.has("utls")
        )
        assertTrue("utls yoqilgan bo'lishi kerak",
            tls.getJSONObject("utls").getBoolean("enabled"))
    }

    @Test
    fun `TCP - trojan uchun utls yoqilgan`() {
        val tls = outboundTls("trojan://pass@t.example.com:443?sni=t.example.com")
        assertTrue("Trojan uchun utls kerak", tls.has("utls"))
    }

    /**
     * `security=tls` rejimida havoladagi `fp` HURMAT QILINADI.
     * (Reality'da esa ataylab chrome'ga majburlanadi — pastdagi testga qarang.)
     */
    @Test
    fun `TCP - tls rejimida havoladagi fingerprint ishlatiladi`() {
        val tls = outboundTls("vless://uuid@v.com:443?security=tls&sni=v.com&fp=firefox")
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
    }

    /**
     * ✅ TUZATILGAN: yaroqsiz fingerprint chrome'ga almashtiriladi.
     *
     * ⚠️ TARIX (v1.12.0 regressiyasi): `sanitizeFp()` tekshiruvi olib
     * tashlangan edi va qiymatni o'zgartirmasdan qaytarardi. Natijada
     * obunadagi noto'g'ri `fp=` sing-box'ga borib yetardi va u
     * "unknown uTLS fingerprint: <qiymat>" bilan ishga tushmasdi —
     * ya'ni VPN umuman ulanmasdi.
     *
     * Bu test endi TO'G'RI xatti-harakatni qulflaydi.
     */
    @Test
    fun `TCP - yaroqsiz fingerprint chrome'ga almashtiriladi (regressiya tuzatildi)`() {
        val tls = outboundTls("vless://uuid@v.com:443?security=tls&sni=v.com&fp=notoGriFingerprint")
        assertEquals(
            "⚠️ REGRESSIYA QAYTDI: sing-box notanish fingerprint'ni qabul qilmaydi " +
                "('unknown uTLS fingerprint'). Bunday qiymat chrome bo'lishi shart.",
            "chrome",
            tls.getJSONObject("utls").getString("fingerprint")
        )
    }

    /**
     * `randomizednoalpn` — sing-box BINARIDA YO'Q qiymat.
     * (enum: chrome_psk, ..., chrome, chrome_155, firefox, edge, safari,
     *  360, qq, ios, android, random, randomized — `randomizednoalpn` yo'q.)
     * Shuning uchun chrome'ga almashtirilishi kerak.
     */
    @Test
    fun `TCP - randomizednoalpn yaroqsiz, chrome bo'ladi`() {
        val tls = outboundTls(
            "vless://uuid@v.com:443?security=tls&sni=v.com&fp=randomizednoalpn"
        )
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    /**
     * sing-box HAQIQIY qo'llab-quvvatlaydigan qiymatlar O'ZGARTIRILMASLIGI kerak.
     * Ro'yxat libbox.so ichidagi sxemadan olindi (enum:"...").
     */
    @Test
    fun `TCP - sing-box qo'llab-quvvatlaydigan barcha fingerprint'lar saqlanadi`() {
        val valid = listOf(
            "chrome", "chrome_155", "chrome_pq", "chrome_psk",
            "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
            "firefox", "edge", "safari", "360", "qq", "ios", "android",
            "random", "randomized"
        )
        for (fp in valid) {
            val tls = outboundTls("vless://uuid@v.com:443?security=tls&sni=v.com&fp=$fp")
            assertEquals(
                "'$fp' sing-box tomonidan qo'llab-quvvatlanadi — " +
                    "o'zgartirilmasligi kerak",
                fp, tls.getJSONObject("utls").getString("fingerprint")
            )
        }
    }

    @Test
    fun `TCP - fingerprint registrga sezgir emas (CHROME - chrome)`() {
        val tls = outboundTls("vless://uuid@v.com:443?security=tls&sni=v.com&fp=FIREFOX")
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun `TCP - fingerprint atrofidagi bo'sh joylar tozalanadi`() {
        val tls = outboundTls("vless://uuid@v.com:443?security=tls&sni=v.com&fp=%20firefox%20")
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun `TCP - bo'sh fingerprint chrome bo'ladi`() {
        val tls = outboundTls("vless://uuid@v.com:443?security=tls&sni=v.com&fp=")
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    /**
     * VMess base64-JSON yo'lida ham tekshiruv ishlashi kerak.
     * (Bu yo'l `vmess://<base64-json>` ko'rinishida; `tls: "tls"` va
     *  `fp` maydonlari JSON ichida bo'ladi.)
     */
    @Test
    fun `TCP - vmess base64 JSON yo'lida yaroqsiz fp tozalanadi`() {
        val tls = outboundTls(vmessLink(fp = "buzuqFp"))
        assertEquals(
            "VMess base64-JSON'dagi yaroqsiz fp ham chrome bo'lishi kerak",
            "chrome", tls.getJSONObject("utls").getString("fingerprint")
        )
    }

    @Test
    fun `TCP - vmess base64 JSON yo'lida to'g'ri fp saqlanadi`() {
        val tls = outboundTls(vmessLink(fp = "firefox"))
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
    }

    /** `vmess://` + base64(JSON) link yasaydi (tls yoqilgan holda). */
    private fun vmessLink(fp: String): String {
        val json = """
            {"v":"2","ps":"T","add":"v.com","port":"443","id":"uuid-1",
             "aid":"0","net":"tcp","tls":"tls","sni":"v.com","fp":"$fp"}
        """.trimIndent()
        val b64 = java.util.Base64.getEncoder()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
        return "vmess://$b64"
    }

    /**
     * QUIC protokollarida `utls` umuman qo'shilmaydi (v1.12.0 fix),
     * shuning uchun fingerprint tekshiruvi ham ahamiyatsiz.
     */
    @Test
    fun `QUIC - hysteria2 da utls yo'q, fp ahamiyatsiz`() {
        val tls = outboundTls("hysteria2://pass@h.com:443?fp=buzuqFp")
        assertFalse("QUIC'da utls bo'lmasligi kerak", tls.has("utls"))
    }

    @Test
    fun `TCP - fingerprint berilmasa chrome`() {
        val tls = outboundTls("vless://uuid@v.com:443?security=tls&sni=v.com")
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    /**
     * Reality uchun fingerprint ATAYLAB chrome'ga majburlanadi.
     *
     * Sabab (kod izohidan): Xray >= 26.9.8 gibrid key share
     * (`key_share: hybrid`) talab qiladi va u faqat chrome fingerprint
     * bilan ishlaydi. Havolada boshqa fp yozilgan bo'lsa ham chrome.
     */
    @Test
    fun `TCP - reality fingerprint chrome'ga majburlanadi`() {
        val tls = outboundTls(
            "vless://uuid@v.com:443?security=reality&sni=v.com&fp=firefox" +
                "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
        assertEquals(
            "Reality gibrid key share faqat chrome bilan ishlaydi — " +
                "fp havolada boshqacha bo'lsa ham chrome bo'lishi shart",
            "chrome", tls.getJSONObject("utls").getString("fingerprint")
        )
    }

    @Test
    fun `TCP - reality key_share hybrid (Xray 26_9_8+)`() {
        val tls = outboundTls(
            "vless://uuid@v.com:443?security=reality&sni=v.com" +
                "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
        val reality = tls.getJSONObject("reality")
        assertTrue("reality yoqilgan bo'lishi kerak", reality.getBoolean("enabled"))
        assertEquals("hybrid", reality.getString("key_share"))
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            reality.getString("public_key")
        )
    }

    /**
     * Reality `pbk` (public key) bo'lmasa — aniq xato bo'lishi kerak.
     * Bu server tomonidagi noto'g'ri obunani erta ushlaydi.
     */
    @Test
    fun `TCP - reality pbk bo'lmasa ParseException`() {
        try {
            SingBoxConfig.outboundFor("vless://uuid@v.com:443?security=reality&sni=v.com", ctx)
            throw AssertionError("pbk bo'lmasa xato kutilgan edi")
        } catch (e: SingBoxConfig.ParseException) {
            assertTrue(
                "Xato xabari pbk haqida bo'lishi kerak: ${e.message}",
                e.message?.contains("pbk") == true
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. SNI tozalash
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `sni - utls va quic ikkalasida ham o'rnatiladi`() {
        for (link in listOf(
            "vless://uuid@h.com:443?security=tls&sni=real.sni.com",
            "hysteria2://pass@h.com:443?sni=real.sni.com"
        )) {
            assertEquals(
                "SNI o'rnatilishi kerak: $link",
                "real.sni.com", outboundTls(link).getString("server_name")
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. outboundFor dispatch — protokol -> tur
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `outbound - hysteria2 turi to'g'ri`() {
        val o = SingBoxConfig.outboundFor("hysteria2://pass@h.com:443", ctx)
        assertEquals("hysteria2", o.getString("type"))
    }

    @Test
    fun `outbound - tuic turi to'g'ri`() {
        val o = SingBoxConfig.outboundFor("tuic://uuid:pass@h.com:443", ctx)
        assertEquals("tuic", o.getString("type"))
    }

    @Test
    fun `outbound - juicity turi to'g'ri`() {
        val o = SingBoxConfig.outboundFor("juicity://uuid:pass@h.com:443", ctx)
        assertEquals("juicity", o.getString("type"))
    }

    @Test
    fun `outbound - vless turi to'g'ri`() {
        val o = SingBoxConfig.outboundFor("vless://uuid@h.com:443", ctx)
        assertEquals("vless", o.getString("type"))
    }

    @Test
    fun `outbound - trojan turi to'g'ri`() {
        val o = SingBoxConfig.outboundFor("trojan://pass@h.com:443", ctx)
        assertEquals("trojan", o.getString("type"))
    }

    @Test
    fun `outbound - host va port o'rnatiladi`() {
        val o = SingBoxConfig.outboundFor("vless://uuid@myhost.com:8443", ctx)
        assertEquals("myhost.com", o.getString("server"))
        assertEquals(8443, o.getInt("server_port"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Chidamlilik — buzuq linklar crash qilmasligi kerak
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * `outboundFor()` notanish/buzuq kirish uchun `ParseException` TASHLASHI
     * — bu KUTILGAN xatti-harakat (chaqiruvchi uni ushlaydi va UI'da xato
     * ko'rsatadi). Bu yerda MUHIM narsa: hech qachon boshqa turdagi
     * (masalan NullPointerException) xato chiqmasligi.
     */
    @Test
    fun `outbound - buzuq linklar faqat ParseException beradi`() {
        val garbage = listOf(
            "", "vless://", "hysteria2://", "tuic://", ":::", "://",
            "vless://@:", "hysteria2://@:0", "bu-matn-link-emas",
            "vless://uuid@h.com:999999",
            "vless://" + "a".repeat(5000) + "@h.com:443",
            "\u0000\u0001\u0002"
        )
        for (g in garbage) {
            try {
                SingBoxConfig.outboundFor(g, ctx)
            } catch (e: SingBoxConfig.ParseException) {
                // kutilgan
            } catch (t: Throwable) {
                throw AssertionError(
                    "outboundFor() kutilmagan xato berdi: '${g.take(40)}' -> " +
                        "${t::class.simpleName}: ${t.message}", t
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. ⚠️ ROUTE QOIDALARI — STUN leak va YouTube QUIC
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildRoute(): JSONObject {
        val link = "vless://uuid@1.2.3.4:443?security=tls&sni=x.com"
        return SingBoxConfig.buildFullConfig(link, "1.1.1.1", ctx = ctx)
            .getJSONObject("route")
    }

    private fun rules(): JSONArray = buildRoute().getJSONArray("rules")

    /** Qoidalar ro'yxatidan `protocol == stun` bo'lganini topadi. */
    private fun findStunRule(): JSONObject? {
        val rs = rules()
        for (i in 0 until rs.length()) {
            val r = rs.getJSONObject(i)
            if (r.optString("protocol") == "stun") return r
        }
        return null
    }

    private fun findYoutubeRule(): JSONObject? {
        val rs = rules()
        for (i in 0 until rs.length()) {
            val r = rs.getJSONObject(i)
            if (r.has("domain_suffix") &&
                r.getJSONArray("domain_suffix").toString().contains("youtube.com")) {
                return r
            }
        }
        return null
    }

    @Test
    fun `STUN - reject qoidasi mavjud (IP leak himoyasi)`() {
        val rule = findStunRule()
        assertNotNull(
            "⚠️ REGRESSIYA: STUN qoidasi yo'q! WebRTC/STUN orqali " +
                "foydalanuvchining haqiqiy IP'si oshkor bo'ladi. " +
                "Qoidalar: ${rules()}",
            rule
        )
        assertEquals("reject", rule!!.getString("action"))
    }

    /**
     * MUHIM: 3478/5349 portlari BLOKLANMASLIGI kerak — aks holda
     * WhatsApp/Telegram qo'ng'iroqlari buziladi.
     */
    @Test
    fun `STUN - portlar bloklanmagan (qo'ng'iroqlar buzilmasin)`() {
        val rule = findStunRule()
        assertNotNull(rule)
        val r = rule!!
        val asText = r.toString()
        assertFalse(
            "⚠️ STUN qoidasi port bo'yicha bloklamasligi kerak — " +
                "WhatsApp/Telegram qo'ng'iroqlari buziladi. Qoida: $asText",
            r.has("port")
        )
        assertFalse("3478 porti bloklanmasin", asText.contains("3478"))
        assertFalse("5349 porti bloklanmasin", asText.contains("5349"))
    }

    @Test
    fun `YouTube - QUIC bloklash qoidasi mavjud`() {
        val rule = findYoutubeRule()
        assertNotNull("⚠️ REGRESSIYA: YouTube QUIC qoidasi yo'q!", rule)
        assertEquals("reject", rule!!.getString("action"))
        assertEquals("udp", rule.getString("network"))
    }

    @Test
    fun `YouTube - port 443 uchun`() {
        val rule = findYoutubeRule()
        assertNotNull(rule)
        assertEquals("443", rule!!.get("port").toString())
    }

    @Test
    fun `YouTube - kerakli domenlar qamrab olingan`() {
        val rule = findYoutubeRule()
        assertNotNull(rule)
        val domains = rule!!.getJSONArray("domain_suffix").toString()
        for (d in listOf(
            "youtube.com", "googlevideo.com", "ytimg.com",
            "youtu.be", "ggpht.com", "youtube-nocookie.com", "yt.be"
        )) {
            assertTrue("'$d' qamrab olinishi kerak. Topildi: $domains", domains.contains(d))
        }
    }

    @Test
    fun `YouTube - domenlarda bosh nuqta yo'q (v1_12_0 fix)`() {
        val rule = findYoutubeRule()
        assertNotNull(rule)
        val arr = rule!!.getJSONArray("domain_suffix")
        for (i in 0 until arr.length()) {
            val d = arr.getString(i)
            assertFalse(
                "⚠️ REGRESSIYA: domen bosh nuqta bilan boshlanadi: '$d'. " +
                    "domain_suffix avtomatik qo'shadi — qo'lda qo'yilsa mos kelmaydi.",
                d.startsWith(".")
            )
        }
    }

    @Test
    fun `route - DNS hijack qoidasi bor`() {
        val rs = rules()
        var found = false
        for (i in 0 until rs.length()) {
            if (rs.getJSONObject(i).optString("protocol") == "dns" &&
                rs.getJSONObject(i).optString("action") == "hijack-dns") {
                found = true
            }
        }
        assertTrue("DNS hijack qoidasi bo'lishi kerak", found)
    }

    @Test
    fun `route - sniff qoidasi birinchi`() {
        val first = rules().getJSONObject(0)
        assertEquals("sniff", first.optString("action"))
    }

    @Test
    fun `route - private IP direct (lokal tarmoq ishlashi uchun)`() {
        val rs = rules()
        var found = false
        for (i in 0 until rs.length()) {
            val r = rs.getJSONObject(i)
            if (r.optBoolean("ip_is_private") && r.optString("outbound") == "direct") found = true
        }
        assertTrue("Lokal tarmoq direct bo'lishi kerak", found)
    }

    @Test
    fun `route - final proxy`() {
        assertEquals("proxy", buildRoute().getString("final"))
    }

    @Test
    fun `route - auto_detect_interface yoqilgan`() {
        assertTrue(buildRoute().getBoolean("auto_detect_interface"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. buildFullConfig tuzilishi
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `buildFullConfig - kerakli bo'limlar mavjud`() {
        val root = SingBoxConfig.buildFullConfig(
            "vless://uuid@1.2.3.4:443?security=tls", "1.1.1.1", ctx = ctx
        )
        for (key in listOf("log", "dns", "inbounds", "outbounds", "route")) {
            assertTrue("'$key' bo'limi bo'lishi kerak", root.has(key))
        }
    }

    @Test
    fun `buildFullConfig - log darajasi info (v1_12_0 CPU tejash)`() {
        val root = SingBoxConfig.buildFullConfig("vless://uuid@1.2.3.4:443?security=tls", "1.1.1.1", ctx = ctx)
        assertEquals(
            "Log darajasi 'info' bo'lishi kerak — 'debug' CPU yeydi",
            "info", root.getJSONObject("log").getString("level")
        )
    }

    @Test
    fun `buildFullConfig - proxy va direct outbound bor`() {
        val root = SingBoxConfig.buildFullConfig("vless://uuid@1.2.3.4:443?security=tls", "1.1.1.1", ctx = ctx)
        val outs = root.getJSONArray("outbounds")
        val types = (0 until outs.length()).map { outs.getJSONObject(it).optString("type") }
        assertTrue("direct outbound kerak: $types", types.contains("direct"))
    }
}
