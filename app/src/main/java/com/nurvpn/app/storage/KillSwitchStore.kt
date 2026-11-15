package com.nurvpn.app.storage

import android.content.Context

/**
 * Kill-switch sozlamasi — ILOVA ICHIDA (tizim sozlamasi kerak emas).
 *
 * ★ C5: Foydalanuvchi qarori — dialogsiz ON/OFF switch.
 *   Yoqilgan bo'lsa, tunnel uzilganda barcha trafik BLOKLANADI
 *   (blackhole TUN orqali) va real IP oshkor bo'lmaydi.
 */
object KillSwitchStore {

    private const val PREFS = "nurvpn_security"
    private const val KEY = "kill_switch"

    /** Yoqilganmi? (sukut: O'CHIQ — foydalanuvchi o'zi yoqadi) */
    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    /** Yoqish/o'chirish. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
