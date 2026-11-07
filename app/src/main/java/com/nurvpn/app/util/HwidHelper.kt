package com.nurvpn.app.util

import android.content.Context

/**
 * HWID (Hardware ID) — server tomonidan qurilmani tanish uchun ishlatiladi.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * MUHIM: ALGORITM O'ZGARTIRILMASLIGA DIQQAT
 * ═══════════════════════════════════════════════════════════════════════════
 * Bu funksiya avval `HomeFragment` va `ServersFragment` da AYNAN BIR XIL
 * (bayt-baytga) nusxada yozilgan edi. Endi bitta joyga yig'ildi.
 *
 * HWID qiymati obuna autentifikatsiyasida ishlatiladi va server tomonida
 * qurilma "davlat raqami" sifatida saqlanadi. Agar algoritm o'zgarsa:
 *   - Foydalanuvchi "yangi qurilma" deb ko'rinadi
 *   - Qurilma limiti (odatda 1-3 ta) tugab qoladi
 *   - Obuna ishlamay qolishi mumkin
 *
 * Shuning uchun [compute] funksiyasi Android'ga BOG'LIQ EMAS (sof funksiya) —
 * u unit testlar bilan qulflangan. Kirish qiymatlari o'zgarmasa, natija
 * ham o'zgarmaydi.
 *
 * Algoritm (o'zgarmas):
 *   SHA-256("<ANDROID_ID>-<Build.MODEL>-<packageName>") -> hex -> birinchi 32 belgi
 * ═══════════════════════════════════════════════════════════════════════════
 */
object HwidHelper {

    /** HWID uzunligi (hex belgilar). Server shu formatga tayanadi. */
    const val HWID_LENGTH = 32

    /** ANDROID_ID o'qib bo'lmasa ishlatiladigan qiymat. */
    const val FALLBACK_ANDROID_ID = "unknown"

    /** Build.MODEL bo'sh bo'lsa ishlatiladigan qiymat. */
    const val FALLBACK_MODEL = "device"

    /**
     * SOF FUNKSIYA — testlanadigan yadro.
     *
     * Android API'lariga murojaat qilmaydi, shuning uchun JVM unit testlarida
     * to'g'ridan-to'g'ri chaqiriladi.
     *
     * @param androidId  Settings.Secure.ANDROID_ID (yoki [FALLBACK_ANDROID_ID])
     * @param model      Build.MODEL (yoki [FALLBACK_MODEL])
     * @param packageName ilova paketi (masalan "com.nurvpn.app")
     * @return 32 belgili kichik harfli hex qator
     */
    fun compute(androidId: String, model: String, packageName: String): String {
        val raw = "$androidId-$model-$packageName"
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HWID_LENGTH)
    }

    /**
     * Qurilmadan HWID hisoblaydi.
     *
     * DIQQAT: qiymat `HwidStore` sozlamasiga BOG'LIQ EMAS. Avvalgi
     * fragmentlardagi nusxalar ham bu sozlamani tekshirmasdi — ya'ni
     * `HwidStore.setEnabled(false)` HWID uzatilishini to'xtatmasdi.
     * Xatti-harakatni o'zgartirmaslik uchun shu holat saqlandi.
     */
    fun fromContext(ctx: Context): String {
        val androidId = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: FALLBACK_ANDROID_ID
        } catch (t: Throwable) {
            FALLBACK_ANDROID_ID
        }

        val model = android.os.Build.MODEL ?: FALLBACK_MODEL

        return compute(androidId, model, ctx.packageName)
    }
}
