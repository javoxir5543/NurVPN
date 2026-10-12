# `app/src/main/java/com/nurvpn/app/storage/AdvSettings.kt`

```kotlin
package com.nurvpn.app.storage

import android.content.Context

/**
 * Kengaytirilgan sozlamalar — DPI bypass uchun.
 * FakeDNS, TLS Fragment, Mux.
 */
object AdvSettings {
    private const val PREF = "adv_settings"

    fun isFakeDnsEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("fakedns", false)

    fun setFakeDnsEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("fakedns", v).apply()

    fun isFragmentEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("fragment", true)

    fun setFragmentEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("fragment", v).apply()

    fun isMuxEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean("mux", false)

    fun setMuxEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("mux", v).apply()
}

```
