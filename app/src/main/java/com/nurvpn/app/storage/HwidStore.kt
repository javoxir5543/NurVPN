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
}
