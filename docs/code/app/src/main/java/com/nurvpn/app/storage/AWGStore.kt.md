# `app/src/main/java/com/nurvpn/app/storage/AWGStore.kt`

```kotlin
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
            o.put("type", c.type)
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
                // ★ Agar endpoint bo'sh bo'lsa — rawConf'dan hisoblaymiz
                if (c.endpoint.isNullOrEmpty()) {
                    val raw = c.rawConf ?: ""
                    var host = ""
                    var port = ""
                    for (line in raw.lines()) {
                        val t = line.trim()
                        when {
                            t.startsWith("Server") && t.contains("=") ->
                                host = t.substringAfter("=").trim()
                            t.startsWith("Endpoint") && t.contains("=") ->
                                host = t.substringAfter("=").trim()
                            t.startsWith("Port") && t.contains("=") ->
                                port = t.substringAfter("=").trim()
                        }
                    }
                    if (host.isNotEmpty()) {
                        // host allaqachon :port bilan bo'lishi mumkin
                        c.endpoint = if (host.contains(":")) host
                                     else if (port.isNotEmpty()) "$host:$port"
                                     else host
                    }
                }
                c.address = o.optString("address", null)
                c.favorite = o.optBoolean("favorite", false)
                c.ping = o.optInt("ping", -1)
                // ★ type HAR DOIM rawConf'dan aniqlanadi (saqlangan qiymatga ishonmaymiz)
                val raw = c.rawConf ?: ""
                c.type = if (!raw.contains("[Interface]") &&
                             !raw.contains("[Peer]") &&
                             raw.contains("PrivateKey") &&
                             raw.contains("PublicKey") &&
                             (raw.contains("Server") || raw.contains("Endpoint"))) {
                    "masque"
                } else {
                    "awg"
                }
                out.add(c)
            }
        } catch (ignored: Throwable) {}
        return out
    }
}

```
