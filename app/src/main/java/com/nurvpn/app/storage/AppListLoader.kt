package com.nurvpn.app.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.nurvpn.app.core.AppInfo
import java.util.concurrent.Executors

object AppListLoader {
    fun loadAsync(ctx: Context, cb: (List<AppInfo>) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val out = ArrayList<AppInfo>()
            try {
                val pm = ctx.packageManager
                val intents = pm.getInstalledApplications(
                    PackageManager.GET_META_DATA)
                for (app in intents) {
                    if (pm.getLaunchIntentForPackage(app.packageName) == null)
                        continue
                    val ai = AppInfo()
                    ai.packageName = app.packageName
                    ai.label = app.loadLabel(pm).toString()
                    ai.icon = app.loadIcon(pm)
                    out.add(ai)
                }
                out.sortBy { it.label.lowercase() }
            } catch (ignored: Throwable) {}
            Handler(Looper.getMainLooper()).post { cb(out) }
        }
    }
}

// ═══════════ AI CARD VIEW ═══════════

