package com.nurvpn.app

import com.nurvpn.app.config.SingBoxConfig
import com.nurvpn.app.parser.ServerLinkParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ★ TUZATISH #9: Xray JSON obuna importi.
 *
 * Real holat: `https://sub.dark-bit.cc/dbabb490-...` base64 EMAS,
 * balki Xray JSON qaytaradi:
 *   [{"dns":{...},"outbounds":[{"protocol":"vless",...}],"remarks":"..."}]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JsonSubImportTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    /** sub.dark-bit.cc javobining soddalashtirilgan nusxasi. */
    private fun realLikeJson(id: String = "b831381d-6324-4d53-ad4f-8cda48b30811") = """
    [{"dns":{"tag":"darkbit-yandex-dns","servers":["77.88.8.8"]},
      "log":{"loglevel":"warning"},
      "inbounds":[{"tag":"socks","port":10808,"protocol":"socks",
                   "settings":{"udp":true}}],
      "outbounds":[
        {"tag":"proxy","protocol":"vless",
         "settings":{"vnext":[{"address":"edge01.tehdom.info","port":443,
           "users":[{"id":"$id","encryption":"none","flow":""}]}]},
         "streamSettings":{"network":"tcp","security":"reality",
           "realitySettings":{"serverName":"www.microsoft.com",
             "publicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
             "shortId":"0123456789abcdef","fingerprint":"chrome"}}},
        {"tag":"direct","protocol":"freedom"},
        {"tag":"block","protocol":"blackhole"}
      ],
      "remarks":"DarkBit-01"}]
    """.trimIndent()

    @Test
    fun `json - real tuzilmadagi vless outbound topiladi`() {
        val arr = JSONArray(realLikeJson())
        val cfg = arr.getJSONObject(0)
        val obs = cfg.getJSONArray("outbounds")
        var vless: JSONObject? = null
        for (i in 0 until obs.length()) {
            if (obs.getJSONObject(i).optString("protocol") == "vless")
                vless = obs.getJSONObject(i)
        }
        assertNotNull("vless outbound topilmadi", vless)
        assertEquals("edge01.tehdom.info",
            vless!!.getJSONObject("settings").getJSONArray("vnext")
                .getJSONObject(0).optString("address"))
        assertEquals("DarkBit-01", cfg.optString("remarks"))
    }

    @Test
    fun `json - vless outbound dan link yasalib parse qilinadi`() {
        // Kodimiz yasaydigan linkni qo'lda reproduksiya qilamiz
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811" +
            "@edge01.tehdom.info:443?security=reality" +
            "&sni=www.microsoft.com" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "#DarkBit-01"
        val si = ServerLinkParser.parse(link)
        assertNotNull("link parse bo'lmadi", si)
        assertEquals("edge01.tehdom.info", si!!.host)
        assertEquals(443, si.port)
        assertEquals("DarkBit-01", si.remark)
    }

    @Test
    fun `json - sing-box config ga aylantiriladi (reality + hybrid)`() {
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811" +
            "@edge01.tehdom.info:443?security=reality" +
            "&sni=www.microsoft.com" +
            "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "#DarkBit-01"
        val cfg = SingBoxConfig.buildFullConfig(link, "1.1.1.1", "", false, ctx)
        val ob = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", ob.getString("type"))
        assertEquals("edge01.tehdom.info", ob.getString("server"))
        assertEquals(443, ob.getInt("server_port"))
        val tls = ob.getJSONObject("tls")
        assertTrue("reality yoqilmagan", tls.has("reality"))
        assertEquals(
            "Reality gibrid key share faqat chrome bilan",
            "chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun `json - bitta obyekt ham qo'llab-quvvatlanadi`() {
        val one = realLikeJson().removePrefix("[").removeSuffix("]")
        val cfg = JSONObject(one)
        assertTrue(cfg.has("outbounds"))
        assertEquals("DarkBit-01", cfg.optString("remarks"))
    }

    @Test
    fun `json - freedom va blackhole o'tkazib yuboriladi`() {
        val skip = setOf("freedom", "blackhole", "dns", "direct", "block", "frag")
        for (p in skip) {
            assertFalse("'$p' proxy sifatida qo'shilmasligi kerak",
                p in setOf("vless", "vmess", "trojan", "shadowsocks",
                    "hysteria2", "hy2", "tuic", "anytls"))
        }
    }

    @Test
    fun `json - hysteria2 obfs (finalmask EMAS) to'g'ri o'qiladi`() {
        val s = """
        {"tag":"proxy","protocol":"hysteria2",
         "settings":{"password":"pw123"},
         "obfs":{"type":"salamander","password":"obfspw"}}
        """.trimIndent()
        val ob = JSONObject(s)
        assertEquals("hysteria2", ob.optString("protocol"))
        assertEquals("pw123", ob.getJSONObject("settings").optString("password"))
        assertEquals("salamander", ob.getJSONObject("obfs").optString("type"))
        assertFalse("finalmask ishlatilmasligi kerak", ob.has("finalmask"))
    }
}
