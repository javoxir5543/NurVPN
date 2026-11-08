package com.nurvpn.app.util

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
