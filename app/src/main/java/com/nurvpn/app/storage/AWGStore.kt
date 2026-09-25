package com.nurvpn.app.storage

import android.content.Context
import com.nurvpn.app.core.AWGConfig
import org.json.JSONArray
import org.json.JSONObject

object AWGStore {
    private const val PREF = "awg"
    private const val KEY = "configs"

    fun save(ctx: Context, configs: List<AWGConfig>) {
        val arr = JSONArray()
        for (c in configs) {
            val o = JSONObject()
            o.put("raw", c.rawConf)
            o.put("name", c.name)
            o.put("endpoint", c.endpoint)
            o.put("address", c.address ?: "")
            o.put("ping", c.ping)
            o.put("favorite", c.favorite)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(ctx: Context): MutableList<AWGConfig> {
        val out = ArrayList<AWGConfig>()
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = AWGConfig(o.optString("raw"))
                c.name = o.optString("name", null)
                c.endpoint = o.optString("endpoint", null)
                c.address = o.optString("address", null)
                c.favorite = o.optBoolean("favorite", false)
                c.ping = o.optInt("ping", -1)
                out.add(c)
            }
        } catch (ignored: Throwable) {}
        return out
    }
}

