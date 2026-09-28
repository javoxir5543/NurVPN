package com.nurvpn.app.storage

import android.content.Context

/**
 * HWID (Device ID) yuborishni boshqaradi.
 * Ba'zi subscription provider'lar qurilma limitini HWID orqali tekshiradi.
 * Sukut bo'yicha: YOQILGAN (true).
 */
object HwidStore {
    private const val PREFS = "nurvpn_hwid"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)   // ★ default ON

    fun setEnabled(ctx: Context, enabled: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()

    /**
     * FIX #12: getHwid() DRY - HomeFragment va ServersFragment'da takrorlanardi.
     * Barqaror SHA-256 hash: ANDROID_ID + model + packageName.
     */
    fun getHwid(ctx: Context): String {
        if (!isEnabled(ctx)) return ""
        val androidId = try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (t: Throwable) { "unknown" }
        val model = android.os.Build.MODEL ?: "device"
        val packageName = ctx.packageName
        val raw = "$androidId-$model-$packageName"
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
