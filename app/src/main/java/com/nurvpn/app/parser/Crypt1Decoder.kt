package com.nurvpn.app.parser

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nurvpn.app.R
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shifrlangan obuna linklarini decode qiladi.
 *
 * Qo'llab-quvvatlanadigan formatlar:
 *   - <prefix>://crypt1/<url-safe-base64-payload>
 *   - crypt1://<url-safe-base64-payload>
 *
 * Decode algoritmi:
 *  1. Base64 URL-safe decode (URL_SAFE | NO_PADDING | NO_WRAP)
 *  2. IV = data[0:12], ciphertext = data[12:]
 *  3. Kalit = SHA-256(m1 + m2 + m3 + m4 + assets_a[1024:1056] + assets_b[2048:2080])
 *  4. AES-GCM decrypt (128-bit tag)
 *  5. JSON: {"n": "name", "url": "subscription_url", "v": 1}
 */
object Crypt1Decoder {

    private const val TAG = "Crypt1Decoder"

    // ── Kalit materiallari (Base64'da yashirilgan, runtime'da decode) ──
    // m1: base64 — material 1
    // m2: base64 — material 2
    // m3: base64 — material 3
    // m4: base64 — material 4
    private val M1: String by lazy {
        String(Base64.decode("aW5jeQ==", Base64.DEFAULT))
    }
    private val M2: String by lazy {
        String(Base64.decode("ZGVlcA==", Base64.DEFAULT))
    }
    private val M3: String by lazy {
        String(Base64.decode("Y3J5cHQx", Base64.DEFAULT))
    }
    private val M4: String by lazy {
        String(Base64.decode("djIwMjYuMDY=", Base64.DEFAULT))
    }

    // Prefikslar (runtime'da quriladi)
    private val PREFIX_A: String by lazy { "$M1://crypt1/" }
    private val PREFIX_CRYPT: String by lazy { "$M3://" }
    private const val PREFIX_NURVPN = "nurvpn://"

    @Volatile
    private var cachedKey: ByteArray? = null

    /**
     * Kalitni hisoblash (bir marta, keyin cache).
     */
    private fun getKey(context: Context): ByteArray {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }

            val aPart = readAssetPart(context, "crypt_assets_a.bin", 1024, 32, "a")
            val bPart = readAssetPart(context, "crypt_assets_b.bin", 2048, 32, "b")

            val md = MessageDigest.getInstance("SHA-256")
            md.update(M1.toByteArray(Charsets.UTF_8))
            md.update(M2.toByteArray(Charsets.UTF_8))
            md.update(M3.toByteArray(Charsets.UTF_8))
            md.update(M4.toByteArray(Charsets.UTF_8))
            md.update(aPart)
            md.update(bPart)

            val key = md.digest()
            cachedKey = key
            Log.d(TAG, "Key computed")
            return key
        }
    }

    private fun readAssetPart(
        context: Context,
        fileName: String,
        offset: Int,
        length: Int,
        name: String
    ): ByteArray {
        val stream: InputStream = context.assets.open(fileName)
        val all = stream.use { it.readBytes() }
        if (offset + length > all.size) {
            throw IllegalStateException("Asset $name: ${all.size} < ${offset + length}")
        }
        return all.copyOfRange(offset, offset + length)
    }

    /**
     * Shifrlangan linkni decode qiladi.
     */
    fun decode(context: Context, uri: String): Pair<String, String?>? {
        try {
            val payload: String = when {
                uri.startsWith(PREFIX_A) -> uri.substring(PREFIX_A.length)
                uri.startsWith(PREFIX_CRYPT) -> uri.substring(PREFIX_CRYPT.length)
                uri.startsWith(PREFIX_NURVPN) -> uri.substring(PREFIX_NURVPN.length)
                else -> {
                    Log.w(TAG, "Unknown prefix")
                    return null
                }
            }.replace("\n", "").replace("\r", "")

            if (payload.isEmpty()) {
                Log.w(TAG, "Empty payload")
                return null
            }

            val data: ByteArray = try {
                Base64.decode(payload, 11)
            } catch (e: Exception) {
                Base64.decode(payload, 0)
            }

            if (data.size <= 28) {
                Log.w(TAG, "Too short: ${data.size}")
                return null
            }

            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)
            val key = getKey(context)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )
            val plaintext = cipher.doFinal(ciphertext)

            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            val url = json.optString("url").takeIf { it.isNotBlank() } ?: return null
            val name = json.optString("n").takeIf { it.isNotBlank() }

            Log.i(TAG, "Decoded OK")
            return Pair(url, name)
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed: ${e.message}")
            return null
        }
    }

    /**
     * Link shifrlangan formatdami?
     */
    fun isCryptLink(uri: String): Boolean {
        return uri.startsWith(PREFIX_A) ||
               uri.startsWith(PREFIX_CRYPT) ||
               uri.startsWith(PREFIX_NURVPN)
    }
}
