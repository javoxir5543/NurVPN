package com.nurvpn.app.storage

import android.content.Context
import com.nurvpn.app.core.ServerItem

object ManualSortStore {
    private const val PREF = "manual_sort"
    private const val KEY = "mode"

    fun getMode(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "default") ?: "default"

    fun setMode(ctx: Context, mode: String) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()

    /** Qo'lda qo'shilgan serverlarni saralash. */
    fun sort(list: List<ServerItem>, mode: String): List<ServerItem> =
        when (mode) {
            "name_asc" -> list.sortedBy { it.displayName().lowercase() }
            "name_desc" -> list.sortedByDescending { it.displayName().lowercase() }
            "ping_asc" -> list.sortedBy { if (it.ping < 0) Int.MAX_VALUE else it.ping }
            "ping_desc" -> list.sortedByDescending { it.ping }
            else -> list
        }
}
