package com.nurvpn.app.util

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.spec.NamedParameterSpec

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  FIX161 — Cloudflare WARP (MASQUE) generator
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  Cloudflare `/reg` API'sidan shaxsiy WARP konfiguratsiyasini oladi va
 *  uni ilova tushunadigan MASQUE `.conf` matniga o'giradi.
 *
 *  ── OQIM ──
 *   1. X25519 kalit juftligini generatsiya qilamiz
 *   2. Xom 32 baytlik kalitlarni Base64 ga o'giramiz
 *   3. `POST /reg` ga yuboramiz
 *   4. Javobdan peer public key, address, endpoint ajratamiz
 *   5. MASQUE `.conf` matnini quramiz
 *
 *  ── ⚠️ ENG NOZIK JOYI: KALIT KODLASH ──
 *
 *  `KeyPairGenerator.getInstance("X25519").generateKeyPair()` ning
 *  `getEncoded()` metodi **XOM kalitni bermaydi**:
 *
 *      Public : 44 bayt = 12 bayt SPKI header + 32 bayt kalit
 *                         (302a300506032b656e032100)
 *      Private: 48 bayt = 16 bayt PKCS8 header + 32 bayt kalit
 *                         (302e020100300506032b656e04220420)
 *
 *  WireGuard esa XOM 32 bayt (Base64) kutadi. Shuning uchun
 *  `stripKeyHeader()` bilan headerni kesib tashlaymiz.
 *
 *  Bu joy xato bo'lsa — WARP ulanmaydi, lekin XATO KO'RINMAYDI
 *  (server shunchaki javob bermaydi). Shuning uchun `selftest()` bor.
 *
 *  ── XAVFSIZLIK ──
 *  Barcha tarmoq amallari CHAQIRUVCHI oqimda (fon `Thread` ichida)
 *  bajariladi. Bu obyekt UI oqimini BLOKLAMAYDI.
 */
object WarpGenerator {

    private const val TAG = "NurVPN-WARP"
    private const val API = "https://api.cloudflareclient.com/v0a2158/reg"

    // ═══ ASN.1 PREFIX'LAR ═══
    // X25519 SPKI (SubjectPublicKeyInfo) — 12 bayt
    private const val SPKI_PREFIX = "302a300506032b656e032100"
    // X25519 PKCS#8 (PrivateKeyInfo) — 16 bayt
    private const val PKCS8_PREFIX = "302e020100300506032b656e04220420"

    /** WireGuard kalit uzunligi (xom). */
    private const val RAW_KEY_LEN = 32

    /** Natija: muvaffaqiyat bo'lsa `conf`, xato bo'lsa `error`. */
    class Result(val conf: String?, val endpoint: String?, val error: String?) {
        val ok: Boolean get() = conf != null
    }

    // ═══════════════════════════════════════════════════════════════
    //  1. KALIT GENERATSIYASI
    // ═══════════════════════════════════════════════════════════════

    /**
     * ASN.1 headersiz XOM kalit (Base64).
     *
     * @param private `true` — PKCS#8 (16 bayt header),
     *                `false` — SPKI (12 bayt header)
     */
    internal fun stripKeyHeader(encoded: ByteArray, isPrivate: Boolean): String? {
        val prefixHex = if (isPrivate) PKCS8_PREFIX else SPKI_PREFIX
        val prefix = hexToBytes(prefixHex)
        if (encoded.size < prefix.size + RAW_KEY_LEN) return null
        // ⚠️ Prefiksni TEKSHIRAMIZ — aks holda noto'g'ri kalit jimgina ketadi
        for (i in prefix.indices) {
            if (encoded[i] != prefix[i]) {
                Log.w(TAG, "kalit prefiksi mos emas (${if (isPrivate) "PKCS8" else "SPKI"})")
                return null
            }
        }
        val raw = encoded.copyOfRange(prefix.size, prefix.size + RAW_KEY_LEN)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /**
     * X25519 juftlik. `Pair(publicB64, privateB64)`.
     *
     * ★ PLATFORMA X25519 → SOF KOTLIN ZAXIRA
     *
     * `KeyPairGenerator.getInstance("X25519")` **faqat Android 13
     * (API 33)+** da mavjud. Ilova `minSdk 24`. Haqiqiy qurilmada
     * (Android 10 / API 29) sinov:
     *
     *     NoSuchAlgorithmException: X25519 KeyPairGenerator not available
     *
     * Shuning uchun avval platformani sinaymiz, ishlamasa o'zimizning
     * `X25519` implementatsiyamizga o'tamiz. Platforma versiyasi
     * tezroq (native), lekin ikkalasi ham bir xil natija beradi.
     */
    internal fun generateKeyPair(): Pair<String, String>? {
        // 1) Platforma (API 33+)
        try {
            val kpg = KeyPairGenerator.getInstance("X25519")
            try { kpg.initialize(NamedParameterSpec.X25519) } catch (ignored: Throwable) {}
            val kp = kpg.generateKeyPair()
            val pub = stripKeyHeader(kp.public.encoded, isPrivate = false)
            val prv = stripKeyHeader(kp.private.encoded, isPrivate = true)
            if (pub != null && prv != null) {
                Log.i(TAG, "X25519: platforma implementatsiyasi")
                return Pair(pub, prv)
            }
        } catch (ignored: Throwable) {
            // API 33 dan past — pastdagi zaxiraga o'tamiz
        }

        // 2) Sof Kotlin zaxirasi (API 24+)
        return try {
            val priv = ByteArray(32)
            java.security.SecureRandom().nextBytes(priv)
            val pub = com.nurvpn.app.util.X25519.publicFromPrivate(priv)
            val pubB64 = Base64.encodeToString(pub, Base64.NO_WRAP)
            val prvB64 = Base64.encodeToString(priv, Base64.NO_WRAP)
            Log.i(TAG, "X25519: sof Kotlin implementatsiyasi")
            Pair(pubB64, prvB64)
        } catch (t: Throwable) {
            Log.e(TAG, "X25519 generatsiya xatosi", t)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  2. CLOUDFLARE API
    // ═══════════════════════════════════════════════════════════════

    /** ⚠️ Fon oqimida chaqirilishi SHART. */
    fun generate(): Result {
        val (pub, prv) = try {
            generateKeyPair() ?: return Result(null, null, "kalit_generatsiya")
        } catch (t: Throwable) {
            return Result(null, null, "kalit_generatsiya")
        }

        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("key", pub)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", "2020-09-01T00:00:00.000+00:00")
                put("model", "PC")
                put("serial_number", "")
                put("locale", "en_US")
            }.toString()

            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json")
                // ⚠️ Cloudflare boshqa User-Agent'ni rad etadi
                setRequestProperty("User-Agent", "okhttp/3.12.1")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "API HTTP $code")
                return Result(null, null, "api_$code")
            }

            parseAndBuild(text, prv)
        } catch (t: Throwable) {
            Log.e(TAG, "tarmoq xatosi", t)
            Result(null, null, "tarmoq")
        } finally {
            try { conn?.disconnect() } catch (ignored: Throwable) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  3. JAVOBNI TAHLIL QILISH + .CONF QURISH
    // ═══════════════════════════════════════════════════════════════

    internal fun parseAndBuild(json: String, privateKey: String): Result {
        return try {
            val root = JSONObject(json)
            val cfg = root.optJSONObject("config")
                ?: return Result(null, null, "config_yoq")
            val iface = cfg.optJSONObject("interface")
                ?: return Result(null, null, "interface_yoq")

            val peers = cfg.optJSONArray("peers")
            if (peers == null || peers.length() == 0) {
                return Result(null, null, "peer_yoq")
            }
            val peer = peers.getJSONObject(0)

            val serverPub = peer.optString("public_key", "")
            val endpoint = peer.optJSONObject("endpoint")?.optString("host", "") ?: ""

            // IPv4 va IPv6 manzillar
            val addrs = ArrayList<String>()
            val a4 = iface.optJSONArray("addresses")
            if (a4 != null) {
                for (i in 0 until a4.length()) {
                    val v = a4.optJSONObject(i)?.optString("address", "") ?: ""
                    if (v.isNotEmpty()) addrs.add(v)
                }
            }
            // Zaxira: ba'zi javoblarda `address` alohida
            if (addrs.isEmpty()) {
                val alt = iface.optString("address", "")
                if (alt.isNotEmpty()) addrs.add(alt)
            }

            if (serverPub.isEmpty() || endpoint.isEmpty() || addrs.isEmpty()) {
                Log.w(TAG, "javob to'liq emas: pub=$serverPub ep=$endpoint n=${addrs.size}")
                return Result(null, null, "javob_toliq_emas")
            }

            // ═══ MASQUE .conf ═══
            val sb = StringBuilder()
            sb.append("PrivateKey = ").append(privateKey).append('\n')
            sb.append("PublicKey = ").append(serverPub).append('\n')
            sb.append("Address = ").append(addrs.joinToString(", ")).append('\n')
            sb.append("MTU = 1280\n")
            val host = endpoint.substringBefore(':')
            val port = endpoint.substringAfter(':', "443")
            sb.append("Server = ").append(host).append('\n')
            sb.append("Port = ").append(port).append('\n')
            sb.append("SNI = ").append(host).append('\n')
            sb.append("Network = h2\n")
            sb.append("Profile = cloudflare")

            Log.i(TAG, "MASQUE .conf tayyor: $host:$port")
            Result(sb.toString(), "$host:$port", null)
        } catch (t: Throwable) {
            Log.e(TAG, "javob parse xatosi", t)
            Result(null, null, "parse")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  4. YORDAMCHILAR
    // ═══════════════════════════════════════════════════════════════

    internal fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
                      Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /**
     * ★ O'Z-O'ZINI TEKSHIRISH (`demo()` naqshi).
     *
     * Kalit kodlash mantig'ini tekshiradi — bu eng nozik joy.
     * Log'da `WARP selftest: OK` ko'rinsa, kalitlar to'g'ri.
     */
    fun selftest(): Boolean {
        return try {
            // 1) RFC 7748 vektorlari (sof Kotlin mantig'i)
            if (!X25519.selftest()) {
                Log.e(TAG, "selftest: RFC 7748 vektorlari XATO")
                return false
            }
            // 2) Kalit juftligi uzunligi
            val (pub, prv) = generateKeyPair() ?: return false
            val pubBytes = Base64.decode(pub, Base64.DEFAULT)
            val prvBytes = Base64.decode(prv, Base64.DEFAULT)
            val ok = pubBytes.size == RAW_KEY_LEN && prvBytes.size == RAW_KEY_LEN
            if (!ok) Log.e(TAG, "selftest: noto'g'ri uzunlik ${pubBytes.size}/${prvBytes.size}")
            if (ok) Log.i(TAG, "selftest: OK")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "selftest xatosi", t)
            false
        }
    }
}
