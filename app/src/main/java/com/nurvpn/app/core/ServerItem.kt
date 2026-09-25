package com.nurvpn.app.core

import com.nurvpn.app.util.CountryLookup
import java.util.Locale

class ServerItem(@JvmField var link: String) {
    var host: String? = null
    var port: Int = 0
    var remark: String? = null
    var countryCode: String = ""
    var country: String = ""
    var ping: Int = -1
    var subId: String? = null
    var favorite: Boolean = false

    
    var protocol: Protocol = Protocol.VLESS_REALITY

    fun flag(): String {
        val cc = countryCode.uppercase(Locale.US)
        if (cc.length == 2 && cc.all { it in 'A'..'Z' }) {
            val sb = StringBuilder()
            for (c in cc) {
                sb.appendCodePoint(0x1F1E6 + (c - 'A'))
            }
            return sb.toString()
        }
        // Remark dan emoji bayroqni olishga urinamiz
        val fromRemark = CountryLookup.flagFromRemark(remark)
        if (fromRemark.isNotEmpty()) return fromRemark
        return "🌍"
    }

    fun displayName(): String {
        if (!remark.isNullOrEmpty()) {
            val r = remark!!.replace("+", " ").replace("%20", " ").trim()
            // Agar remark juda uzun bo'lsa (butun link bo'lsa) — qisqartiramiz
            if (r.length > 60 || r.startsWith("vless://") ||
                r.startsWith("vmess://") || r.startsWith("hysteria2://") ||
                r.startsWith("hy2://") || r.startsWith("tuic://") ||
                r.startsWith("ss://") || r.startsWith("trojan://")) {
                return if (!host.isNullOrEmpty()) host!! else "server"
            }
            return r
        }
        if (!host.isNullOrEmpty()) return host!!
        return "server"
    }
}
