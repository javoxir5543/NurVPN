package com.nurvpn.app.storage

import android.content.Context
import android.util.Log
import com.nurvpn.app.core.Protocol
import com.nurvpn.app.core.ServerItem
import org.json.JSONArray
import org.json.JSONObject

object ServerStore {
    private const val PREF = "servers"
    private const val KEY = "list"

    fun save(ctx: Context, servers: List<ServerItem>) {
        val arr = JSONArray()
        for (si in servers) {
            val o = JSONObject()
            o.put("link", si.link)
            o.put("host", si.host)
            o.put("port", si.port)
            o.put("remark", si.remark)
            o.put("cc", si.countryCode)
            o.put("country", si.country)
            o.put("ping", si.ping)
            o.put("subId", si.subId ?: JSONObject.NULL)
            o.put("favorite", si.favorite)
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(ctx: Context): MutableList<ServerItem> {
        val out = ArrayList<ServerItem>()
        try {
            val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val si = ServerItem(o.getString("link"))
                si.host = o.optString("host", null)
                si.port = o.optInt("port", 0)
                si.remark = o.optString("remark", null)
                si.countryCode = o.optString("cc", "")
                si.country = o.optString("country", "")
                si.ping = o.optInt("ping", -1)
                si.favorite = o.optBoolean("favorite", false)
                si.subId = if (o.has("subId") && !o.isNull("subId"))
                    o.getString("subId") else null
                si.protocol = Protocol.fromUri(o.getString("link"))
                Log.d("NurVPN-PING", "load: ${si.host}:${si.port} proto=${si.protocol} link=${o.getString("link").take(20)}…")
                out.add(si)
            }
        } catch (e: Throwable) { Log.e("NurVPN", "load servers", e) }
        return out
    }
}
