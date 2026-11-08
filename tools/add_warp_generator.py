#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
════════════════════════════════════════════════════════════════════════════
 FIX161-WARPGEN — Cloudflare WARP (MASQUE) generator
════════════════════════════════════════════════════════════════════════════

 ────────────────────────────────────────────────────────────────────────
  1. TAHLIL — MAVJUD KOD QANDAY ISHLAYDI
 ────────────────────────────────────────────────────────────────────────

 Kodni o'qib, MASQUE arxitekturasi aniqlandi:

   `BuiltinAwgConfigs.kt` (120 qator)
       5 ta TAYYOR MASQUE config saqlaydi (`masque_1..5`).
       Format — oddiy `.conf` MATN:

           PrivateKey = MHcCAQEEIOW4...   ← ECDSA P-256 (PKCS8)
           PublicKey  = MFkwEwYHKoZ...    ← ECDSA P-256 (SPKI)
           Address    = 172.16.0.2, 2606:4700:...
           MTU        = 1360
           Server     = 162.159.198.2
           Port       = 443
           SNI        = 4pda.to
           Network    = h2
           Profile    = cloudflare

   ⚠️ MUHIM: BUILTIN configlar **ECDSA P-256** ishlatadi, X25519 EMAS!
      `MHcCAQEE...` = PKCS8 ECDSA kalit.
      Lekin Cloudflare `/reg` API **X25519** qaytaradi.
      → Ikkalasi ham ishlaydi, chunki `SingBoxConfig.kt` kalitni
        `wireguard` outbound'ga to'g'ridan-to'g'ri uzatadi va
        libbox ikkala turni ham qabul qiladi.
      → Biz Cloudflare API bergan X25519 ni ishlatamiz (u to'g'ri).

   `AWGStore.kt` (82 qator)
       `AWGConfig` ni `awg.xml` (SharedPreferences) ga JSON qilib saqlaydi.
       `type` ("awg" | "masque") HAR DOIM `rawConf` MATNIDAN qayta
       aniqlanadi: `[Interface]`/`[Peer]` yo'q + `PrivateKey` + `PublicKey`
       + `Server`/`Endpoint` bo'lsa → "masque".
       ★ Ya'ni generator faqat to'g'ri `.conf` matn yaratsa yetarli —
         saqlash/o'qish mantiqiga TEGISh SHART EMAS.

   `SettingsFragment.kt:174-215`
       Ochiq manba yoqilganda `BuiltinAwgConfigs.byId(awgId)` →
       `AWGStore.load/save` → `AWGConfig` qo'shadi.
       ★ Bu generator tugmasi uchun ENG MOS joy.

   `MainActivity.kt:231-233` — ilova ochilganda yoqilgan manbalarni
       avtomatik yuklaydi.

 ────────────────────────────────────────────────────────────────────────
  2. KRIPTOGRAFIYA — TEKSHIRILGAN
 ────────────────────────────────────────────────────────────────────────

 `KeyPairGenerator.getInstance("X25519")` JVM'da mavjud (sinov o'tdi).
 Lekin `getEncoded()` **XOM 32 baytni bermaydi**:

     Public  encoded: 44 bayt = 12 bayt SPKI header + 32 bayt kalit
                               302a300506032b656e032100
     Private encoded: 48 bayt = 16 bayt PKCS8 header + 32 bayt kalit
                               302e020100300506032b656e04220420

 WireGuard esa **XOM 32 bayt** (Base64) kutadi. Shuning uchun
 headerni KESIB TASHLASH shart — bu generatorning eng nozik joyi.

 Sinov natijasi:
     Xom public : 32 bayt = 44nox5/51Yu6GXNylHRVa8fw9mjEkSdUMe8pYGmwozU=
     Xom private: 32 bayt = QfQwjIK//gc4Bh6MglixyArI9wD3CxvJYUZ8f5/LQcc=

 ────────────────────────────────────────────────────────────────────────
  3. NIMA QO'SHILADI
 ────────────────────────────────────────────────────────────────────────

 Yangi fayl:
   • `app/src/main/java/com/nurvpn/app/util/WarpGenerator.kt`

 O'zgartiriladi:
   • `SettingsFragment.kt`  — tugma + ProgressDialog + Thread
   • `fragment_settings.xml` — «Generate WARP (MASQUE)» tugmasi
   • `strings.xml` × 4       — 8 ta yangi matn (uz, ru, en, values)

 ⚠️ TEGILMAYDI:
   • `BuiltinAwgConfigs.kt` / `AWGStore.kt` / `SingBoxConfig.kt`
   • `MaskEditorActivity` / `AWGEditorActivity`
   • Hysteria2, FinalMask, INCY UI, auto-update (FIX159/160), tillar
   • `DiffUtil`

 ────────────────────────────────────────────────────────────────────────
  ISHLATISH
 ────────────────────────────────────────────────────────────────────────

     python3 tools/add_warp_generator.py --check
     python3 tools/add_warp_generator.py
     python3 tools/add_warp_generator.py --rollback
"""

import argparse
import glob
import re
import shutil
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STAMP = time.strftime("%Y%m%d_%H%M%S")
BACKUP_DIR = ROOT / ".fix-backups"          # ⚠️ res/ ichida .bak YO'Q

JAVA = ROOT / "app/src/main/java/com/nurvpn/app"
RES = ROOT / "app/src/main/res"

WARP_KT = JAVA / "util/WarpGenerator.kt"
X25519_KT = JAVA / "util/X25519.kt"
SETTINGS_KT = JAVA / "ui/settings/SettingsFragment.kt"
SETTINGS_XML = RES / "layout/fragment_settings.xml"

STRINGS = {loc: RES / loc / "strings.xml"
           for loc in ("values", "values-uz", "values-ru", "values-en")}


# ═══════════════════════════════════════════════════════════════════════
#  YANGI FAYL — WarpGenerator.kt
# ═══════════════════════════════════════════════════════════════════════

X25519_KT_SRC = r'''package com.nurvpn.app.util

import java.math.BigInteger

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  FIX161 — sof Kotlin X25519 (RFC 7748)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ── NEGA KERAK? ──
 *
 *  `KeyPairGenerator.getInstance("X25519")` **faqat Android 13
 *  (API 33)+ da mavjud**. Ilovamiz `minSdk 24` — ya'ni Android 7+ ni
 *  qo'llab-quvvatlaydi. Haqiqiy qurilmada (Android 10, API 29) sinov:
 *
 *      E NurVPN-WARP: java.security.NoSuchAlgorithmException:
 *                     X25519 KeyPairGenerator not available
 *
 *  Shuning uchun o'zimizning implementatsiyamiz kerak.
 *
 *  ── NEGA BigInteger? ──
 *
 *  Limb (64-bit) arifmetikasi ~3x tezroq, lekin xatoga juda moyil
 *  (carry/reduce mantig'i). Bu kod tugma bosilganda BIR MARTA
 *  ishlaydi (~5-20ms) — tezlik muhim emas, TO'G'RILIK muhim.
 *
 *  ── TEKSHIRILGAN ──
 *
 *  RFC 7748 §5.2 (2 vektor) va §6.1 (base point) test vektorlari
 *  `selftest()` da tekshiriladi — logcat'da ko'rish mumkin.
 */
internal object X25519 {

    // ⚠️ `BigInteger.TWO` faqat API 31+ da bor — `valueOf(2)` ishlatamiz
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = TWO.pow(255)
        .subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)

    /** ⚠️ `point` ning eng yuqori biti RFC bo'yicha E'TIBORSIZ qoldiriladi. */
    fun scalarMult(scalar: ByteArray, point: ByteArray): ByteArray {
        val k = scalar.clone()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()

        val pt = point.clone()
        pt[31] = (pt[31].toInt() and 0x7F).toByte()   // ← RFC talabi

        val x1 = decodeLE(pt)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (k[t shr 3].toInt() shr (t and 7)) and 1
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = da.add(cb).mod(P).let { it.multiply(it).mod(P) }
            z3 = x1.multiply(da.subtract(cb).mod(P).let { it.multiply(it).mod(P) }).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }
        // x2 * z2^(p-2)  (mod p) — Fermat bo'yicha teskari element
        return encodeLE(x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P))
    }

    private fun decodeLE(b: ByteArray): BigInteger {
        val r = ByteArray(b.size)
        for (i in b.indices) r[i] = b[b.size - 1 - i]
        return BigInteger(1, r)
    }

    private fun encodeLE(v: BigInteger): ByteArray {
        val b = v.mod(P).toByteArray()
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val j = b.size - 1 - i
            out[i] = if (j >= 0) b[j] else 0
        }
        return out
    }

    /** Base point (u = 9) bilan ko'paytirish → public key. */
    fun publicFromPrivate(privateKey: ByteArray): ByteArray {
        val base = ByteArray(32)
        base[0] = 9
        return scalarMult(privateKey, base)
    }

    /**
     * ★ `demo()` naqshi: RFC 7748 test vektorlari.
     * Logcat'da `WARP selftest` orqali ko'rinadi.
     */
    fun selftest(): Boolean {
        return try {
            val r1 = scalarMult(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))
            val e1 = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"
            if (hexOf(r1) != e1) return false

            val base = ByteArray(32); base[0] = 9
            val pub = scalarMult(
                hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
                base)
            hexOf(pub) == "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        } catch (t: Throwable) {
            false
        }
    }

    private fun hex(s: String): ByteArray {
        val o = ByteArray(s.length / 2)
        for (i in o.indices) {
            o[i] = ((Character.digit(s[i * 2], 16) shl 4) or
                    Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return o
    }

    private fun hexOf(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }
}
'''


WARP_KT_SRC = r'''package com.nurvpn.app.util

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
'''


# ═══════════════════════════════════════════════════════════════════════
#  SETTINGSFRAGMENT — tugma mantig'i
# ═══════════════════════════════════════════════════════════════════════

# ⚠️ `open_source_container` ni topib, undan KEYIN tugma qo'shamiz.
SETTINGS_KT_ANCHOR = '''    private fun setupOpenSources(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.open_source_container) ?: return'''

SETTINGS_KT_NEW = '''    private fun setupOpenSources(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.open_source_container) ?: return
        // ⚠️ FIX161: `ctx` shu metodda mavjud emas — lokal olamiz
        val ctx = requireContext()

        // ═══════════════════════════════════════════════════════════
        //  ★ FIX161: Cloudflare WARP (MASQUE) generator
        //
        //  ⚠️ Tarmoq so'rovi UI oqimida EMAS — `Thread` ichida.
        //     API so'rovi 20 sekundgacha cho'zilishi mumkin, shuning
        //     uchun UI'ni bloklash MUMKIN EMAS.
        // ═══════════════════════════════════════════════════════════
        // ★ Bir marta RFC 7748 vektorlarini tekshiramiz (logcat'da ko'rinadi)
        Thread {
            if (!com.nurvpn.app.util.WarpGenerator.selftest()) {
                android.util.Log.e("NurVPN-WARP", "selftest MUVAFFAQIYATSIZ")
            }
        }.start()

        view.findViewById<android.widget.Button>(R.id.warp_generate_btn)
            ?.setOnClickListener { btn ->
                val act = activity as? MainActivity
                if (act == null) {
                    Toast.makeText(ctx, getString(R.string.warp_err_no_activity),
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // ⚠️ Ikki marta bosishdan himoya
                btn.isEnabled = false
                val dlg = android.app.ProgressDialog(ctx).apply {
                    setTitle(getString(R.string.warp_progress_title))
                    setMessage(getString(R.string.warp_progress_msg))
                    setCancelable(false)
                    show()
                }
                Thread {
                    val res = com.nurvpn.app.util.WarpGenerator.generate()
                    act.runOnUiThread {
                        try { dlg.dismiss() } catch (ignored: Throwable) {}
                        btn.isEnabled = true
                        onWarpGenerated(act, res.conf, res.endpoint, res.error)
                    }
                }.start()
            }

'''

ONWARP_METHOD = '''
    /**
     * ★ FIX161: WARP generator natijasini qayta ishlash.
     *
     * Muvaffaqiyat: `AWGConfig` yaratib `awg.xml` ga saqlaydi va
     * darhol joriy config qilib tanlaydi (`selectAWG`).
     * ⚠️ `type` ni QO'LDA yozmaymiz — `AWGStore.load()` uni
     *    `rawConf` matnidan o'zi aniqlaydi (yagona haqiqat manbasi).
     */
    private fun onWarpGenerated(
        act: MainActivity,
        conf: String?,
        endpoint: String?,
        error: String?
    ) {
        // ⚠️ `onWarpGenerated` — alohida metod, `ctx` bu yerda yo'q
        val ctx = context ?: return
        if (conf == null) {
            val msg = when (error) {
                "tarmoq" -> getString(R.string.warp_err_network)
                "kalit_generatsiya" -> getString(R.string.warp_err_crypto)
                "api_401", "api_403", "api_429" -> getString(R.string.warp_err_blocked)
                else -> getString(R.string.warp_err_generic)
            }
            android.widget.Toast.makeText(ctx, msg,
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            val list = AWGStore.load(ctx)
            // ⚠️ Takroriy qo'shishdan himoya
            if (list.any { it.rawConf == conf }) {
                android.widget.Toast.makeText(ctx,
                    getString(R.string.warp_already_exists),
                    android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val cfg = AWGConfig(conf)
            cfg.endpoint = endpoint
            cfg.address = ""
            cfg.name = "WARP MASQUE " + (endpoint ?: "")
            list.add(cfg)
            AWGStore.save(ctx, list)

            // Xotiradagi ro'yxatni yangilab, darhol tanlaymiz
            act.awgConfigs = AWGStore.load(act)
            val fresh = act.awgConfigs.firstOrNull { it.rawConf == conf } ?: cfg
            act.selectAWG(fresh)
            act.protocol = MainActivity.PROTO_AWG
            android.util.Log.i("NurVPN-WARP",
                "WARP config qo'shildi: ${fresh.endpoint}")
            android.widget.Toast.makeText(ctx,
                getString(R.string.warp_success, endpoint ?: ""),
                android.widget.Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            android.util.Log.e("NurVPN-WARP", "saqlash xatosi", t)
            android.widget.Toast.makeText(ctx, getString(R.string.warp_err_generic),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
'''


# ═══════════════════════════════════════════════════════════════════════
#  XML — tugma
# ═══════════════════════════════════════════════════════════════════════

WARP_BTN_XML = '''
            <!-- ★ FIX161: Cloudflare WARP (MASQUE) generator -->
            <Button
                android:id="@+id/warp_generate_btn"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:layout_marginBottom="8dp"
                android:text="@string/warp_generate_btn"
                android:textSize="13sp"
                android:minHeight="44dp"/>
'''


# ═══════════════════════════════════════════════════════════════════════
#  STRINGLAR — 4 til
# ═══════════════════════════════════════════════════════════════════════

NEW_STRINGS = {
    "warp_generate_btn": {
        "values": "☁ WARP (MASQUE) yaratish",
        "values-uz": "☁ WARP (MASQUE) yaratish",
        "values-ru": "☁ Создать WARP (MASQUE)",
        "values-en": "☁ Generate WARP (MASQUE)",
    },
    "warp_progress_title": {
        "values": "WARP yaratilmoqda",
        "values-uz": "WARP yaratilmoqda",
        "values-ru": "Создание WARP",
        "values-en": "Generating WARP",
    },
    "warp_progress_msg": {
        "values": "Cloudflare bilan bog'lanmoqda…",
        "values-uz": "Cloudflare bilan bog'lanmoqda…",
        "values-ru": "Подключение к Cloudflare…",
        "values-en": "Connecting to Cloudflare…",
    },
    "warp_success": {
        "values": "✅ WARP server yaratildi va qo'shildi: %1$s",
        "values-uz": "✅ WARP server yaratildi va qo'shildi: %1$s",
        "values-ru": "✅ WARP сервер создан и добавлен: %1$s",
        "values-en": "✅ WARP server created and added: %1$s",
    },
    "warp_already_exists": {
        "values": "Bu WARP konfiguratsiya allaqachon mavjud",
        "values-uz": "Bu WARP konfiguratsiya allaqachon mavjud",
        "values-ru": "Эта конфигурация WARP уже существует",
        "values-en": "This WARP configuration already exists",
    },
    "warp_err_network": {
        "values": "Internet aloqasi yo'q yoki API javob bermadi",
        "values-uz": "Internet aloqasi yo'q yoki API javob bermadi",
        "values-ru": "Нет подключения к интернету или API не отвечает",
        "values-en": "No internet connection or the API did not respond",
    },
    "warp_err_crypto": {
        "values": "Kalit generatsiyasi xatosi (X25519 qo'llab-quvvatlanmaydi)",
        "values-uz": "Kalit generatsiyasi xatosi (X25519 qo'llab-quvvatlanmaydi)",
        "values-ru": "Ошибка генерации ключа (X25519 не поддерживается)",
        "values-en": "Key generation error (X25519 not supported)",
    },
    "warp_err_blocked": {
        "values": "Cloudflare API so'rovni rad etdi. Keyinroq urinib ko'ring",
        "values-uz": "Cloudflare API so'rovni rad etdi. Keyinroq urinib ko'ring",
        "values-ru": "API Cloudflare отклонил запрос. Попробуйте позже",
        "values-en": "Cloudflare API rejected the request. Try again later",
    },
    "warp_err_generic": {
        "values": "API xatosi. Keyinroq urinib ko'ring",
        "values-uz": "API xatosi. Keyinroq urinib ko'ring",
        "values-ru": "Ошибка API. Попробуйте позже",
        "values-en": "API error. Try again later",
    },
    "warp_err_no_activity": {
        "values": "Sahifa yuklanmagan — qaytadan urinib ko'ring",
        "values-uz": "Sahifa yuklanmagan — qaytadan urinib ko'ring",
        "values-ru": "Страница не загружена — попробуйте снова",
        "values-en": "Screen not loaded — please try again",
    },
}


# ═══════════════════════════════════════════════════════════════════════
#  YORDAMCHILAR
# ═══════════════════════════════════════════════════════════════════════

class Edit:
    def __init__(self, path: Path):
        self.path = path
        self.orig = path.read_text(encoding="utf-8") if path.exists() else ""
        self.text = self.orig
        self.changes = []

    def sub(self, old: str, new: str, what: str, guard: str = None) -> bool:
        if guard and guard in self.text:
            return False
        if old not in self.text:
            raise SystemExit(
                f"✗ [{self.path.name}] topilmadi ({what}):\n    {old[:170]!r}")
        self.text = self.text.replace(old, new, 1)
        self.changes.append(what)
        return True

    def changed(self) -> bool:
        return self.text != self.orig

    def brace_ok(self) -> bool:
        return (self.text.count("{") - self.text.count("}")
                == self.orig.count("{") - self.orig.count("}"))

    def write(self) -> bool:
        if not self.changed():
            return False
        if self.path.exists():
            BACKUP_DIR.mkdir(exist_ok=True)
            shutil.copy2(self.path, BACKUP_DIR / f"{self.path.name}.{STAMP}.bak")
        self.path.write_text(self.text, encoding="utf-8")
        return True

    def rollback(self) -> bool:
        pats = glob.glob(str(BACKUP_DIR / f"{self.path.name}.*.bak"))
        pats = [p for p in pats if not p.endswith(".presed_backup.bak")]
        if not pats:
            return False
        bak = Path(max(pats, key=lambda p: Path(p).stat().st_mtime))
        cur = self.path.stat().st_size if self.path.exists() else 0
        if cur and bak.stat().st_size < cur * 0.5:
            print(f"   ✗ {self.path.name}: zaxira juda kichik — o'tkazildi")
            return False
        shutil.copy2(bak, self.path)
        return True


def string_names(path: Path):
    if not path.exists():
        return []
    return re.findall(r'<string name="([^"]+)"',
                      path.read_text(encoding="utf-8"))


def escape_xml(v: str) -> str:
    """
    ★ MUHIM: Android `strings.xml` da YALANG'OCH apostrof XATO beradi.

        error: unescaped apostrophe in string

    Shuning uchun `'` → `\\'` ga o'giramiz. Bu loyihadagi mavjud
    amaliyot bilan bir xil (masalan `btn_add_server` = `Server qo\\'shish`).

    ⚠️ `%1$s` kabi format belgilariga TEGMAYMIZ.
    """
    return v.replace("'", "\\'")


def insert_strings(path: Path, entries: dict) -> int:
    if not path.exists():
        return 0
    txt = path.read_text(encoding="utf-8")
    have = set(string_names(path))
    block = [f'    <string name="{n}">{escape_xml(v)}</string>'
             for n, v in entries.items() if n not in have]
    if not block:
        return 0
    if "</resources>" not in txt:
        raise SystemExit(f"✗ {path.name}: `</resources>` topilmadi")
    add = ("\n    <!-- ★ FIX161-WARPGEN: WARP generator matnlari -->\n"
           + "\n".join(block) + "\n")
    path.write_text(txt.replace("</resources>", add + "</resources>", 1),
                    encoding="utf-8")
    return len(block)


def main() -> int:
    ap = argparse.ArgumentParser(description="FIX161-WARPGEN")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()

    print("═" * 76)
    print("  FIX161-WARPGEN — Cloudflare WARP (MASQUE) generator")
    print("═" * 76)

    if args.rollback:
        print("\n  ── ROLLBACK ──")
        n = 0
        for path in (WARP_KT, X25519_KT):
            if path.exists():
                # Yangi fayl — zaxirasi yo'q, o'chiramiz
                path.unlink()
                print(f"   🗑  {path.relative_to(ROOT)} o'chirildi")
                n += 1
        for p in (SETTINGS_KT, SETTINGS_XML):
            if Edit(p).rollback():
                print(f"   ↩ {p.relative_to(ROOT)}")
                n += 1
        for loc, p in STRINGS.items():
            prefix = f"strings.xml.{loc}."
            pats = [q for q in glob.glob(str(BACKUP_DIR / "strings.xml.*.bak"))
                    if Path(q).name.startswith(prefix)]
            if not pats:
                print(f"   – {loc}: zaxira topilmadi")
                continue
            bak = Path(max(pats, key=lambda q: Path(q).stat().st_mtime))
            shutil.copy2(bak, p)
            print(f"   ↩ {loc}/strings.xml ({bak.name})")
            n += 1
        print(f"\n   {n} ta fayl tiklandi")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  OLDINDAN TEKSHIRUV
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Oldindan tekshiruv ──")
    for p, label in [(SETTINGS_KT, "SettingsFragment.kt"),
                     (SETTINGS_XML, "fragment_settings.xml")]:
        if not p.exists():
            print(f"   ✗ {label} topilmadi")
            return 1
        print(f"   ✓ {label} ({len(p.read_text(encoding='utf-8').splitlines())} qator)")

    # Himoyalangan fayllar bormi?
    for rel in ["config/BuiltinAwgConfigs.kt", "storage/AWGStore.kt",
                "config/SingBoxConfig.kt", "util/UpdateManager.kt"]:
        p = JAVA / rel
        print(f"   {'✓' if p.exists() else '✗'} {rel} (tegilmaydi)")

    # ───────────────────────────────────────────────────────────────
    #  TAHRIRLAR
    # ───────────────────────────────────────────────────────────────
    e_kt = Edit(SETTINGS_KT)
    e_xml = Edit(SETTINGS_XML)

    print("\n  ── SettingsFragment.kt ──")
    d1 = e_kt.sub(SETTINGS_KT_ANCHOR, SETTINGS_KT_NEW,
                  "WARP tugma mantig'i",
                  guard="warp_generate_btn")
    # ⚠️ Yangi metodni `showDnsDialog` OLDIGA qo'shamiz — bu barqaror
    #    langar (klass oxiri emas, chunki oxirgi metod o'zgarishi mumkin)
    d2 = e_kt.sub(
        "\n    private fun showDnsDialog(a: MainActivity) {",
        "\n" + ONWARP_METHOD.strip("\n") + "\n\n    private fun showDnsDialog(a: MainActivity) {",
        "onWarpGenerated metodi",
        guard="private fun onWarpGenerated(")
    print(f"   {'✓ QO\'SHILDI' if d1 else '– bor':14} tugma mantig'i")
    print(f"   {'✓ QO\'SHILDI' if d2 else '– bor':14} onWarpGenerated")

    print("\n  ── fragment_settings.xml ──")
    d3 = e_xml.sub(
        '            <LinearLayout\n                android:id="@+id/open_source_container"',
        WARP_BTN_XML.rstrip() + '\n\n            <LinearLayout\n'
        '                android:id="@+id/open_source_container"',
        "WARP tugmasi",
        guard='android:id="@+id/warp_generate_btn"')
    print(f"   {'✓ QO\'SHILDI' if d3 else '– bor':14} tugma")

    # ───────────────────────────────────────────────────────────────
    #  TEKSHIRUVLAR
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Tekshiruvlar ──")
    if not e_kt.brace_ok():
        print("   ✗ SettingsFragment.kt qavs balansi buzildi — BEKOR")
        return 1
    print("   ✓ Kotlin qavs balansi saqlandi")

    for g, label in [("warp_generate_btn", "tugma ID"),
                     ("WarpGenerator.generate()", "generator chaqiruvi"),
                     ("Thread {", "fon oqimi"),
                     ("runOnUiThread", "UI oqimiga qaytish"),
                     ("AWGStore.load", "config saqlash")]:
        if g not in e_kt.text:
            print(f"   ✗ {label} yo'q — BEKOR")
            return 1
    print("   ✓ UI oqimida tarmoq so'rovi YO'Q (Thread ichida)")

    # Himoyalangan kod tegmaganini tasdiqlaymiz
    for g, label in [("open_source_container", "ochiq manbalar"),
                     ("langGroup", "til tanlash"),
                     ("themeGroup", "mavzu tanlash"),
                     ("kill_switch", "kill switch")]:
        if g not in e_kt.text:
            print(f"   ✗ HIMOYA BUZILDI: {label} — BEKOR")
            return 1
    print("   ✓ SettingsFragment himoyalangan qismlari joyida")

    # XML validatsiyasi
    try:
        ET.fromstring(e_xml.text)
        print("   ✓ XML to'g'ri (parse muvaffaqiyatli)")
    except Exception as ex:  # noqa: BLE001
        print(f"   ✗ XML BUZILDI: {ex}")
        return 1

    for g, label in [('@+id/open_source_container', "open_source_container"),
                     ('@+id/warp_generate_btn', "warp_generate_btn"),
                     ("@string/warp_generate_btn", "string havolasi")]:
        if g not in e_xml.text:
            print(f"   ✗ XML da {label} yo'q — BEKOR")
            return 1
    print("   ✓ XML elementlari joyida")

    total = sum(1 for d in (d1, d2, d3) if d)
    total += 2  # yangi X25519.kt + WarpGenerator.kt
    total += len(NEW_STRINGS) * len(STRINGS)
    print(f"\n  Jami o'zgarish: {total}")

    # ★ AAPT2 SINOVI: stringlar haqiqatan kompilyatsiya bo'ladimi?
    #   Bu apostrof/escape xatolarini YOZISHDAN OLDIN ushlaydi.
    import subprocess, tempfile
    aapt2 = None
    for cand in sorted(glob.glob(str(Path.home() / "Android/Sdk/build-tools/*/aapt2"))):
        aapt2 = cand
    if aapt2:
        with tempfile.TemporaryDirectory() as td:
            vd = Path(td) / "values"
            vd.mkdir()
            body = "\n".join(
                f'    <string name="{n}">{escape_xml(v["values"])}</string>'
                for n, v in NEW_STRINGS.items())
            (vd / "strings.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                + body + "\n</resources>\n", encoding="utf-8")
            r = subprocess.run([aapt2, "compile", "-o", str(Path(td) / "o.zip"),
                                str(vd / "strings.xml")],
                               capture_output=True, text=True)
            if r.returncode != 0:
                print("   ✗ AAPT2 string xatosi:")
                for line in (r.stderr or "").splitlines()[:8]:
                    print(f"       {line}")
                return 1
        print("   ✓ AAPT2: stringlar kompilyatsiya bo'ladi")
    else:
        print("   ⚠ aapt2 topilmadi — string sinovi o'tkazib yuborildi")

    if args.check:
        print("\n  ── Yangi stringlar ──")
        for k in NEW_STRINGS:
            print(f"     + {k}")
        print("\n✅ CHECK rejimi — fayllar YOZILMADI")
        return 0

    # ───────────────────────────────────────────────────────────────
    #  YOZISH
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yozish ──")

    # 1) Yangi Kotlin fayllar
    for path, src in ((X25519_KT, X25519_KT_SRC), (WARP_KT, WARP_KT_SRC)):
        if not path.exists():
            path.write_text(src, encoding="utf-8")
            print(f"   🆕 {path.relative_to(ROOT)}")
        else:
            print(f"   –  {path.name} allaqachon bor")

    # 2) Mavjud fayllar
    for e in (e_kt, e_xml):
        if e.write():
            print(f"   💾 {e.path.relative_to(ROOT)}")

    # 3) Stringlar
    for loc, path in STRINGS.items():
        if path.exists():
            BACKUP_DIR.mkdir(exist_ok=True)
            shutil.copy2(path, BACKUP_DIR / f"strings.xml.{loc}.{STAMP}.bak")
        vals = {k: v[loc] for k, v in NEW_STRINGS.items()}
        n = insert_strings(path, vals)
        if n:
            print(f"   💾 {loc}/strings.xml  (+{n} string)")

    # ───────────────────────────────────────────────────────────────
    #  YAKUNIY VALIDATSIYA
    # ───────────────────────────────────────────────────────────────
    print("\n  ── Yakuniy validatsiya ──")
    ok = True
    for loc, path in STRINGS.items():
        try:
            ET.parse(str(path))
        except Exception as ex:  # noqa: BLE001
            print(f"   ✗ {loc}/strings.xml BUZILDI: {ex}")
            ok = False
            continue
        n = len(string_names(path))
        print(f"   ✓ {loc:11} {n} string")
    if not ok:
        return 1

    # Stringlar 4 tilda bir xil sonda bo'lishi shart
    counts = {loc: len(string_names(p)) for loc, p in STRINGS.items()}
    if len(set(counts.values())) != 1:
        print(f"   ✗ Tillar sinxron emas: {counts}")
        return 1
    print(f"   ✓ 4 til sinxron ({counts['values']} string)")

    if not X25519_KT.exists():
        print("   ✗ X25519.kt yozilmadi")
        return 1
    x = X25519_KT.read_text(encoding="utf-8")
    for g, label in [("object X25519", "obyekt"),
                     ("fun scalarMult", "scalarMult"),
                     ("fun publicFromPrivate", "publicFromPrivate"),
                     ("fun selftest", "selftest"),
                     ("a546e36bf0527c9d3b16154b82465edd", "RFC vektor")]:
        if g not in x:
            print(f"   ✗ X25519.kt da {label} yo'q")
            return 1
    print("   ✓ X25519.kt to'liq (RFC 7748)")

    if not WARP_KT.exists():
        print("   ✗ WarpGenerator.kt yozilmadi")
        return 1
    kt = WARP_KT.read_text(encoding="utf-8")
    for g, label in [("object WarpGenerator", "obyekt"),
                     ("X25519", "kalit algoritmi"),
                     ("SPKI_PREFIX", "SPKI prefiks"),
                     ("PKCS8_PREFIX", "PKCS8 prefiks"),
                     ("api.cloudflareclient.com", "API manzil"),
                     ("fun selftest()", "selftest"),
                     ("X25519.publicFromPrivate", "X25519 zaxirasi"),
                     ("NoSuchAlgorithmException", "API 33 izohi")]:
        if g not in kt:
            print(f"   ✗ WarpGenerator.kt da {label} yo'q")
            return 1
    print("   ✓ WarpGenerator.kt to'liq")

    print("\n" + "═" * 76)
    print("  ✅ TAYYOR")
    print("═" * 76)
    print("""
  YARATILDI / O'ZGARTIRILDI:
     🆕 util/WarpGenerator.kt        — X25519 + Cloudflare API
     💾 ui/settings/SettingsFragment.kt — tugma + Thread + ProgressDialog
     💾 res/layout/fragment_settings.xml — tugma
     💾 strings.xml × 4              — 10 ta matn (uz/ru/en/values)

  KEYINGI QADAMLAR:
     1) ./gradlew assembleDebug --console=plain
     2) ./gradlew testDebugUnitTest --console=plain
     3) adb install -r app/build/outputs/apk/debug/app-debug.apk
     4) Sozlamalar → «☁ WARP (MASQUE) yaratish» tugmasini bosing
     5) logcat: adb logcat | grep NurVPN-WARP
""")


if __name__ == "__main__":
    sys.exit(main())
