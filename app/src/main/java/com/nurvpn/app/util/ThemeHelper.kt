package com.nurvpn.app.util

import android.content.Context

object ThemeHelper {
    private const val PREF = "settings"

    fun getThemeMode(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("theme", "dark") ?: "dark"

    fun setThemeMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("theme", mode).apply()
        applyTheme(ctx)
    }

    fun applyTheme(ctx: Context) {
        val mode = getThemeMode(ctx)
        val nightMode = when (mode) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else    -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun getLanguage(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("lang", "") ?: ""

    fun setLanguage(ctx: Context, tag: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("lang", tag).apply()
}
