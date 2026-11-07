package com.nurvpn.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AWGParser testlari — WireGuard / AmneziaWG / MASQUE / Clash YAML.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NEGA MUHIM
 * ═══════════════════════════════════════════════════════════════════════════
 * Bu parser foydalanuvchi qo'lda yopishtirgan `.conf` va Clash/Mihomo
 * YAML'ini o'qiydi. v1.12.0 da MASQUE 1-5 va AmneziaWG 1-3 konfiguratsiyalari
 * moslashtirildi — bu testlar o'sha formatlarni qulflaydi.
 *
 * `parse()` xato holatlarda `error` maydonini to'ldiradi, `ok=false` qoldiradi.
 * Bu xatti-harakat UI'da xato xabarini ko'rsatish uchun ishlatiladi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AWGParserTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. isMasqueConf — MASQUE aniqlash
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `masque - to'g'ri konfiguratsiya aniqlanadi`() {
        val conf = """
            PrivateKey = aGVsbG8xMjM0NTY3ODkwYWJjZGVmZ2hpamts
            PublicKey = d29ybGQ5ODc2NTQzMjEwYWJjZGVmZ2hpamts
            Server = masque.example.com
            Address = 172.16.0.2
        """.trimIndent()
        assertTrue("MASQUE conf aniqlanishi kerak", AWGParser.isMasqueConf(conf))
    }

    @Test
    fun `masque - Endpoint bilan ham aniqlanadi`() {
        val conf = """
            PrivateKey = abc
            PublicKey = def
            Endpoint = m.example.com:443
        """.trimIndent()
        assertTrue(AWGParser.isMasqueConf(conf))
    }

    @Test
    fun `masque - Interface bo'lsa MASQUE emas`() {
        val conf = """
            [Interface]
            PrivateKey = abc
            [Peer]
            PublicKey = def
            Server = x.com
        """.trimIndent()
        assertFalse("Interface bo'lsa klassik WireGuard", AWGParser.isMasqueConf(conf))
    }

    @Test
    fun `masque - AmneziaWG parametrlari bo'lsa MASQUE emas`() {
        val conf = """
            PrivateKey = abc
            PublicKey = def
            Server = x.com
            Jc = 3
            Jmin = 50
        """.trimIndent()
        assertFalse("AmneziaWG parametrlari MASQUE emas", AWGParser.isMasqueConf(conf))
    }

    @Test
    fun `masque - Server ham Endpoint ham bo'lmasa MASQUE emas`() {
        val conf = """
            PrivateKey = abc
            PublicKey = def
        """.trimIndent()
        assertFalse(AWGParser.isMasqueConf(conf))
    }

    @Test
    fun `masque - PublicKey bo'lmasa MASQUE emas`() {
        val conf = """
            PrivateKey = abc
            Server = x.com
        """.trimIndent()
        assertFalse(AWGParser.isMasqueConf(conf))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. parse() — klassik WireGuard
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `parse - to'g'ri WireGuard conf muvaffaqiyatli`() {
        val conf = """
            [Interface]
            PrivateKey = privkey123
            Address = 10.0.0.2/32
            DNS = 1.1.1.1
            MTU = 1420

            [Peer]
            PublicKey = pubkey456
            Endpoint = vpn.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent()

        val r = AWGParser.parse(conf)
        assertTrue("Parse muvaffaqiyatli bo'lishi kerak: ${r.error}", r.ok)
        assertEquals("vpn.example.com:51820", r.endpoint)
        assertEquals("10.0.0.2/32", r.address)
        assertTrue("Xato bo'lmasligi kerak", r.error.isEmpty())
    }

    @Test
    fun `parse - bo'sh conf xato qaytaradi`() {
        val r = AWGParser.parse("")
        assertFalse(r.ok)
        assertTrue("Xato xabari bo'lishi kerak", r.error.isNotEmpty())
    }

    @Test
    fun `parse - faqat bo'sh joy xato qaytaradi`() {
        val r = AWGParser.parse("   \n\n  \t ")
        assertFalse(r.ok)
    }

    @Test
    fun `parse - Interface bo'lmasa xato`() {
        val conf = """
            [Peer]
            PublicKey = pub
            Endpoint = x.com:51820
        """.trimIndent()
        val r = AWGParser.parse(conf)
        assertFalse("Interface majburiy", r.ok)
        assertTrue(r.error.contains("Interface"))
    }

    @Test
    fun `parse - Peer bo'lmasa xato`() {
        val conf = """
            [Interface]
            PrivateKey = priv
            Address = 10.0.0.2
        """.trimIndent()
        val r = AWGParser.parse(conf)
        assertFalse("Peer majburiy", r.ok)
    }

    @Test
    fun `parse - Endpoint bo'lmasa xato`() {
        val conf = """
            [Interface]
            PrivateKey = priv
            [Peer]
            PublicKey = pub
        """.trimIndent()
        val r = AWGParser.parse(conf)
        assertFalse(r.ok)
        assertTrue("Endpoint xatosi kerak", r.error.contains("Endpoint"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Versiya aniqlash (AmneziaWG 2.0 / 3.1)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `versiya - oddiy WireGuard 2_0`() {
        val conf = """
            [Interface]
            PrivateKey = priv
            [Peer]
            PublicKey = pub
            Endpoint = x.com:51820
        """.trimIndent()
        assertEquals("2.0", AWGParser.parse(conf).version)
    }

    @Test
    fun `versiya - Jc bo'lsa 3_1`() {
        val conf = """
            [Interface]
            PrivateKey = priv
            Jc = 3
            Jmin = 50
            [Peer]
            PublicKey = pub
            Endpoint = x.com:51820
        """.trimIndent()
        assertEquals("AmneziaWG parametrlari 3.1 versiyani bildiradi", "3.1", AWGParser.parse(conf).version)
    }

    @Test
    fun `versiya - I1 bo'lsa 3_1`() {
        val conf = """
            [Interface]
            PrivateKey = priv
            I1 = 1234
            [Peer]
            PublicKey = pub
            Endpoint = x.com:51820
        """.trimIndent()
        assertEquals("3.1", AWGParser.parse(conf).version)
    }

    @Test
    fun `versiya - S1 bo'lsa 3_1`() {
        val conf = """
            [Interface]
            PrivateKey = priv
            S1 = 42
            [Peer]
            PublicKey = pub
            Endpoint = x.com:51820
        """.trimIndent()
        assertEquals("3.1", AWGParser.parse(conf).version)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. expand()
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `expand - klassik conf o'zgarishsiz qaytadi`() {
        val conf = "[Interface]\nPrivateKey = x\n[Peer]\nPublicKey = y"
        val out = AWGParser.expand(conf)
        assertEquals(1, out.size)
        assertEquals(conf, out[0])
    }

    @Test
    fun `expand - MASQUE conf o'zgarishsiz qaytadi`() {
        val conf = "PrivateKey = a\nPublicKey = b\nServer = c.com"
        val out = AWGParser.expand(conf)
        assertEquals(1, out.size)
    }

    @Test
    fun `expand - noma'lum matn o'zgarishsiz qaytadi (yiqilmaydi)`() {
        val out = AWGParser.expand("bu shunchaki matn")
        assertEquals(1, out.size)
        assertEquals("bu shunchaki matn", out[0])
    }

    @Test
    fun `expand - bo'sh matn bilan ishlaydi`() {
        val out = AWGParser.expand("")
        assertEquals(1, out.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Clash / Mihomo YAML
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `yaml - oddiy wireguard proxy konvertatsiya qilinadi`() {
        val yaml = """
            proxies:
              - name: "WG-Server"
                type: wireguard
                server: wg.example.com
                port: 51820
                ip: 10.0.0.2
                private-key: privkey123
                public-key: pubkey456
                dns: 1.1.1.1
                mtu: 1420
        """.trimIndent()

        val out = AWGParser.expand(yaml)
        assertTrue("Kamida bitta conf chiqishi kerak, olindi: ${out.size}", out.isNotEmpty())
        val conf = out[0]
        assertTrue("Interface bo'lishi kerak", conf.contains("[Interface]"))
        assertTrue("Peer bo'lishi kerak", conf.contains("[Peer]"))
        assertTrue(conf.contains("PrivateKey = privkey123"))
        assertTrue(conf.contains("PublicKey = pubkey456"))
        assertTrue("Endpoint to'g'ri bo'lishi kerak: $conf",
            conf.contains("Endpoint = wg.example.com:51820"))
    }

    @Test
    fun `yaml - bir nechta proxy`() {
        val yaml = """
            proxies:
              - name: "WG-1"
                type: wireguard
                server: one.example.com
                port: 51820
                private-key: priv1
                public-key: pub1
                ip: 10.0.0.2
              - name: "WG-2"
                type: wireguard
                server: two.example.com
                port: 51821
                private-key: priv2
                public-key: pub2
                ip: 10.0.0.3
        """.trimIndent()

        val out = AWGParser.expand(yaml)
        assertEquals("Ikki proxy -> ikki conf", 2, out.size)
        assertTrue(out[0].contains("one.example.com"))
        assertTrue(out[1].contains("two.example.com"))
    }

    @Test
    fun `yaml - wireguard bo'lmagan proxy o'tkazib yuboriladi`() {
        val yaml = """
            proxies:
              - name: "SS"
                type: ss
                server: ss.example.com
                port: 8388
        """.trimIndent()
        val out = AWGParser.expand(yaml)
        // Natija YAML emas, balki asl matn qaytadi
        assertEquals(1, out.size)
        assertFalse("WireGuard bo'lmagan proxy conf bermasligi kerak",
            out[0].contains("[Interface]"))
    }

    @Test
    fun `yaml - AmneziaWG parametrlari konfga o'tadi`() {
        val yaml = """
            proxies:
              - name: "AWG"
                type: wireguard
                server: awg.example.com
                port: 51820
                private-key: priv
                public-key: pub
                ip: 10.0.0.2
                amnezia-wg-option:
                  jc: 4
                  jmin: 50
                  jmax: 1000
        """.trimIndent()

        val out = AWGParser.expand(yaml)
        assertTrue(out.isNotEmpty())
        val conf = out[0]
        assertTrue("AWG parametrlari o'tishi kerak:\n$conf",
            out.any { it.contains("Jc = 4") })
    }

    @Test
    fun `yaml - buzuq YAML yiqilishga olib kelmaydi`() {
        val bad = listOf(
            "proxies:\n  - {bu: buzuq",
            "proxies: [",
            "wireguard",
            "\t\tproxies:\n- type: wireguard",
            "proxies:\n" + "- x\n".repeat(500)
        )
        for (y in bad) {
            try {
                AWGParser.expand(y)
            } catch (t: Throwable) {
                throw AssertionError("expand() yiqildi: ${t.message}", t)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Chidamlilik — hech qachon exception tashlamasligi kerak
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `parse - har xil kirishlarda yiqilmaydi`() {
        val inputs = listOf(
            "\u0000\u0001",
            "[Interface]".repeat(1000),
            "[Peer]\n".repeat(1000),
            "Endpoint = " + "a".repeat(10000),
            "[interface]\nprivatekey=x\n[peer]\npublickey=y\nendpoint=z:1",
            "Endpoint=\nAddress=\n[Interface]\n[Peer]"
        )
        for (i in inputs) {
            try {
                AWGParser.parse(i)
            } catch (t: Throwable) {
                throw AssertionError("parse() yiqildi (${i.take(30)}): ${t.message}", t)
            }
        }
    }

    @Test
    fun `parse - kichik harfli bo'lim nomlari ishlaydi`() {
        val conf = """
            [interface]
            PrivateKey = priv
            [peer]
            PublicKey = pub
            Endpoint = x.com:51820
        """.trimIndent()
        val r = AWGParser.parse(conf)
        assertTrue("Bo'lim nomlari registrga sezgir bo'lmasligi kerak: ${r.error}", r.ok)
    }

    @Test
    fun `parse - tenglik atrofida bo'sh joylar`() {
        val conf = """
            [Interface]
            PrivateKey=priv
            [Peer]
            PublicKey=pub
            Endpoint=x.com:51820
        """.trimIndent()
        val r = AWGParser.parse(conf)
        assertTrue("Bo'sh joysiz '=' ham ishlashi kerak", r.ok)
        assertEquals("x.com:51820", r.endpoint)
    }
}
