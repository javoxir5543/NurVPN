package com.nurvpn.app.storage

import android.content.Context
import com.nurvpn.app.core.ServerMetrics
import org.json.JSONObject

object MetricsStore {
    private const val PREF = "metrics"

    fun get(ctx: Context, link: String): ServerMetrics {
        val m = ServerMetrics(link)
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(link, null) ?: return m
            val o = JSONObject(s)
            m.avgLatency = o.optDouble("lat", 0.0)
            m.successRate = o.optDouble("succ", 0.5)
            m.samples = o.optInt("n", 0)
            m.fails = o.optInt("f", 0)
            m.totalBytesUp = o.optLong("up", 0)
            m.totalBytesDown = o.optLong("down", 0)
            m.lastGoodTime = o.optLong("t", 0)
        } catch (ignored: Throwable) {}
        return m
    }

    fun save(ctx: Context, m: ServerMetrics) {
        val o = JSONObject()
        o.put("lat", m.avgLatency)
        o.put("succ", m.successRate)
        o.put("n", m.samples)
        o.put("f", m.fails)
        o.put("up", m.totalBytesUp)
        o.put("down", m.totalBytesDown)
        o.put("t", m.lastGoodTime)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(m.link, o.toString()).apply()
    }

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().clear().apply()

    fun exportJson(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val out = JSONObject()
        for ((k, v) in prefs.all) out.put(k, v)
        return out.toString(2)
    }
}

