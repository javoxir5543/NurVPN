package com.nurvpn.app.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ★ MASQUE `Server` ↔ `PublicKey` juftligi + h2 majburlash QULFI.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NEGA BU TEST BOR
 * ═══════════════════════════════════════════════════════════════════════════
 * 2026-11-15 da qurilmada (Redmi Note 10S, mobil internet) quyidagi xato
 * 693 marta takrorlandi va VPN butunlay ishlamadi:
 *
 *     masque: remote endpoint has a different public key than pinned
 *
 * Sabab: `BuiltinAwgConfigs.kt` da `Server` bir necha marta o'zgartirilgan,
 * `PublicKey` esa 2026-10 dan beri o'zgarmagan. Yadro TLS sertifikatining
 * SPKI kalitini `public_key` bilan solishtiradi — mos kelmasa tunnel
 * ochilmaydi.
 *
 * ⚠️ KALIT GURUHLARI TLS ORQALI O'LCHANGAN (SNI=4pda.to) — `197.x` oilasi
 *    ARALASH, raqamga qarab taxmin qilib bo'lmaydi:
 *
 *        GURUH A: 197.1 197.3 197.4 192.1 193.1 194.1 195.1 196.1
 *        GURUH B: 197.2 197.5 198.2 199.2
 *
 *    Yangi server qo'shsangiz, kalitini o'lchang:
 *        python3 tools/fix_pubkey_pairing.py --probe <ip>
 *
 * ⚠️ `PublicKey` X25519 GA O'GIRILMASIN. Uch marta urinilgan va rad etilgan
 *    (qurilmada, 2026-11-15):
 *        32 bayt (`bmXOC…`)  → "parse public_key: asn1: structure error"
 *        44 bayt (PKIX DER)  → "parse public_key: public_key is not an ECDSA key"
 *        PrivateKey X25519   → "parse private_key: x509: failed to parse EC private key"
 *    Yadro `x509.ParsePKIXPublicKey` ishlatadi — P-256 ECDSA (91 bayt) SHART.
 */
@RunWith(RobolectricTestRunner::class)
// ⚠️ `sdk = [33]` — loyihadagi boshqa testlar bilan bir xil. Robolectric
//    `android-all-instrumented` JAR'ini Maven'dan yuklaydi; `--offline`
//    rejimida faqat cache'dagi SDK ishlaydi (13/14 mavjud, 28 YO'Q).
@Config(sdk = [33])
class MasquePubkeyPairingTest {

    /** GURUH A — TLS SPKI, SNI=4pda.to (o'lchangan 2026-11-15). */
    private val keyA =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEab5PD8DnqxlOcsLgHX9r/6BTUf8x0JX7" +
        "eNbWwRQb5dzmIDq7b3qaidcGNR/qxBCaa8reMTK63bMGpwW9J/H59w=="

    /** GURUH B — TLS SPKI, SNI=4pda.to (o'lchangan 2026-11-15). */
    private val keyB =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI" +
        "8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w=="

    private val privKey =
        "MHcCAQEEIA5BbtEj6iVSyB9a3IcrwtKCsSPvL5zLovQUR7QfyrntoAoGCCqGSM49" +
        "AwEHoUQDQgAEHj6Iq8fpkjKfI3EQXnIFebRXCvgiUGX8RG6SZuljZ13QR9unzjBkiY" +
        "/kQebt5BO7ZLsFMeQ5YLWgMzpctEizNQ=="

    private fun conf(server: String, publicKey: String, network: String = "h2") = """
        PrivateKey = $privKey
        PublicKey = $publicKey
        Address = 172.16.0.2
        MTU = 1280
        Server = $server
        Port = 443
        SNI = 4pda.to
        Network = $network
        Profile = cloudflare
    """.trimIndent()

    private fun parse(server: String, publicKey: String, network: String = "h2"): JSONObject =
        SingBoxConfig.masqueOutbound(conf(server, publicKey, network))

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Server ↔ PublicKey juftligi
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `server JIMGINA almashtirilmaydi`() {
        // ★ RUNTIME ALMASHTIRISH OLIB TASHLANGAN.
        //
        //   Ilgari A guruhi (197.1) jimgina `198.2` ga o'tkazilardi:
        //   UI bir narsani ko'rsatib, trafik boshqasiga ketardi. Bu
        //   config-match xatosining boshqa ko'rinishi edi.
        //
        //   Endi ma'lumot `BuiltinAwgConfigs.kt` da to'g'rilangan.
        //   Foydalanuvchi nima tanlasa — o'sha ishlatiladi.
        //
        //   ⚠️ Runtime almashtirish QAYTA KIRITILMASIN.
        val o = parse("162.159.197.1", keyB)
        assertEquals("Server foydalanuvchi tanlaganicha qolishi shart",
            "162.159.197.1", o.getString("server"))
        assertEquals("Kalit GURUH A ga tuzatilishi shart", keyA, o.getString("public_key"))
    }

    @Test
    fun `A guruhi serverlari ozgartirilmaydi faqat kalit tuzatiladi`() {
        val groupA = listOf("162.159.197.1", "162.159.197.3", "162.159.197.4",
            "162.159.192.1", "162.159.193.1", "162.159.194.1",
            "162.159.195.1", "162.159.196.1")
        for (s in groupA) {
            val o = parse(s, keyB)
            assertEquals("Server=$s o'zgartirilmasligi shart",
                s, o.getString("server"))
            assertEquals("Server=$s kaliti GURUH A ga tuzatilishi shart",
                keyA, o.getString("public_key"))
        }
    }

    @Test
    fun `GURUH B serveri GURUH A kaliti bilan kelsa tuzatiladi`() {
        val o = parse("162.159.198.2", keyA)
        assertEquals("Server o'zgarmaydi (allaqachon B)",
            "162.159.198.2", o.getString("server"))
        assertEquals("Kalit GURUH B ga o'girilishi shart", keyB, o.getString("public_key"))
    }

    @Test
    fun `togri juftlik ozgarmaydi`() {
        val o = parse("162.159.198.2", keyB)
        assertEquals(keyB, o.getString("public_key"))
    }

    @Test
    fun `nomalum server kalitni ozgartirmaydi`() {
        // Noma'lum server — TEGILMAYDI. Foydalanuvchi qo'lda kiritgan
        // server uchun kalitni biz bilmaymiz; o'zgartirsak yana buzamiz.
        val o = parse("10.0.0.1", keyA)
        assertEquals(
            "Noma'lum server uchun kalit saqlanishi shart",
            keyA, o.getString("public_key"))
    }

    @Test
    fun `foydalanuvchining oz kaliti TEGILMAYDI`() {
        // ⚠️ ENG MUHIM XAVFSIZLIK TESTI.
        //
        // Yuklash manbalari (BuiltinAwgConfigs, MASQUE editor, YAML import,
        // obuna) hammasi `parseMasque` dan o'tadi. Foydalanuvchi O'Z kalitini
        // qo'ygan bo'lishi mumkin:
        //   * Cloudflare WARP kalitni yangilagan
        //   * korporativ/zero-trust WARP profili
        //   * boshqa akkaunt
        //
        // Jadval bo'yicha almashtirsak — BIZ uning ulanishini buzamiz.
        // Faqat eski A/B kalitlar (bizning tarixiy xatomiz) tuzatiladi.
        val userKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" +
            "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3A="
        // GURUH B serverida — kalit TEGILMAYDI.
        val o = parse("162.159.198.2", userKey)
        assertEquals(
            "Foydalanuvchi kaliti o'zgarishsiz qolishi shart",
            userKey, o.getString("public_key"))
    }

    @Test
    fun `foydalanuvchi kaliti barcha malum serverlarda saqlanadi`() {
        val userKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" +
            "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3A="
        for (server in listOf("162.159.198.2", "162.159.197.2",
                              "162.159.197.5", "162.159.199.2")) {
            val o = parse(server, userKey)
            assertEquals(
                "Server=$server da foydalanuvchi kaliti saqlanishi shart",
                userKey, o.getString("public_key"))
        }
    }

    @Test
    fun `faqat A yoki B kalit almashtiriladi`() {
        // Tarixiy xatomiz: A va B kalitlar aralashib ketgan. Faqat shular
        // tuzatiladi.
        for (server in listOf("162.159.198.2", "162.159.197.2")) {
            val oA = parse(server, keyA)
            val oB = parse(server, keyB)
            assertTrue("A/B kalit jadvaldagisi bo'lishi shart",
                oA.getString("public_key").let { it == keyA || it == keyB })
            assertTrue("A/B kalit jadvaldagisi bo'lishi shart",
                oB.getString("public_key").let { it == keyA || it == keyB })
        }
    }

    @Test
    fun `GURUH A va GURUH B kalitlari har xil`() {
        // Agar kimdir ikkalasini bir xil qilib qo'ysa — pin mantig'i
        // ma'nosiz bo'lib qoladi.
        assertNotEquals("Kalit guruhlari har xil bo'lishi shart", keyA, keyB)
    }

    @Test
    fun `Endpoint kaliti Server kabi tuzatiladi`() {
        val c = """
            PrivateKey = $privKey
            PublicKey = $keyB
            Address = 172.16.0.2
            Endpoint = 162.159.197.1:443
            SNI = 4pda.to
            Network = h2
        """.trimIndent()
        val o = SingBoxConfig.masqueOutbound(c)
        assertEquals("Endpoint o'zgartirilmasligi shart",
            "162.159.197.1", o.getString("server"))
        assertEquals("Kalit GURUH A ga tuzatilishi shart",
            keyA, o.getString("public_key"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. h2 majburlash (FIX_FORCE_H2_CLEAN)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `h3 majburan h2 ga ogiriladi`() {
        // QUIC/UDP 443 ko'p mobil operatorlarda bloklanadi (TSPU).
        val o = parse("162.159.198.2", keyB, network = "h3")
        assertEquals("h3 -> h2 majburiy", "h2", o.getString("vhttp"))
    }

    @Test
    fun `auto majburan h2 ga ogiriladi`() {
        // `auto` = avval h3 sinaydi -> mobil internetda vaqt yo'qotadi.
        val o = parse("162.159.198.2", keyB, network = "auto")
        assertEquals("auto -> h2 majburiy", "h2", o.getString("vhttp"))
    }

    @Test
    fun `h2 h2 bolib qoladi`() {
        val o = parse("162.159.198.2", keyB, network = "h2")
        assertEquals("h2", o.getString("vhttp"))
    }

    @Test
    fun `Network yozilmasa ham vhttp h2`() {
        val c = """
            PrivateKey = $privKey
            PublicKey = $keyB
            Address = 172.16.0.2
            Server = 162.159.198.2
            SNI = 4pda.to
        """.trimIndent()
        val o = SingBoxConfig.masqueOutbound(c)
        assertEquals("Network yo'q bo'lsa ham h2", "h2", o.getString("vhttp"))
    }

    @Test
    fun `vhttp faqat h2 qiymat oladi`() {
        // Yadro faqat "h2"/"h3" STRING qabul qiladi. Boolean yoki
        // boshqa qiymat -> "masque: invalid vhttp".
        for (net in listOf("h2", "h3", "auto", "H2", "AUTO", "quic")) {
            val o = parse("162.159.198.2", keyB, network = net)
            assertEquals(
                "Network=$net uchun vhttp 'h2' bo'lishi shart",
                "h2", o.getString("vhttp"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. PublicKey formati (X25519 regressiyasiga qarshi qulf)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `PublicKey P-256 ECDSA PKIX bolishi shart`() {
        // ⚠️ X25519 URINILGAN VA RAD ETILGAN (qurilmada):
        //     32 bayt  -> "asn1: structure error"
        //     44 bayt  -> "public_key is not an ECDSA key"
        // Yadro x509.ParsePKIXPublicKey ishlatadi.
        val o = parse("162.159.198.2", keyB)
        val b64 = o.getString("public_key")
        val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)

        assertEquals("PublicKey 91 bayt bo'lishi shart (X25519 = 32/44)", 91, raw.size)
        assertEquals("PKIX DER SEQUENCE (0x30) bilan boshlanishi shart",
            0x30.toByte(), raw[0])
        assertEquals("SEQUENCE uzunligi 0x59 (89 bayt) bo'lishi shart",
            0x59.toByte(), raw[1])
    }

    @Test
    fun `PrivateKey P-256 EC bolishi shart`() {
        // ⚠️ X25519 PrivateKey -> "x509: failed to parse EC private key".
        val o = parse("162.159.198.2", keyB)
        val raw = android.util.Base64.decode(
            o.getString("private_key"), android.util.Base64.DEFAULT)

        assertTrue("PrivateKey 100+ bayt P-256 EC DER bo'lishi shart", raw.size > 60)
        assertEquals("DER SEQUENCE (0x30)", 0x30.toByte(), raw[0])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Boshqa maydonlar saqlanadi
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `boshqa maydonlar ozgarmaydi`() {
        val o = parse("162.159.198.2", keyB)
        assertEquals("162.159.198.2", o.getString("server"))
        assertEquals(443, o.getInt("server_port"))
        assertEquals("172.16.0.2", o.getString("ip"))
        assertEquals("4pda.to", o.getString("sni"))
        assertEquals("cloudflare", o.getString("profile"))
        assertEquals(1280, o.getInt("mtu"))
    }

    @Test
    fun `Tez-tez uchraydigan 11 config juftligi togri`() {
        // `BuiltinAwgConfigs.kt` dagi haqiqiy taqsimot (2026-11-15).
        val cases = listOf(
            "162.159.198.2" to "B", "162.159.199.2" to "B",
            "162.159.197.2" to "B", "162.159.197.5" to "B",
            "162.159.197.1" to "A", "162.159.197.3" to "A",
            "162.159.192.1" to "A", "162.159.193.1" to "A",
            "162.159.194.1" to "A", "162.159.195.1" to "A",
            "162.159.196.1" to "A", "162.159.197.4" to "A",
        )
        for ((server, want) in cases) {
            val wrong = if (want == "A") keyB else keyA
            val o = parse(server, wrong)
            if (want == "A") {
                // Kalit A ga tuzatiladi, server O'ZGARMAYDI.
                assertEquals("Server=$server (A) o'zgartirilmasligi shart",
                    server, o.getString("server"))
                assertEquals("Server=$server kaliti A bo'lishi shart",
                    keyA, o.getString("public_key"))
            } else {
                assertEquals("Server=$server (B) o'zgarmaydi",
                    server, o.getString("server"))
                assertEquals("Server=$server kaliti B bo'lishi shart",
                    keyB, o.getString("public_key"))
            }
        }
    }
}
