package com.nurvpn.app.storage

import android.content.Context

object SplitTunnelStore {
    const val MODE_ALL = 0
    const val MODE_WHITELIST = 1
    const val MODE_BLACKLIST = 2
    private const val PREF = "split"

    fun getMode(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt("mode", MODE_ALL)
    fun setMode(ctx: Context, mode: Int) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt("mode", mode).apply()

    fun getApps(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet("apps", emptySet()) ?: emptySet()
    fun setApps(ctx: Context, apps: Set<String>) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet("apps", HashSet(apps)).apply()
}

