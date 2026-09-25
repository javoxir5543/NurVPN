package com.nurvpn.app.storage

import com.nurvpn.app.core.AWGConfig

object AwgSortStore {
    private const val PREF = "awg_sort"
    private const val KEY = "mode"

    fun getMode(ctx: android.content.Context): String =
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .getString(KEY, "default") ?: "default"

    fun setMode(ctx: android.content.Context, mode: String) =
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()

    /** AWG listini sortMode bo'yicha saralash. */
    fun sort(list: List<AWGConfig>, mode: String): List<AWGConfig> =
        when (mode) {
            "name_asc" -> list.sortedBy { (it.name ?: "AWG").lowercase() }
            "name_desc" -> list.sortedByDescending { (it.name ?: "AWG").lowercase() }
            "ping_asc" -> list.sortedBy { if (it.ping < 0) Int.MAX_VALUE else it.ping }
            "ping_desc" -> list.sortedByDescending { it.ping }
            else -> list
        }
}

