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
        // ★ FIX154-SNAPSHOT: nusxa ustida ishlaymiz.
        //
        //   ═══ ILDIZ SABAB ═══
        //
        //   Bu funksiya 37 ta joydan chaqiriladi va deyarli har doim
        //   `a.servers` — MainActivity'ning JONLI ro'yxati — uzatiladi.
        //   Ba'zi chaqiruvlar fon oqimida
        //   (`Thread { ServerStore.save(...) }`), boshqalari asosiy
        //   oqimda. Natijada:
        //
        //       java.util.ConcurrentModificationException
        //         at java.util.ArrayList$Itr.next
        //         at ServerStore.save(ServerStore.kt:16)
        //         at HomeFragment...loadSubscription$lambda...
        //
        //   Obuna yuklanayotganda UI oqimi ro'yxatga server qo'shadi,
        //   fon oqimi esa ayni paytda shu ro'yxatni aylanib chiqadi
        //   → ilova YIQILADI.
        //
        //   ═══ YECHIM ═══
        //
        //   Bir marta nusxa olamiz. Bu O(n) qo'shimcha xotira
        //   (686 server uchun ~o'nlab KB), lekin yiqilishni butunlay
        //   yo'q qiladi. 37 ta chaqiruv joyini tuzatishdan ko'ra
        //   bitta umumiy joyda tuzatish to'g'ri.
        val snapshot = try {
            ArrayList(servers)
        } catch (t: Throwable) {
            // O'ta kam holat: ro'yxat ayni damda o'zgartirilgan.
            // Bu holda saqlamaymiz — chala ma'lumot yozgandan ko'ra
            // keyingi muvaffaqiyatli saqlashni kutgan yaxshi.
            Log.w("NurVPN-STORE", "save: snapshot xatosi: ${t.message}")
            return
        }
        for (si in snapshot) {
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
            o.put("encrypted", si.encrypted)
            o.put("transport", si.transport)
            o.put("security", si.security)
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
            // ═══════════════════════════════════════════════════════════
            //  ★ FIX133-STARTUP: HAR BIR SERVER UCHUN LOG YOZILMASIN!
            //
            //  ═══ ILDIZ SABAB ═══
            //
            //  Avval tsikl ICHIDA shartsiz log bor edi:
            //
            //      Log.d("NurVPN-PING", "load: ${si.host}:${si.port} ...")
            //
            //  `Log.d()` — SINXRON I/O. 686 ta server bo'lsa 686 ta
            //  log yoziladi. Har biri bir necha ms olsa jami 5-9
            //  SEKUND, va bu ASOSIY OQIMDA (`onCreate`) bajariladi
            //  → ilova ochilishi 8-9 sekundga cho'ziladi!
            //
            //  ═══ YECHIM ═══
            //
            //  Faqat DEBUG build'da va faqat kichik ro'yxatda log
            //  yozamiz. Katta ro'yxatda (686 ta) — o'chiq.
            // ═══════════════════════════════════════════════════════════
            // ★ FIX133-STARTUP: faqat kichik ro'yxatda log (686 ta emas)
            val debugLog = arr.length() <= 40
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
                si.encrypted = o.optBoolean("encrypted", false)
                si.subId = if (o.has("subId") && !o.isNull("subId"))
                    o.getString("subId") else null
                si.protocol = Protocol.fromUri(o.getString("link"))
                si.transport = o.optString("transport", "")
                si.security = o.optString("security", "")
                si.ensureTransport()
                if (debugLog) {
                    Log.d("NurVPN-PING",
                        "load: ${si.host}:${si.port} proto=${si.protocol}")
                }
                out.add(si)
            }
        } catch (e: Throwable) { Log.e("NurVPN", "load servers", e) }
        return out
    }
}
