package com.nurvpn.app.storage

import android.content.Context
import android.util.Log
import com.nurvpn.app.core.Subscription
import org.json.JSONArray
import org.json.JSONObject

object SubscriptionStore {
    private const val PREF = "subscriptions"
    private const val KEY = "list"

    fun save(ctx: Context, subs: List<Subscription>) {
        val arr = JSONArray()
        for (sub in subs) {
            val o = JSONObject()
            o.put("id", sub.id)
            o.put("url", sub.url)
            o.put("name", sub.name)
            o.put("lastUpdated", sub.lastUpdated)
            o.put("updateIntervalHours", sub.updateIntervalHours)
            o.put("expireAt", sub.expireAt)
            o.put("trafficUsed", sub.trafficUsed)
            o.put("trafficTotal", sub.trafficTotal)
            o.put("serverCount", sub.serverCount)
            o.put("order", sub.order)
            o.put("sortMode", sub.sortMode)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(ctx: Context): MutableList<Subscription> {
        val out = ArrayList<Subscription>()
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sub = Subscription(
                    o.getString("id"),
                    o.getString("url"),
                    o.optString("name", "Subscription")
                )
                sub.lastUpdated = o.optLong("lastUpdated", 0)
                sub.updateIntervalHours = o.optInt("updateIntervalHours", 24)
                sub.expireAt = o.optLong("expireAt", 0)
                sub.trafficUsed = o.optLong("trafficUsed", 0)
                sub.trafficTotal = o.optLong("trafficTotal", 0)
                sub.serverCount = o.optInt("serverCount", 0)
                sub.order = o.optInt("order", 0)
                sub.sortMode = o.optString("sortMode", "default")
                out.add(sub)
            }
        } catch (e: Throwable) { Log.e("NurVPN", "load subscriptions", e) }
        return out
    }

    /** Obunani yuqoriga (dir=-1) yoki pastga (dir=+1) ko'chirish. */
    fun moveSubscription(ctx: Context, subId: String, dir: Int): Boolean {
        val list = load(ctx)
        if (list.isEmpty()) return false
        // Tartib bo'yicha saralash
        list.sortBy { it.order }
        val idx = list.indexOfFirst { it.id == subId }
        if (idx < 0) return false
        val newIdx = (idx + dir).coerceIn(0, list.size - 1)
        if (idx == newIdx) return false
        // Swap
        val tmp = list[idx]
        list[idx] = list[newIdx]
        list[newIdx] = tmp
        // Order'larni qayta yozamiz
        list.forEachIndexed { i, sub -> sub.order = i }
        save(ctx, list)
        return true
    }

    /** Obuna sortMode ni o'zgartirish. */
    fun setSortMode(ctx: Context, subId: String, mode: String) {
        val list = load(ctx)
        list.find { it.id == subId }?.sortMode = mode
        save(ctx, list)
    }

    fun delete(ctx: Context, sub: Subscription) {
        val list = load(ctx)
        list.removeAll { it.id == sub.id }
        save(ctx, list)
    }
}

// ═══════════ AI ENGINE ═══════════

